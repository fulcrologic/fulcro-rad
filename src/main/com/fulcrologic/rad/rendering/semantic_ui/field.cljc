(ns com.fulcrologic.rad.rendering.semantic-ui.field
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    #?(:cljs [com.fulcrologic.fulcro.dom :refer [div label input span]]
       :clj  [com.fulcrologic.fulcro.dom-server :refer [div label input span]])
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ui-validation :as validation]
    [taoensso.timbre :as log]))

(defn render-field-factory
  "Create a general field factory using the given input factory as the function to call to draw an input."
  ([input-factory]
   (render-field-factory {} input-factory))
  ([addl-props input-factory]
   (fn [{::form/keys [form-instance] :as env} {::attr/keys [type qualified-key] :as attribute}]
     (let [props              (comp/props form-instance)
           value              (or (form/computed-value env attribute)
                                (and attribute (get props qualified-key)))
           invalid?           (validation/invalid-attribute-value? env attribute)
           validation-message (when invalid? (validation/validation-error-message env attribute))
           user-props         (form/field-style-config env attribute :input/props)
           field-label        (form/field-label env attribute)
           visible?           (form/field-visible? form-instance attribute)
           read-only?         (form/read-only? form-instance attribute)
           addl-props         (if read-only? (assoc addl-props :readOnly "readonly") addl-props)]
       (when visible?
         (div :.ui.field {:key     (str qualified-key)
                          :classes [(when invalid? "error")]}
           (label
             (or field-label (some-> qualified-key name str/capitalize))
             (when validation-message (str ent/nbsp "(" validation-message ")")))
           (div :.ui.input {:classes [(when read-only? "transparent")]}
             (input-factory (merge addl-props
                              {:value    value
                               :onBlur   (fn [v] (form/input-blur! env qualified-key v))
                               :onChange (fn [v] (form/input-changed! env qualified-key v))}
                              user-props)))
           #_(when validation-message
               (div :.ui.error.message
                 (str validation-message)))))))))
