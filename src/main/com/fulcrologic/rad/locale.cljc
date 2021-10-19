(ns com.fulcrologic.rad.locale
  #?(:cljs (:require-macros com.fulcrologic.rad.locale))
  (:require
    #?@(:cljs [[goog.object :as gobj]]
        :clj  [[com.fulcrologic.fulcro.components :as comp]]))
  #?(:clj
     (:import (java.util Locale))))

(def ^:dynamic *current-locale*
  "The current locale. In CLJ this will be a Locale object. In CLJS this will be a locale string like en-US."
  #?(:clj  (Locale/getDefault)
     :cljs "en-US"))

(defn current-locale [] *current-locale*)

(defn set-locale!
  "Set the locale of the application to the given locale code, e.g. `en-US`."
  [code]
  #?(:clj
     (alter-var-root (var *current-locale*) (constantly (Locale/forLanguageTag code)))
     :cljs
     (set! *current-locale* code)))

#?(:clj
   (defmacro with-locale [nm & body]
     (let [locale-expr (if (comp/cljs? &env)
                         nm
                         `(java.util.Locale/forLanguageTag ~nm))]
       `(binding [*current-locale* ~locale-expr]
          ~@body))))

