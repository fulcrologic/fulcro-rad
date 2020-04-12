(ns com.fulcrologic.rad.rendering.semantic-ui.enumerated-field
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [cljs.reader :refer [read-string]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [com.fulcrologic.rad.ui-validation :as validation]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]))

(defn enumerated-options [{::form/keys [form-instance] :as env} {::attr/keys [qualified-key] :as attribute}]
  (let [{::attr/keys [enumerated-values]} attribute
        enumeration-labels (merge
                             (::attr/enumerated-labels attribute)
                             (comp/component-options form-instance ::form/enumerated-labels qualified-key))]
    ;; TODO: Sorting should be something users control
    (sort-by :text
      (mapv (fn [k]
              {:text  (get enumeration-labels k (name k))
               :value k}) enumerated-values))))

(defn- render-to-many [{::form/keys [form-instance] :as env} {::form/keys [field-label]
                                                              ::attr/keys [qualified-key] :as attribute}]
  (when (form/field-visible? form-instance attribute)
    (let [props        (comp/props form-instance)
          read-only?   (form/read-only? form-instance attribute)
          options      (enumerated-options env attribute)
          selected-ids (get props qualified-key #{})]
      (div :.ui.field {:key (str qualified-key)}
        (label (or field-label (some-> qualified-key name str/capitalize)))
        (div :.ui.middle.aligned.celled.list.big {:style {:marginTop "0"}}
          (map (fn [{:keys [text value]}]
                 (let [checked? (contains? selected-ids value)]
                   (div :.item {:key value}
                     (div :.content {}
                       (div :.ui.toggle.checkbox {:style {:marginTop "0"}}
                         (dom/input
                           {:type     "checkbox"
                            :checked  checked?
                            :disabled read-only?
                            :onChange #(let [selection (if-not checked?
                                                         (conj (set (or selected-ids #{})) value)
                                                         (disj selected-ids value))]
                                         (form/input-changed! env qualified-key selection))})
                         (dom/label text))))))
            options))))))

(defn- render-to-one [{::form/keys [form-instance] :as env} {::form/keys [field-label]
                                                             ::attr/keys [qualified-key] :as attribute}]
  (when (form/field-visible? form-instance attribute)
    (let [props      (comp/props form-instance)
          read-only? (form/read-only? form-instance attribute)
          invalid?   (validation/invalid-attribute-value? env attribute)
          user-props (form/field-style-config env attribute :input/props)
          options    (enumerated-options env attribute)
          value      (get props qualified-key)]
      (div :.ui.field {:key (str qualified-key) :classes [(when invalid? "error")]}
        (label (str (or field-label (some-> qualified-key name str/capitalize))
                 (when invalid? " (Required)")))
        (if read-only?
          (let [value (first (filter #(= value (:value %)) options))]
            (:text value))
          (ui-wrapped-dropdown (merge
                                 {:disabled read-only?
                                  :options  options
                                  :value    value
                                  :onChange (fn [v] (form/input-changed! env qualified-key v))}
                                 user-props)))))))

(defn render-field [env {::attr/keys [cardinality] :or {cardinality :one} :as attribute}]
  (if (= :many cardinality)
    (render-to-many env attribute)
    (render-to-one env attribute)))
