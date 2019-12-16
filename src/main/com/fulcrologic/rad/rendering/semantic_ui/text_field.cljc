(ns com.fulcrologic.rad.rendering.semantic-ui.text-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.dom.events :as evt]))

(defn render-field [{::form/keys [form-instance] :as env} attribute]
  (let [k          (::attr/qualified-key attribute)
        props      (comp/props form-instance)
        {::form/keys [field-label]} attribute
        read-only? (form/read-only? form-instance attribute)
        value      (or (and attribute (get props k)) "")]
    (div :.ui.field {:key (str k)}
      (label (or field-label (some-> k name str/capitalize)))
      (if read-only?
        (div value)
        (input {:value    value
                :onBlur   (fn [evt]
                            (form/input-blur! env k (evt/target-value evt)))
                :onChange (fn [evt]
                            (form/input-changed! env k (evt/target-value evt)))})))))

