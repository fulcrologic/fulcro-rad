(ns com.fulcrologic.rad.rendering.semantic-ui.entity-picker
  (:require
    [clojure.string :as str]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom :refer [div h3 button i span]]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div h3 button i span]])
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [com.fulcrologic.rad.ui-validation :as validation]
    [taoensso.timbre :as log]))

(defsc ToOnePicker [this {:keys [env attr]}]
  {:componentDidMount (fn [this]
                        (let [{:keys [env attr]} (comp/props this)]
                          (picker-options/load-options! (::form/form-instance env) attr)))}
  (let [{::form/keys [form-instance]} env
        {::form/keys [subforms]} (comp/component-options form-instance)
        {::form/keys           [field-label]
         ::picker-options/keys [cache-key query-key]
         ::attr/keys           [qualified-key]} attr
        TargetClass (get-in subforms [qualified-key ::form/ui])
        cache-key   (or cache-key query-key (log/error "Ref field MUST have either a ::picker-options/cache-key or ::picker-options/query-key in attribute " qualified-key))
        props       (comp/props form-instance)
        options     (get-in props [::picker-options/options-cache cache-key :options])
        value       (if TargetClass
                      (comp/get-ident TargetClass (get-in props [qualified-key]))
                      (log/error "Entity picker is missing ::form/ui option in subforms of " (comp/component-name form-instance)))
        invalid?    (validation/invalid-attribute-value? env attr)
        onSelect    (fn [v] (m/set-value! form-instance qualified-key v))]
    (div :.ui.field {:classes [(when invalid? "error")]}
      (dom/label (str field-label (when invalid? " (Required)")))
      (ui-wrapped-dropdown (cond->
                             {:onChange (fn [v] (onSelect v))
                              :value    value
                              :options  options})))))

(let [ui-to-one-picker (comp/factory ToOnePicker {:keyfn (fn [{:keys [attr]}] (::attr/qualified-key attr))})]
  (defn to-one-picker [env attribute]
    (ui-to-one-picker {:env  env
                       :attr attribute})))
