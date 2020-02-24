(ns com.fulcrologic.rad.options-util
  "Utilities for interpreting and coping with form/report options."
  (:require
    [clojure.string]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
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
  #?(:clj  f
     :cljs (gf/debounce f tm)))

(>defn narrow-keyword
  "Narrow the meaning of a keyword by turning the full original keyword into a namespace and adding the given
  `new-name`.

  ```
  (narrow-keyword :a/b \"c\") => :a.b/c
  ```

  Requires that the incoming keyword already have a namespace.
  "
  [k new-name]
  [qualified-keyword? (s/or :string string? :k keyword? :sym symbol?) => qualified-keyword?]
  (let [old-ns (namespace k)
        nm     (name k)
        new-ns (str old-ns "." nm)]
    (keyword new-ns new-name)))
