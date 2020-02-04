(ns com.fulcrologic.rad.rendering.semantic-ui.field
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs [com.fulcrologic.fulcro.dom :refer [div label input]]
       :clj  [com.fulcrologic.fulcro.dom-server :refer [div label input]])
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ui-validation :as validation]))

(defn render-field-factory
  "Create a general field factory using the given input factory as the function to call to draw an input."
  [input-factory]
  (fn [{::form/keys [form-instance] :as env} attribute]
    (let [k                  (::attr/qualified-key attribute)
          props              (comp/props form-instance)
          value              (and attribute (get props k))
          invalid?           (validation/invalid-attribute-value? env attribute)
          validation-message (when invalid? (validation/validation-error-message env attribute))
          field-label        (form/field-label env attribute)
          read-only?         (form/read-only? form-instance attribute)]
      (div :.ui.field {:key     (str k)
                       :classes [(when invalid? "error")]}
        (label (str (or field-label (some-> k name str/capitalize))
                 (when validation-message (str ent/nbsp "(" validation-message ")"))))
        (if read-only?
          (div (str value))
          (input-factory {:value    value
                          :onBlur   (fn [v] (form/input-blur! env k v))
                          :onChange (fn [v] (form/input-changed! env k v))}))
        #_(when validation-message
          (div :.ui.error.message
            (str validation-message)))))))





