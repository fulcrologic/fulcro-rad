(ns com.fulcrologic.rad.type-support.cache-a-bools-spec
  (:require
    [com.fulcrologic.rad.type-support.cache-a-bools :as bool :refer [And Or Not True? False? as-boolean]]
    [fulcro-spec.core :refer [specification assertions component]]))

(specification
  "Cachable booleans"
  (let [ct bool/cachably-true
        cf bool/cachably-false
        ut bool/uncachably-true
        uf bool/uncachably-false]
    (component "Basic use as booleans"
      (assertions
        "True?"
        (True? ct) => true
        (True? ut) => true
        (True? cf) => false
        (True? uf) => false
        "as-boolean"
        (identical? true (as-boolean ct)) => true
        (identical? true (as-boolean ut)) => true
        (identical? false (as-boolean cf)) => true
        (identical? false (as-boolean uf)) => true
        ))
    (component "And"
      (assertions
        "Truth table"
        (True? (And ct ct ct ut ut ct)) => true
        (False? (And ct cf ct ut ut ct)) => true

        ))))
