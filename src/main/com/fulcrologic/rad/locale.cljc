(ns com.fulcrologic.rad.locale
  "ALPHA. Will almost certainly change."
  #?(:cljs
     (:require
       ["@js-joda/locale_en-us" :refer [Locale]]))
  #?(:clj (:import (java.util Locale))))

(def ^:dynamic *current-locale* #?(:clj  (Locale/getDefault)
                                   :cljs (.-US Locale)))

(defn current-locale [] *current-locale*)

(defn set-locale!
  "Set the locale of the application to the given Locale. In CLJS you will have to require the
  proper @js-joda/timezone file, like so:

  ```
  (:require
    [\"@js-joda/locale_en-us\" :refer [Locale]])
  ...
  ```

  (set-locale! (.-US Locale))

  "
  [locale]
  #?(:clj
     (alter-var-root (var *current-locale*) (constantly locale))))

