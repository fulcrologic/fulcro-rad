(ns com.fulcrologic.rad.ids
  (:require
    [clojure.string :as str]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]))

(defn new-uuid
  "Without args gives random UUID. With args, builds UUID based on input.

  - If v is an int (in CLJC), it will generate a fixed UUID starting with FFF...and ending
    in that number.
  - If v is a uuid, it is just returned.
  - If v is non-nil it will be used as a string to generate a UUID (can fail).
  - If v is missing, you will get a random uuid."
  #?(:clj ([] (java.util.UUID/randomUUID)))
  #?(:clj ([v]
           (cond
             (uuid? v) v
             (int? v)
             (java.util.UUID/fromString
               (format "ffffffff-ffff-ffff-ffff-%012d" v))
             :else (java.util.UUID/fromString (str v)))))
  #?(:cljs ([] (random-uuid)))
  #?(:cljs ([v]
            (cond
              (uuid? v) v
              (int? v) (let [sv      (str v)
                             l       (.-length sv)
                             padding (str/join (repeat (- 12 l) "0"))]
                         (uuid (str "ffffffff-ffff-ffff-ffff-" padding sv)))
              :else (uuid (str v))))))

(>defn select-keys-in-ns
  "Returns a version of `m` where only those keys with namespace `nspc` are kept."
  [m nspc]
  [map? string? => map?]
  (reduce-kv
    (fn [new-map k v]
      (if (and (keyword? k) (= nspc (namespace k)))
        (assoc new-map k v)
        new-map))
    {} m))

