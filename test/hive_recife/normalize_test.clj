(ns hive-recife.normalize-test
  "Free coverage: property + mutation for `normalize`, projected off the
   registered :hive.recife/raw-result -> :hive.recife/result schemas."
  (:require [hive-schemas.test :as hst]
            [hive-recife.schema]           ; registers :hive.recife/* keys (side-effect)
            [hive-recife.core :as core]))

;; conformance (output always a valid ModelCheckResult) + relation (status
;; faithfully reflects the :trace discriminator) + schema-derived mutation.
(hst/deftrifecta-from-schema normalize core/normalize
  {:in  :hive.recife/raw-result
   :out :hive.recife/result
   :rel (fn [raw out]
          (and (contains? #{:ok :fail :skip} (:status out))
               (map? (:details out))
               (case (:status out)
                 :ok   (= :ok (:trace raw))
                 :fail (boolean (and (sequential? (:trace raw)) (seq (:trace raw))))
                 :skip (not (and (sequential? (:trace raw)) (seq (:trace raw)))))))
   :mutation  true
   :num-tests 200})
