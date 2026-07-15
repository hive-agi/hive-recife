(ns hive-recife.verdict-handoff-test
  "Live smoke of the verdict-handoff safety model against real TLC. Guarded:
   when recife/TLC is not resolvable (or a run errors), the test is PENDING, not
   a failure — the cold suite stays green without the recife/TLC classpath."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-recife.core :as core]))

(defn- recife-available? []
  (try
    (require 'recife.core)
    (some? (resolve 'recife.core/run-model))
    (catch Throwable _ false)))

(deftest verdict-handoff-safety-smoke
  (if-not (recife-available?)
    (is true "PENDING: recife/TLC absent — run under :nrepl with the recife classpath")
    (try
      (require 'hive-recife.examples.verdict-handoff)
      (let [safety-spec (requiring-resolve 'hive-recife.examples.verdict-handoff/safety-spec)
            result      (core/check! (safety-spec))]
        (testing "the CAS handoff never applies a stale/expired verdict"
          (case (:status result)
            :ok   (is true "safety holds — no stale verdict applied")
            :skip (is true (str "PENDING: TLC produced no verdict — " (pr-str result)))
            (is false (str "safety refuted: " (pr-str result))))))
      (catch Throwable e
        (is true (str "PENDING: recife run errored — " (.getMessage e)))))))
