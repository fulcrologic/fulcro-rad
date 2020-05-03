(ns com.fulcrologic.rad.locale
  "ALPHA. Will almost certainly change."
  #?(:cljs
     (:require
       [goog.object :as gobj]))
  #?(:clj (:import (java.util Locale))))

(defn current-locale []
  #?(:clj  (Locale/getDefault)
     :cljs (try
             (some->
               (gobj/get js/JSJodaLocale "Locale")
               (gobj/get "US"))
             (catch js/Error e))))

(defn set-locale!
  "Set the locale of the application to the given locale code, e.g. `en-US`.
  CURRENTLY A NO-OP"
  [code]
  )

