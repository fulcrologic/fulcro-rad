(ns com.fulcrologic.rad.ids-spec
  (:require
    [com.fulcrologic.rad.ids :as ids]
    [fulcro-spec.core :refer [assertions specification behavior component =>]]))

(defn less-than [x y & more]
  (if (< (compare x y) 0)
    (if (next more)
      (recur y (first more) (next more))
      (< (compare y (first more)) 0))
    false))

(specification "new-uuid"
  (behavior "Can generate a well-known UUID when given a number (for tests)"
    (assertions
      (ids/new-uuid 1) => #uuid "ffffffff-ffff-ffff-ffff-000000000001"
      (ids/new-uuid 2) => #uuid "ffffffff-ffff-ffff-ffff-000000000002"
      (ids/new-uuid 3) => #uuid "ffffffff-ffff-ffff-ffff-000000000003"))
  (behavior "Generates sequential UUIDs by default"
    (let [ids (repeatedly 20 ids/new-uuid)]
      (assertions
        (apply less-than ids) => true))))
