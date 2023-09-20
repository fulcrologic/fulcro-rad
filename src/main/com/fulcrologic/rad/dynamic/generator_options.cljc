(ns com.fulcrologic.rad.dynamic.generator-options
  (:require
    [com.fulcrologic.rad.options-util :refer [defoption]]))

(defoption reports
  "Place on an `identity? true` attribute. A map from symbol to a (fn [env]) that returns report options.

   The `env` will contain at least ::attr/key->attribute for the entire RAD model.")

(defoption forms
  "Place on an `identity? true` attribute. A map from symbol to a (fn [env]) that returns form options.

   The `env` will contain at least ::attr/key->attribute for the entire RAD model.")
