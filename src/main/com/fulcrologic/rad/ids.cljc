(ns com.fulcrologic.rad.ids
  #?(:cljs
     (:require
       [cljs.core :as core])))

(defn new-uuid
  "Without args gives random UUID. With args, builds UUID based on input.

  - If v is an int (in CLJ), it will generate a fixed UUID starting with FFF...and ending
    in that number.
  - If v is a uuid, it is just returned.
  - If v is non-nil it will be used as a string to generate a UUID (can fail).
  - If v is missing, you will get a random uuid."
  #?(:clj ([] (java.util.UUID/randomUUID)))
  #?(:clj ([v]
           (cond
             (uuid? v) v
             (int? int-or-str)
             (java.util.UUID/fromString
               (format "ffffffff-ffff-ffff-ffff-%012d" int-or-str))
             :else (java.util.UUID/fromString (str int-or-str)))))
  #?(:cljs ([] (random-uuid)))
  #?(:cljs ([& args] (core/uuid (apply str args)))))

