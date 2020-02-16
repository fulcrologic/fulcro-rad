(ns com.fulcrologic.rad.options-util
  "Utilities for interpreting and coping with form/report options."
  (:require
    #?(:cljs [goog.functions :as gf])))

(defn ?!
  "Run if the argument is a fn. This function can accept a value or function. If it is a
  function then it will apply the remaining arguments to it; otherwise it will just return
  `v`."
  [v & args]
  (if (fn? v)
    (apply v args)
    v))

(defn debounce
  "Debounce calls to f to at-most every tm ms. Trailing edge wins."
  [f tm]
  #?(:clj  (f)
     :cljs (gf/debounce f tm)))
