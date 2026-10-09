(ns hive-recife.tlc-opts-test
  "TLC opts passthrough: `tlc-opts` whitelists the TLC budget keys of a
   ModelSpec's :opts, and `default-runner` hands them to recife's run-model as
   its third argument. The `resolve-run-model` seam is private, so the pure
   projection is the trifecta subject; the threading through default-runner
   is not exercised here and rests on its one-line call site."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [malli.generator :as mg]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-recife.schema :as schema]
            [hive-recife.core :as core]))

;; SPDX-License-Identifier: MIT

(def ^:private tlc-opts? (m/validator schema/TlcOpts))
(def ^:private spec? (m/validator schema/ModelSpec))

(def ^:private base
  {:name ::spec :init-state {:g/x 0} :components [] :safety [] :liveness []})

(defn- opts-shape-ok?
  "Every projection conforms to TlcOpts, carries no key outside the whitelist,
   and never carries an empty :tlc-args."
  [out]
  (and (tlc-opts? out)
       (every? (set core/tlc-opt-keys) (keys out))
       (not (and (contains? out :tlc-args) (empty? (:tlc-args out))))))

(def ^:private gen-spec
  (gen/fmap #(assoc base :opts %) (mg/generator schema/TlcOpts)))

(def ^:private cases
  {:no-opts       base
   :workers-depth (assoc base :opts {:workers 4 :depth 50})
   :isolated-seed (assoc base :opts {:isolated true :seed 7 :no-deadlock true})
   :auto-workers  (assoc base :opts {:workers :auto})
   :tlc-args      (assoc base :opts {:tlc-args ["-fp" "3"]})
   :empty-args    (assoc base :opts {:tlc-args []})})

(deftrifecta tlc-opts-contract
  hive-recife.core/tlc-opts
  {:golden-path   "test/golden/tlc-opts.edn"
   :cases         cases
   :gen           gen-spec
   :pred          opts-shape-ok?
   :property-type :pred
   :num-tests     100
   :mutations
   [["drop-all-opts"   (fn [_spec] {})]
    ["leak-run-local"  (fn [spec] (assoc (:opts spec) :run-local false))]
    ["keep-empty-args" (fn [spec] (assoc (:opts spec) :tlc-args []))]]})

(deftest tlc-opts-projection
  (testing "whitelist: keys outside TlcOpts never reach recife"
    (is (= {:workers 2}
           (core/tlc-opts {:opts {:workers 2 :async false :run-local false}}))))
  (testing "no :opts leaves recife's defaults in force"
    (is (= {} (core/tlc-opts base))))
  (testing "nil values and empty :tlc-args are dropped"
    (is (= {:seed 1} (core/tlc-opts {:opts {:seed 1 :depth nil :tlc-args []}}))))
  (testing "a ModelSpec with :opts is still a valid ModelSpec"
    (is (spec? (:isolated-seed cases)))
    (is (not (spec? (assoc base :opts {:async true})))
        "TlcOpts is closed: recife process-control opts are refused")))
