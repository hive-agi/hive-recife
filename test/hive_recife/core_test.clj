(ns hive-recife.core-test
  "What the schemas cannot state: the trace->status + violation classification
   tables, the rule-chain ORDER, and the DIP (injected effect fn) / OCP
   (rule-chain extension) contracts."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [hive-recife.schema :as schema]
            [hive-recife.core :as core]))

(def ^:private result? (m/validator schema/ModelCheckResult))
(def ^:private cex? (m/validator schema/CounterexampleTrace))

;; ---------------------------------------------------------------------------
;; recife-trace->status — the discriminator table (skip is NEVER ok)
;; ---------------------------------------------------------------------------

(deftest trace->status-table
  (testing "clean run"
    (is (= :ok (core/recife-trace->status :ok))))
  (testing "counterexample vector"
    (is (= :fail (core/recife-trace->status [[0 {:g/x 1}]])))
    (is (= :fail (core/recife-trace->status [[0 {}] [1 {}]]))))
  (testing "indeterminate is :skip, never :ok (Bug-1: skip != pass)"
    (is (= :skip (core/recife-trace->status :error)))
    (is (= :skip (core/recife-trace->status nil)))
    (is (= :skip (core/recife-trace->status [])))
    (is (not= :ok (core/recife-trace->status :error)))))

;; ---------------------------------------------------------------------------
;; classify-violation — table + rule ORDER
;; ---------------------------------------------------------------------------

(deftest classify-violation-table
  (testing "temporal violations are liveness"
    (is (= :liveness (core/classify-violation {:violation {:type :back-to-state}})))
    (is (= :liveness (core/classify-violation {:violation {:type :stuttering}}))))
  (testing "a bare counterexample is a safety (invariant) violation"
    (is (= :safety (core/classify-violation {})))
    ;; recife tags an invariant refutation {:violation {:type :invariant ...}}
    (is (= :safety (core/classify-violation {:violation {:type :invariant}})))
    (is (= :safety (core/classify-violation {:violation {:type :deadlock}})))
    (is (= :safety (core/classify-violation nil)))))

(deftest classify-violation-rule-order
  (testing "first match wins — the terminal :safety rule also matches, but the
            :back-to-state rule precedes it, so order (not the terminal) decides"
    (let [reversed (vec (reverse core/default-rules))]  ; terminal :safety first
      (is (= :liveness (core/classify-violation {:violation {:type :back-to-state}}
                                                core/default-rules)))
      (is (= :safety (core/classify-violation {:violation {:type :back-to-state}}
                                              reversed))
          "reordering so the catch-all leads changes the verdict — order is load-bearing"))))

(deftest classify-violation-ocp-extension
  (testing "OCP: a new rule prepended to the chain adds a case with no edit to classify"
    (let [rules (into [(core/rule #(= :timeout (get-in % [:violation :type])) :liveness)]
                      core/default-rules)]
      (is (= :liveness (core/classify-violation {:violation {:type :timeout}} rules)))
      (is (= :safety (core/classify-violation {:violation {:type :timeout}}
                                              core/default-rules))
          "the stock chain still classifies the unknown type as :safety"))))

;; ---------------------------------------------------------------------------
;; build-counterexample — get-trace parity + schema conformance
;; ---------------------------------------------------------------------------

(deftest build-counterexample-contract
  (let [raw {:trace [[0 {:g/x 0}] [1 {:g/x 1}]]
             :trace-info {:violation {:type :back-to-state}}}
        cex (core/build-counterexample raw)]
    (is (cex? cex) "conforms to CounterexampleTrace")
    (is (= :liveness (:violation-type cex)))
    (is (= 2 (:length cex)))
    (is (= [{:g/x 0} {:g/x 1}] (:states cex))
        "index dropped, mirroring recife.helpers/get-trace")))

;; ---------------------------------------------------------------------------
;; normalize — the three branches, output always conforms
;; ---------------------------------------------------------------------------

(deftest normalize-branches
  (testing ":ok carries the state counts"
    (let [r (core/normalize {:trace :ok :distinct-states 12 :generated-states 30})]
      (is (result? r))
      (is (= :ok (:status r)))
      (is (= 12 (get-in r [:details :distinct-states])))))
  (testing ":fail carries a counterexample"
    (let [r (core/normalize {:trace [[0 {:g/x 1}]] :trace-info {}})]
      (is (result? r))
      (is (= :fail (:status r)))
      (is (cex? (get-in r [:details :counterexample])))))
  (testing ":error and non-maps are :skip"
    (is (= :skip (:status (core/normalize {:trace :error :trace-info "boom"}))))
    (is (= :tlc-error (get-in (core/normalize {:trace :error}) [:details :reason])))
    (is (= :skip (:status (core/normalize nil))))
    (is (result? (core/normalize nil)))))

;; ---------------------------------------------------------------------------
;; check! — DIP: the effect fn is INJECTED; a skip is never a pass
;; ---------------------------------------------------------------------------

(def ^:private spec-stub
  {:name ::stub :init-state {:g/x 0} :components [] :safety [] :liveness []})

(deftest check!-injected-effect-fn
  (testing "an injected runner drives the verdict — no recife needed"
    (is (= :ok   (:status (core/check! spec-stub (fn [_] {:trace :ok})))))
    (is (= :fail (:status (core/check! spec-stub (fn [_] {:trace [[0 {:g/x 1}]]
                                                          :trace-info {}})))))
    (is (= :skip (:status (core/check! spec-stub (fn [_] core/recife-absent))))))
  (testing "recife-absent normalizes to a :skip reason, never :ok"
    (is (= :recife-absent
           (get-in (core/check! spec-stub (fn [_] core/recife-absent)) [:details :reason]))))
  (testing "every injected outcome conforms to ModelCheckResult"
    (doseq [runner [(fn [_] {:trace :ok})
                    (fn [_] {:trace [[0 {:g/x 1}]] :trace-info {}})
                    (fn [_] core/recife-absent)
                    (fn [_] nil)]]
      (is (result? (core/check! spec-stub runner))))))
