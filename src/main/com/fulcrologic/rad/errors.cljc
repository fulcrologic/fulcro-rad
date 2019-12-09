(ns com.fulcrologic.rad.errors
  "Support for consistent error reporting across all RAD projects/plugins. These errors report during development, but
  become no-ops in release builds that have zero overhead."
  #?(:cljs (:require-macros [com.fulcrologic.rad.errors]))
  (:require
    [taoensso.timbre :as log]
    [clojure.spec.alpha :as s]))

#?(:clj
   (defmacro required!
     "Log a readable error message and throw an exception if the given map `m` does not contain the key `k` whose
     value passes `(pred (get m k))`."
     ([context m k pred]
      `(when-not (and
                   (contains? ~m ~k)
                   (~pred (get ~m ~k)))
         (log/error ~context ": MUST contain key " ~k " that satisfies predicate " ~(str pred))))
     ([context m k]
      `(when-not (contains? ~m ~k)
         (log/error ~context ": You must supply key " ~k)))))

