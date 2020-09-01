(ns com.fulcrologic.rad.ids
  "Functions supporting various ID concerns."
  (:require
    [clojure.string :as str]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.rad.type-support.integer :as int]
    [taoensso.timbre :as log])
  #?(:clj
     (:import (java.util UUID))))

(defn valid-uuid-string?
  "Returns true if the given string appears to be a valid UUID string."
  [s]
  (boolean
    (and
      (string? s)
      (re-matches #"^........-....-....-....-............$" s))))

(defn new-uuid
  "Without args gives random UUID. With args, builds UUID based on input.

  - If v is an int (in CLJC), it will generate a fixed UUID starting with FFF...and ending
    in that number.
  - If v is a uuid, it is just returned.
  - If v is non-nil it will be used as a string to generate a UUID (can fail).
  - If v is missing, you will get a random uuid."
  #?(:clj ([] (UUID/randomUUID)))
  #?(:clj ([v]
           (cond
             (uuid? v) v
             (int? v)
             (UUID/fromString
               (format "ffffffff-ffff-ffff-ffff-%012d" v))
             :else (UUID/fromString (str v)))))
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

(defn id-string->id
  "When forms are routed to their ID is in the URL as a string. This converts IDs in such a string format to the
   given type (which must be a RAD type name that supports IDs like :uuid, :int, :long or :string)."
  [type id]
  (case type
    :uuid (new-uuid id)
    :int (int/parse-int id)
    :long (int/parse-long id)
    :string id
    (do
      (log/error "Unsupported ID type" type)
      id)))

