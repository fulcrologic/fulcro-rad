(ns com.fulcrologic.rad.rendering.semantic-ui.field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :refer [div label input]])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]))

(defn render-field-factory
  "Create a general field factory using the given input factory as the function to call to draw an input."
  [input-factory]
  (fn [{::form/keys [form-instance] :as env} attribute]
    (let [k          (::attr/qualified-key attribute)
          props      (comp/props form-instance)
          {::form/keys [field-label]} attribute
          read-only? (form/read-only? form-instance attribute)
          value      (or (and attribute (get props k)) 0)]
      (div :.ui.field {:key (str k)}
        (label (or field-label (some-> k name str/capitalize)))
        (if read-only?
          (div (str value))
          (input-factory {:value    value
                          :onBlur   (fn [v] (form/input-blur! env k v))
                          :onChange (fn [v] (form/input-changed! env k v))}))))))





