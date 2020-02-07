(ns com.fulcrologic.rad.rendering.semantic-ui.enumerated-field
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.form :as form]))

(defn enumerated-options [{::form/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{::attr/keys [enumerated-values]} attribute
        enumeration-labels (merge
                             (::attr/enumerated-labels attribute)
                             (comp/component-options form-instance ::form/enumerated-labels qualified-key))]
    (mapv (fn [k]
            {:text  (get enumeration-labels k (name k))
             :value k}) enumerated-values)))

(defn render-field [{::form/keys [form-instance] :as env} attribute]
  (let [k          (::attr/qualified-key attribute)
        {::form/keys [field-label]} attribute
        props      (comp/props form-instance)
        read-only? (form/read-only? form-instance attribute)
        options    (enumerated-options env attribute)
        value      (get props k)]
    #?(:cljs
       (div :.ui.field {:key (str k)}
         (label (or field-label (some-> k name str/capitalize)))
         (ui-wrapped-dropdown {:disabled read-only?
                               :options  options
                               :value    value
                               :onChange (fn [v]
                                           (form/input-changed! env k v))}))
       :clj
       (dom/div :.ui.selection.dropdown
         (dom/input {:type "hidden"})
         (dom/i :.dropdown.icon)
         (dom/div :.default.text "")
         (dom/div :.menu)))))

