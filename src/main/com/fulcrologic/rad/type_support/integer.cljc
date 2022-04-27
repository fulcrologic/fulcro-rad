(ns com.fulcrologic.rad.type-support.integer
  (:refer-clojure :exclude [parse-long])
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [cognitect.transit :as ct]))

(>defn parse-long
  "Parse a string into an integer value. In CLJS this can return a Number or a goog.math.Long
   if it would overflow a js Number. Returns 0 if it cannot be parsed."
  [v]
  [string? => int?]
  #?(:cljs
     (try (ct/integer v) (catch :default _ 0))
     :clj
     (try (Long/parseLong v) (catch Exception _ 0))))

(def parse-int parse-long)
