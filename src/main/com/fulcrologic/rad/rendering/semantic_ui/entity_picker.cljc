(ns com.fulcrologic.rad.rendering.semantic-ui.entity-picker
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom :refer [div h3 button i span]]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div h3 button i span]])
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [com.fulcrologic.rad.ui-validation :as validation]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]))

(defsc ToOnePicker [this {:keys [env attr]}]
  {:componentDidMount (fn [this]
                        (let [{:keys [env attr]} (comp/props this)
                              form-instance (::form/form-instance env)
                              props         (comp/props form-instance)
                              form-class    (comp/react-type form-instance)]
                          (picker-options/load-options! form-instance form-class props attr)))}
  (let [{::form/keys [master-form form-instance]} env
        {::form/keys [attributes field-options]} (comp/component-options form-instance)
        {::attr/keys [qualified-key required?]} attr
        field-options (get field-options qualified-key)
        target-id-key (first (keep (fn [{k ::attr/qualified-key ::attr/keys [target]}]
                                     (when (= k qualified-key) target)) attributes))
        {::picker-options/keys [cache-key query-key]} (merge attr field-options)
        cache-key     (or (?! cache-key (comp/react-type form-instance) (comp/props form-instance)) query-key)
        cache-key     (or cache-key query-key (log/error "Ref field MUST have either a ::picker-options/cache-key or ::picker-options/query-key in attribute " qualified-key))
        props         (comp/props form-instance)
        options       (get-in props [::picker-options/options-cache cache-key :options])
        value         [target-id-key (get-in props [qualified-key target-id-key])]
        field-label   (form/field-label env attr)
        read-only?    (or (form/read-only? master-form attr) (form/read-only? form-instance attr))
        invalid?      (and (not read-only?) (validation/invalid-attribute-value? env attr))
        onSelect      (fn [v]
                        (form/input-changed! env qualified-key v))]
    (div :.ui.field {:classes [(when invalid? "error")]}
      (dom/label field-label (when invalid? " (Required)"))
      (if read-only?
        (let [value (first (filter #(= value (:value %)) options))]
          (:text value))
        (ui-wrapped-dropdown (cond->
                               {:onChange  (fn [v] (onSelect v))
                                :value     value
                                :clearable (not required?)
                                :disabled  read-only?
                                :options   options}))))))

(let [ui-to-one-picker (comp/factory ToOnePicker {:keyfn (fn [{:keys [attr]}] (::attr/qualified-key attr))})]
  (defn to-one-picker [env attribute]
    (ui-to-one-picker {:env  env
                       :attr attribute})))

(defsc ToManyPicker [this {:keys [env attr]}]
  {:componentDidMount (fn [this]
                        (let [{:keys [env attr]} (comp/props this)
                              form-instance (::form/form-instance env)
                              props         (comp/props form-instance)
                              form-class    (comp/react-type form-instance)]
                          (picker-options/load-options! form-instance form-class props attr)))}
  (let [{::form/keys [form-instance]} env
        visible? (form/field-visible? form-instance attr)]
    (when visible?
      (let [{::form/keys [attributes field-options]} (comp/component-options form-instance)
            {attr-field-options ::form/field-options
             ::attr/keys        [qualified-key]} attr
            field-options      (get field-options qualified-key)
            target-id-key      (first (keep (fn [{k ::attr/qualified-key ::attr/keys [target]}]
                                              (when (= k qualified-key) target)) attributes))
            {:keys                 [style]
             ::picker-options/keys [cache-key query-key]} (merge attr-field-options field-options)
            cache-key          (or (?! cache-key (comp/react-type form-instance) (comp/props form-instance)) query-key)
            cache-key          (or cache-key query-key (log/error "Ref field MUST have either a ::picker-options/cache-key or ::picker-options/query-key in attribute " qualified-key))
            props              (comp/props form-instance)
            options            (get-in props [::picker-options/options-cache cache-key :options])
            current-selection  (into #{}
                                 (keep (fn [entity]
                                         (when-let [id (get entity target-id-key)]
                                           [target-id-key id])))
                                 (get props qualified-key))
            field-label        (form/field-label env attr)
            invalid?           (validation/invalid-attribute-value? env attr)
            read-only?         (form/read-only? form-instance attr)
            validation-message (when invalid? (validation/validation-error-message env attr))]
        (div :.ui.field {:classes [(when invalid? "error")]}
          (dom/label field-label " " (when invalid? validation-message))
          (div :.ui.middle.aligned.celled.list.big
            {:style {:marginTop "0"}}
            (if (= style :dropdown)
              (ui-wrapped-dropdown
                {:value    current-selection
                 :multiple true
                 :disabled read-only?
                 :options  options
                 :onChange (fn [v] (form/input-changed! env qualified-key v))})
              (map (fn [{:keys [text value]}]
                     (let [checked? (contains? current-selection value)]
                       (div :.item {:key value}
                         (div :.content {}
                           (div :.ui.toggle.checkbox {:style {:marginTop "0"}}
                             (dom/input
                               {:type     "checkbox"
                                :checked  checked?
                                :onChange #(if-not checked?
                                             (form/input-changed! env qualified-key (vec (conj current-selection value)))
                                             (form/input-changed! env qualified-key (vec (disj current-selection value))))})
                             (dom/label text))))))
                options))))))))

(def ui-to-many-picker (comp/factory ToManyPicker {:keyfn :id}))
(let [ui-to-many-picker (comp/factory ToManyPicker {:keyfn (fn [{:keys [attr]}] (::attr/qualified-key attr))})]
  (defn to-many-picker [env attribute]
    (ui-to-many-picker {:env  env
                        :attr attribute})))
