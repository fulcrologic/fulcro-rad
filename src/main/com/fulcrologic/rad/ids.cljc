(ns com.fulcrologic.rad.ids
  #?(:cljs
     (:require
       [cljs.core :as core])))

(defn new-uuid
  "Without args gives random UUID. With args, builds UUID based on input.

  - If input is an int (in CLJ), it will generate a fixed UUID starting with FFF...and ending
    in that number.
  - If input is a string, it will use that literal string
  - If input is missing, you will get a random uuid."
  #?(:clj ([] (java.util.UUID/randomUUID)))
  #?(:clj ([int-or-str]
           (if (int? int-or-str)
             (java.util.UUID/fromString
               (format "ffffffff-ffff-ffff-ffff-%012d" int-or-str))
             (java.util.UUID/fromString int-or-str))))
  #?(:cljs ([] (random-uuid)))
  #?(:cljs ([& args] (core/uuid (apply str args)))))

