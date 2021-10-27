(ns com.fulcrologic.rad.registered-maps
  "A dev-time map that behaves as-if it is mutable, so that closures over the map's value can be updated
   without having to regenerate those closures.

   Used to improve CLJ hot-code reload behavior.

   NOTE: You MUST do one of the following to ENABLE this feature during development:

   * Include the JVM option to set the property `rad.dev` (e.g. `-Drad.dev`)
   * Use `alter-var-root` on com.fulcrologic.rad.registered-maps/*enabled* to set the root binding to true.
   "
  (:require
    [potemkin.collections :refer [def-map-type]]))

(def ^:dynamic *enabled* (boolean (System/getProperty "rad.dev")))

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
   a map-like value that will access the registry for the values. Two maps registered under the same key will collide
   and be considered the same value, with the one registered later taking precedence.

   NOTE: Returns `value` unless registered maps are enabled. Registered maps are intended to be a dev-time convenience,
   and not a production feature.
   "
  [registration-key value]
  (if *enabled*
    (do
      (swap! registry assoc registration-key value)
      (->RegisteredMap registration-key))
    value))