(ns ^:deprecated com.fulcrologic.rad.ui-validation
  "DEPRECATED: merged into form ns."
  (:require [com.fulcrologic.rad.form :as form]))

(def ^:deprecated invalid-attribute-value?
  "Use form/invalid-attribute-value? instead"
  form/invalid-attribute-value?)

(def ^:deprecated validation-error-message
  "Use form/validation-error-message instead"
  form/validation-error-message)
