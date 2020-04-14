(ns com.fulcrologic.rad.rendering.semantic-ui.autocomplete
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [goog.object :as gobj]
         [cljs.reader :refer [read-string]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [com.fulcrologic.rad.ids :as ids]
    [com.fulcrologic.rad.ui-validation :as validation]
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mroot]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.rad.options-util :as opts]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]))

(defsc AutocompleteQuery [_ _] {:query [:text :value]})

(defn to-js [v]
  #?(:clj  v
     :cljs (clj->js v)))

(defmutation normalize-options [{:keys [source target]}]
  (action [{:keys [state]}]
    #?(:clj true
       :cljs
            (let [options            (get @state source)
                  normalized-options (apply array
                                       (map (fn [{:keys [text value]}]
                                              #js {:text text :value (pr-str value)}) options))]
              (fns/swap!-> state
                (dissoc source)
                (assoc-in target normalized-options))))))

(defsc AutocompleteField [this {:ui/keys [search-string options] :as props} {:keys [value label onChange
                                                                                    invalid? validation-message
                                                                                    read-only?]}]
  {:initLocalState    (fn [this]
                        ;; TASK: props not making it...fix that, or debounce isn't configurable.
                        (let [{:autocomplete/keys [debounce-ms]} (comp/props this)]
                          {:load! (opts/debounce
                                    (fn [s]
                                      (let [{id                 ::autocomplete-id
                                             :autocomplete/keys [search-key]} (comp/props this)]
                                        (df/load! this search-key AutocompleteQuery
                                          {:params               {:search-string s}
                                           :post-mutation        `normalize-options
                                           :post-mutation-params {:source search-key
                                                                  :target [::autocomplete-id id :ui/options]}})))
                                    (or debounce-ms 200))}))
   :componentDidMount (fn [this]
                        (let [{id                 ::autocomplete-id
                               :autocomplete/keys [search-key]} (comp/props this)
                              value (comp/get-computed this :value)]
                          (when (and search-key value)
                            (df/load! this search-key AutocompleteQuery
                              {:params               {:only value}
                               :post-mutation        `normalize-options
                               :post-mutation-params {:source search-key
                                                      :target [::autocomplete-id id :ui/options]}}))))
   :query             [::autocomplete-id :ui/search-string :ui/options :autocomplete/search-key
                       :autocomplete/debounce-ms :autocomplete/minimum-input]
   :ident             ::autocomplete-id}
  (let [load! (comp/get-state this :load!)]
    #?(:clj
       (dom/div "")
       :cljs
       (dom/div :.field {:classes [(when invalid? "error")]}
         (dom/label label (when invalid? (str " " validation-message)))
         (if read-only?
           (gobj/getValueByKeys options 0 "text")
           (ui-dropdown #js {:search             true
                             :options            (if options options #js [])
                             :value              (pr-str value)
                             :selection          true
                             :closeOnBlur        true
                             :openOnFocus        true
                             :selectOnBlur       true
                             :selectOnNavigation true
                             :onSearchChange     (fn [_ v]
                                                   (let [query (comp/isoget v "searchQuery")]
                                                     (load! query)))
                             :onChange           (fn [_ v]
                                                   (when onChange
                                                     (onChange (some-> (comp/isoget v "value")
                                                                 read-string))))}))))))

(def ui-autocomplete-field (comp/computed-factory AutocompleteField {:keyfn ::autocomplete-id}))

(defmutation gc-autocomplete [{:keys [id]}]
  (action [{:keys [state]}]
    (when id
      (swap! state fns/remove-entity [::autocomplete-id id]))))

(defsc AutocompleteFieldRoot [this props {:keys [env attribute]}]
  {:initLocalState        (fn [this] {:field-id (ids/new-uuid)})
   :componentDidMount     (fn [this]
                            (let [id (comp/get-state this :field-id)
                                  {:keys [attribute]} (comp/get-computed this)
                                  {:autocomplete/keys [search-key debounce-ms minimum-input]} (::form/field-options attribute)]
                              (merge/merge-component! this AutocompleteField {::autocomplete-id           id
                                                                              :autocomplete/search-key    search-key
                                                                              :autocomplete/debounce-ms   debounce-ms
                                                                              :autocomplete/minimum-input minimum-input
                                                                              :ui/search-string           ""
                                                                              :ui/options                 #js []}))
                            (mroot/register-root! this {:initialize? true}))
   :shouldComponentUpdate (fn [_ _] true)
   :initial-state         {::autocomplete-id {}}
   :componentWillUnmount  (fn [this]
                            (comp/transact! this [(gc-autocomplete {:id (comp/get-state this :field-id)})])
                            (mroot/deregister-root! this))
   :query                 [::autocomplete-id]}
  (let [{:autocomplete/keys [debounce-ms search-key]} (::form/field-options attribute)
        k                  (::attr/qualified-key attribute)
        {::form/keys [form-instance]} env
        value              (-> (comp/props form-instance) (get k))
        id                 (comp/get-state this :field-id)
        label              (form/field-label env attribute)
        read-only?         (form/read-only? form-instance attribute)
        invalid?           (validation/invalid-attribute-value? env attribute)
        validation-message (when invalid? (validation/validation-error-message env attribute))
        field              (get-in props [::autocomplete-id id])]
    ;; Have to pass the id and debounce early since the merge in mount won't happen until after, which is too late for initial
    ;; state
    (ui-autocomplete-field (assoc field
                             ::autocomplete-id id
                             :autocomplete/search-key search-key
                             :autocomplete/debounce-ms debounce-ms)
      {:value              value
       :invalid?           invalid?
       :validation-message validation-message
       :label              label
       :read-only?         read-only?
       :onChange           (fn [normalized-value]
                             #?(:cljs
                                (when normalized-value (form/input-changed! env k normalized-value))))})))

(def ui-autocomplete-field-root (mroot/floating-root-factory AutocompleteFieldRoot
                                  {:keyfn (fn [props] (-> props :attribute ::attr/qualified-key))}))

(defn render-autocomplete-field [env {::attr/keys [cardinality] :or {cardinality :one} :as attribute}]
  (if (= :many cardinality)
    (log/error "Cannot autocomplete to-many attributes with renderer" `render-autocomplete-field)
    (ui-autocomplete-field-root {:env env :attribute attribute})))

