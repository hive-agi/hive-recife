(ns hive-recife.schema
  "Malli value objects for the recife (TLA+/TLC) model-check adapter, registered
   into the hive-spi schema registry.

   Registered keys (contributed to the schema registry):
     :hive.recife/model-spec      a ModelSpec descriptor
     :hive.recife/raw-result      a permissive model of recife `get-result` output
     :hive.recife/counterexample  a normalized violation trace
     :hive.recife/result          a normalized ModelCheckResult

   ModelCheckResult contract: [:map {:closed true} [:status #{:ok :fail :skip}]
   [:details :map]]. :ok = no violation; :fail = a counterexample exists (in
   :details under :counterexample); :skip = indeterminate (recife/TLC absent, no
   spec, or a TLC error) — never conflate :skip with :ok."
  (:require [hive-spi.schema.registry :as reg]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

;; ---------------------------------------------------------------------------
;; Enums
;; ---------------------------------------------------------------------------

(def statuses
  "Normalized model-check statuses."
  [:enum :ok :fail :skip])

(def violation-types
  "The two TLC property classes a counterexample can refute."
  [:enum :safety :liveness])

;; A generatable surrogate for opaque global-state values. A recife global-state
;; value is any EDN; a bare :any in a generatable slot is the genbomb — the
;; :gen/schema override bounds generation to small scalars while validation stays
;; permissive.
(def ^:private scalar {:gen/schema [:or :int :boolean :keyword :string]})

(def ^:private GlobalState
  [:map-of {:gen/max 3} :qualified-keyword [:any scalar]])

;; ---------------------------------------------------------------------------
;; Value objects
;; ---------------------------------------------------------------------------

(def ModelSpec
  "Descriptor of a recife model: the init global-state, the opaque recife
   components (procs/invariants/properties), and the DECLARED names of the
   safety invariants + liveness properties the spec carries."
  [:map {:closed true}
   [:name :keyword]
   [:init-state GlobalState]
   [:components [:sequential {:gen/max 3} [:any {:gen/schema :keyword}]]]
   [:safety   [:sequential [:string {:min 1}]]]
   [:liveness [:sequential [:string {:min 1}]]]])

(def RawRecifeResult
  "Permissive model of what recife `get-result` returns. The :trace key is the
   status discriminator: :ok (clean) | :error (TLC error) | a vector of
   [idx state] pairs (a counterexample). Modeled for GENERATION of the adapter
   input; open so real recife maps (with :seed/:fp/:simulation/... ) also match."
  [:map {:closed false}
   [:trace [:or
            [:enum :ok :error]
            [:sequential {:gen/min 1 :gen/max 4}
             [:tuple :int GlobalState]]]]
   [:trace-info {:optional true}
    [:maybe [:or :string
             [:map [:violation {:optional true}
                    [:map [:type [:enum :stuttering :back-to-state]]]]]]]]
   [:distinct-states  {:optional true} [:maybe :int]]
   [:generated-states {:optional true} [:maybe :int]]])

(def CounterexampleTrace
  "A normalized violation trace: the classified violation kind, the trace length
   (>= 1 — a counterexample always has an initial state), and the sequence of
   global-state maps (the recife [idx state] pairs with the index dropped)."
  [:map {:closed true}
   [:violation-type violation-types]
   [:length [:int {:min 1}]]
   [:states [:sequential {:gen/min 1 :gen/max 4} GlobalState]]])

(def ModelCheckResult
  "The single normalized output of the adapter."
  [:map {:closed true}
   [:status statuses]
   [:details :map]])

;; ---------------------------------------------------------------------------
;; Registry contribution (DIP seam)
;; ---------------------------------------------------------------------------

(def registered-keys
  "The schema keys this ns contributes to the hive-spi registry."
  (reg/register-all!
   {:hive.recife/model-spec     ModelSpec
    :hive.recife/raw-result     RawRecifeResult
    :hive.recife/counterexample CounterexampleTrace
    :hive.recife/result         ModelCheckResult}))
