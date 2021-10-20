(ns com.fulcrologic.rad.registered-maps
  (:require
    [potemkin.collections :refer [def-map-type]]))

(def registry (atom {}))

(def-map-type RegisteredMap [reg-key]
  (get [this key default-value] (get-in @registry [reg-key key] default-value))
  (assoc [this key value] (assoc (get @registry reg-key) key value))
  (dissoc [this key] (dissoc (get @registry reg-key) key))
  (keys [this] (keys (get @registry reg-key)))
  (meta [this] (meta (get @registry reg-key)))
  (with-meta [this meta] (with-meta (get @registry reg-key) meta)))

(defn registered-map
  "Install the given `value` (a map) as a registered map for `registration-key` and return
   a map-like value that will access the registry for the values."
  [registration-key value]
  (swap! registry assoc registration-key value)
  (->RegisteredMap registration-key))