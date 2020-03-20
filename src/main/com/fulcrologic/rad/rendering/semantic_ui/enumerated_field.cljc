(ns com.fulcrologic.rad.rendering.semantic-ui.enumerated-field
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [com.fulcrologic.rad.ids :as ids]
    [com.fulcrologic.rad.ui-validation :as validation]
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mroot]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]))

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
          options)))))

(defn- render-to-one [{::form/keys [form-instance] :as env} {::form/keys [field-label]
                                                             ::attr/keys [qualified-key] :as attribute}]
  (let [props      (comp/props form-instance)
        read-only? (form/read-only? form-instance attribute)
        invalid?   (validation/invalid-attribute-value? env attribute)
        user-props (form/field-style-config env attribute :input/props)
        options    (enumerated-options env attribute)
        value      (get props qualified-key)]
    (div :.ui.field {:key (str qualified-key) :classes [(when invalid? "error")]}
      (label (str (or field-label (some-> qualified-key name str/capitalize))
               (when invalid? " (Required)")))
      (ui-wrapped-dropdown (merge
                             {:disabled read-only?
                              :options  options
                              :value    value
                              :onChange (fn [v]
                                          (form/input-changed! env qualified-key v))}
                             user-props)))))

(defn render-field [env {::attr/keys [cardinality] :or {cardinality :one} :as attribute}]
  (if (= :many cardinality)
    (render-to-many env attribute)
    (render-to-one env attribute)))

(defsc AutocompleteField [this {:ui/keys [search-string options] :as props}]
  {:query [::autocomplete-id :ui/search-string :ui/options]
   :ident ::autocomplete-id}
  (dom/div))

(def ui-autocomplete-field (comp/factory AutocompleteField {:keyfn :id}))

(defmutation gc-autocomplete [{:keys [id]}]
  (action [{:keys [state]}]
    (when id
      (swap! state fns/remove-entity [::autocomplete-id id]))))

(defsc AutocompleteFieldRoot [this props {:keys [env attribute]}]
  {:initLocalState        (fn [this] {:field-id (ids/new-uuid)})
   :componentDidMount     (fn [this]
                            (let [id (comp/get-state this :field-id)]
                              (merge/merge-component! this AutocompleteField {::autocomplete-id id
                                                                              :ui/search-string ""
                                                                              :ui/options       []}))
                            (mroot/register-root! this {:initialize? false}))
   :shouldComponentUpdate (fn [_ _] true)
   :componentWillUnmount  (fn [this]
                            (comp/transact! this [(gc-autocomplete {:id (comp/get-state this :field-id)})])
                            (mroot/deregister-root! this))
   :query                 [::autocomplete-id]}
  (let [{::attr/keys [qualified-key]} attribute]
    (log/info "autocomplete " qualified-key)
    (dom/div "TODO")))

(def ui-autocomplete-field-root (mroot/floating-root-factory AutocompleteFieldRoot
                                  {:keyfn (fn [props] (-> props :attribute ::attr/qualified-key))}))

(defn render-autocomplete-field [env {::attr/keys [cardinality] :or {cardinality :one} :as attribute}]
  (if (= :many cardinality)
    (log/error "Cannot autocomplete to-many attributes with renderer" `render-autocomplete-field)
    (ui-autocomplete-field-root {:env env :attribute attribute})))

