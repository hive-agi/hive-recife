(ns hive-recife.examples.verdict-handoff
  "recife spec of the verdict-handoff CAS/idempotence protocol as a safety +
   liveness model (a plan->commit handoff).

   REQUIRES recife on the classpath — load it LAZILY (never require this ns from
   hive-recife.core). `safety-spec` yields a hive-recife.schema/ModelSpec ready
   for hive-recife.core/check!.

   Protocol: a verdict is planned over a content-hash snapshot; files may DRIFT;
   the verdict may EXPIRE; COMMIT is a compare-and-swap that applies the patch
   ONLY when the verdict is still planned and the plan-hash still matches the
   file-hash. The safety invariant `never-apply-stale` asserts a stale verdict is
   never applied; commit freezes the observed hash into ::applied-hash so the CAS
   precondition is inductively checkable."
  (:require [recife.core :as r]
            [recife.helpers :as rh]))

;; SPDX-License-Identifier: MIT
;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>

(def global
  "Bounded global state (namespaced = global per recife convention). Hashes are
   kept in {0 1} so the state space is finite."
  {::plan-hash    0
   ::file-hash    0
   ::verdict      :planned
   ::applied?     false
   ::applied-hash 0})

(r/defproc handoff
  {:procs #{:committer}
   :local {:pc ::step}}
  {[::step {:action #{:drift :resync :expire :commit}}]
   (fn [{:keys [action] :as db}]
     (let [plan    (::plan-hash db)
           file    (::file-hash db)
           verdict (::verdict db)]
       (case action
         :drift  (assoc db ::file-hash (if (zero? file) 1 0))
         :resync (assoc db ::plan-hash file ::verdict :planned
                        ::applied? false ::applied-hash file)
         :expire (if (= verdict :planned) (assoc db ::verdict :expired) db)
         :commit (if (and (= verdict :planned) (= plan file))
                   (assoc db ::verdict :committed ::applied? true ::applied-hash file)
                   db))))})

(rh/definvariant never-apply-stale [db]
  (if (::applied? db)
    (= (::plan-hash db) (::applied-hash db))
    true))

(rh/defproperty eventually-committed [db]
  (rh/eventually (= (::verdict db) :committed)))

(defn safety-spec
  "A hive-recife.schema/ModelSpec for the safety model (process + the
   never-apply-stale invariant). Expected result: :ok (the CAS holds)."
  []
  {:name        ::verdict-handoff-safety
   :init-state  global
   :components  [handoff never-apply-stale]
   :safety      ["never-apply-stale"]
   :liveness    []})
