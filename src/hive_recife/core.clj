(ns hive-recife.core
  "recife (TLA+/TLC) model-check adapter.

   Stratified: pure promoters (trace->status, the violation rule-chain) ->
   pure pipeline (normalize a raw recife result into a ModelCheckResult) ->
   effectful boundary (`check!`, which runs a model through an INJECTED effect fn;
   the default lazily resolves recife via requiring-resolve so this ns loads on
   any classpath).

   `normalize` maps recife's `:trace` discriminator to a status:
     :trace :ok               -> {:status :ok}
     :trace [[idx state] ...] -> {:status :fail  :details {:counterexample ...}}
     :trace :error / other    -> {:status :skip}   (indeterminate; never :ok)"
  (:require [malli.core :as m]
            [hive-recife.schema :as schema]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; ===========================================================================
;; Promoters (pure) — one function, one decision
;; ===========================================================================

(defn recife-trace->status
  "Map a recife result's :trace value to a normalized status. A non-empty
   vector is a counterexample (:fail); :ok is clean; anything else (:error,
   nil, empty) is indeterminate (:skip)."
  [trace]
  (cond
    (= :ok trace)                      :ok
    (and (sequential? trace) (seq trace)) :fail
    :else                              :skip))

;; ---------------------------------------------------------------------------
;; OCP violation-classification rule-chain (protocol + ordered rules + fold)
;; ---------------------------------------------------------------------------

(defprotocol IViolationRule
  "A rule classifying a recife trace-info map as a safety/liveness refutation."
  (-match? [rule trace-info] "True iff this rule handles `trace-info`.")
  (-violation-class [rule] "The violation-type keyword this rule assigns."))

(defrecord FnViolationRule [match-fn klass]
  IViolationRule
  (-match? [_ ti] (boolean (match-fn ti)))
  (-violation-class [_] klass))

(defn rule
  "A violation rule from a predicate on trace-info and a violation-type."
  [match-fn klass]
  (->FnViolationRule match-fn klass))

(def default-rules
  "Ordered violation rules; first match wins. A temporal (liveness) refutation
   surfaces as a :back-to-state or :stuttering violation; a bare finite
   counterexample with no such marker is a safety (invariant) violation."
  [(rule #(= :back-to-state (get-in % [:violation :type])) :liveness)
   (rule #(= :stuttering    (get-in % [:violation :type])) :liveness)
   (rule #(= :invariant     (get-in % [:violation :type])) :safety)
   (rule (constantly true) :safety)])

(defn classify-violation
  "Classify a recife trace-info map as a :safety or :liveness refutation by
   folding the ordered rule-chain (first match wins). OCP: add a rule, not a
   branch. Total on the default chain (the terminal rule matches everything)."
  ([trace-info] (classify-violation trace-info default-rules))
  ([trace-info rules]
   (some->> rules
            (some #(when (-match? % trace-info) %))
            -violation-class)))

;; ===========================================================================
;; Pipeline (pure) — raw recife result -> ModelCheckResult
;; ===========================================================================

(defn build-counterexample
  "Build a CounterexampleTrace from a recife result whose :trace is a
   counterexample vector of [idx state] pairs (index dropped, as
   recife.helpers/get-trace does)."
  [{:keys [trace trace-info]}]
  (let [states (mapv second trace)]
    {:violation-type (classify-violation (if (map? trace-info) trace-info {}))
     :length         (max 1 (count states))
     :states         states}))

(defn normalize
  "Normalize a raw recife `get-result` value into a ModelCheckResult. Total:
   any value (incl. nil / a non-map) yields a {:status :details} map."
  [raw]
  (if-not (map? raw)
    {:status :skip :details {:reason :no-result}}
    (let [{:keys [trace trace-info distinct-states generated-states]} raw]
      (case (recife-trace->status trace)
        :ok
        {:status  :ok
         :details (cond-> {}
                    distinct-states  (assoc :distinct-states distinct-states)
                    generated-states (assoc :generated-states generated-states))}

        :fail
        {:status  :fail
         :details {:counterexample (build-counterexample raw)
                   :trace-info     (if (map? trace-info)
                                     trace-info
                                     {:message (some-> trace-info str)})}}

        :skip
        {:status  :skip
         :details {:reason  (if (= :error trace) :tlc-error :indeterminate)
                   :message (when-not (map? trace-info) (some-> trace-info str))}}))))

;; ===========================================================================
;; Boundary (effectful) — run a model through an INJECTED effect fn (DIP seam)
;; ===========================================================================

(def recife-absent
  "Sentinel a runner returns when recife/TLC is not on the classpath."
  ::recife-absent)

(defn- resolve-run-model
  "Lazily resolve recife's run-model, or nil when recife is not resolvable.
   Mirrors the requiring-resolve lazy-dep pattern so this ns never hard-depends
   on recife at load time."
  []
  (try (requiring-resolve 'recife.core/run-model)
       (catch Throwable _ nil)))

(defn default-runner
  "Effect fn: run `model-spec` through recife, returning the raw result map, or
   `recife-absent` when recife is unresolvable. In run-local mode (recife's
   default) run-model returns the result MAP directly; only the async subprocess
   path returns a derefable — deref that, else use the map as-is."
  [{:keys [init-state components]}]
  (if-let [run-model (resolve-run-model)]
    (let [ret (run-model init-state (set components))]
      (if (instance? clojure.lang.IDeref ret) (deref ret) ret))
    recife-absent))

(defn check!
  "Model-check `model-spec`, returning a normalized ModelCheckResult. The effect
   is INJECTED: `run-fn` maps a ModelSpec to a raw recife result (default:
   `default-runner`). `recife-absent`, nil, or a non-map result all normalize to
   {:status :skip} — a skip is NEVER a pass."
  ([model-spec] (check! model-spec default-runner))
  ([model-spec run-fn]
   (let [raw (run-fn model-spec)]
     (if (= recife-absent raw)
       {:status :skip :details {:reason :recife-absent}}
       (normalize raw)))))

;; ===========================================================================
;; Contract spine — m/=> (schemas sourced from hive-recife.schema)
;; ===========================================================================

(m/=> recife-trace->status [:=> [:cat :any] schema/statuses])
(m/=> classify-violation
      [:function
       [:=> [:cat [:maybe :map]] [:maybe schema/violation-types]]
       [:=> [:cat [:maybe :map] [:sequential :any]] [:maybe schema/violation-types]]])
(m/=> build-counterexample [:=> [:cat :map] schema/CounterexampleTrace])
(m/=> normalize [:=> [:cat :any] schema/ModelCheckResult])
(m/=> check!
      [:function
       [:=> [:cat schema/ModelSpec] schema/ModelCheckResult]
       [:=> [:cat schema/ModelSpec fn?] schema/ModelCheckResult]])
