(ns com.fulcrologic.rad.ui-validation
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr tr-unsafe]]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]))

(defn invalid-attribute-value?
  "Returns true if the given `attribute` is invalid in the given form `env` context. This is meant to be used in UI
  functions, not resolvers/mutations. If there is a validator defined on the form it completely overrides all
  attribute validators."
  [env attribute]
  (let [form-instance    (::form/form-instance env)
        k                (::attr/qualified-key attribute)
        props            (comp/props form-instance)
        value            (and attribute (get props k))
        options          (comp/component-options form-instance)
        attribute-valid? (::attr/valid? attribute)
        checked?         (fs/checked? props k)
        form-validator   (get options ::form/validator)
        invalid?         (or
                           (and checked? (not form-validator) attribute-valid? (not (attribute-valid? value)))
                           (and form-validator (= :invalid (form-validator props k))))]
    invalid?))

(defn validation-error-message
  "Get the string that should be shown for the error message on a given attribute in the given form context."
  [env attribute]
  (let [{::form/keys [form-instance]} env
        k             (::attr/qualified-key attribute)
        props         (comp/props form-instance)
        value         (and attribute (get props k))
        options       (comp/component-options form-instance)
        attribute-msg (::attr/validation-message attribute)
        form-msg      (get-in options [::form/validation-messages k])
        message       (tr-unsafe
                        (or
                          (and form-msg (form-msg props k))
                          (and attribute-msg (attribute-msg value))
                          (tr "Invalid value")))]
    message))
