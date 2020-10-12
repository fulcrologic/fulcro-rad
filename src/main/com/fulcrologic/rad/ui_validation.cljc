(ns com.fulcrologic.rad.ui-validation
  (:require
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [taoensso.timbre :as log]))

(defn invalid-attribute-value?
  "Returns true if the given `attribute` is invalid in the given form `env` context. This is meant to be used in UI
  functions, not resolvers/mutations. If there is a validator defined on the form it completely overrides all
  attribute validators."
  [{::form/keys [form-instance master-form] :as env} attribute]
  (let [k              (::attr/qualified-key attribute)
        props          (comp/props form-instance)
        value          (and attribute (get props k))
        checked?       (fs/checked? props k)
        required?      (get attribute ao/required? false)
        form-validator (comp/component-options master-form ::form/validator)
        invalid?       (or
                         (and checked? required? (or (nil? value) (and (string? value) (empty? value))))
                         (and checked? (not form-validator) (not (attr/valid-value? attribute value)))
                         (and form-validator (= :invalid (form-validator props k))))]
    invalid?))

(defn validation-error-message
  "Get the string that should be shown for the error message on a given attribute in the given form context."
  [{::form/keys [form-instance master-form] :as env} {:keys [::form/validation-message ::attr/qualified-key] :as attribute}]
  (let [props          (comp/props form-instance)
        value          (and attribute (get props qualified-key))
        master-message (comp/component-options master-form ::form/validation-messages qualified-key)
        local-message  (comp/component-options form-instance ::form/validation-messages qualified-key)
        message        (or
                         (?! master-message props qualified-key)
                         (?! local-message props qualified-key)
                         (?! validation-message value)
                         (tr "Invalid value"))]
    message))
