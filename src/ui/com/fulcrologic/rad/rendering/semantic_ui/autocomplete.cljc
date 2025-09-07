(ns com.fulcrologic.rad.rendering.semantic-ui.autocomplete
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [goog.object :as gobj]
         [cljs.reader :refer [read-string]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.ids :as ids]
    [com.fulcrologic.rad.options-util :as opts]
    [com.fulcrologic.rad.rendering.semantic-ui.form-options :as sufo]
    [taoensso.timbre :as log]))

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
                                                                                    className omit-label?
                                                                                    read-only?]}]
  {:initLocalState    (fn [this]
                        ;; Possible problem???: props not making it...fix that, or debounce isn't configurable.
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
                               :autocomplete/keys [search-key preload?]} (comp/props this)
                              value (comp/get-computed this :value)]
                          (cond
                            preload? (df/load! this search-key AutocompleteQuery
                                       {:post-mutation        `normalize-options
                                        :post-mutation-params {:source search-key
                                                               :target [::autocomplete-id id :ui/options]}})
                            (and search-key value) (df/load! this search-key AutocompleteQuery
                                                     {:params               {:only value}
                                                      :post-mutation        `normalize-options
                                                      :post-mutation-params {:source search-key
                                                                             :target [::autocomplete-id id :ui/options]}}))))
   :initial-state     (fn [{:keys [id search-key debounce-ms minimum-input]}]
                        {::autocomplete-id           id
                         :autocomplete/search-key    search-key
                         :autocomplete/debounce-ms   debounce-ms
                         :autocomplete/minimum-input minimum-input
                         :ui/search-string           ""
                         :ui/options                 #js []})
   :query             [::autocomplete-id :ui/search-string :ui/options :autocomplete/search-key
                       :autocomplete/debounce-ms :autocomplete/minimum-input]
   :ident             ::autocomplete-id}
  (let [load! (comp/get-state this :load!)]
    #?(:clj
       (dom/div "")
       :cljs
       (dom/div :.field {:className (or className "field")
                         :classes   [(when invalid? "error")]}
         (when-not omit-label?
           (dom/label label (when invalid? (dom/span " " validation-message))))
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
                                                                 read-string))))}))
         (when (and invalid? omit-label?)
           (dom/div :.red validation-message))))))

(def ui-autocomplete-field (comp/computed-factory AutocompleteField {:keyfn ::autocomplete-id}))

(defmutation gc-autocomplete [{:keys [id]}]
  (action [{:keys [state]}]
    (when id
      (swap! state fns/remove-entity [::autocomplete-id id]))))

(defsc AutocompleteFieldRoot [this _ {:keys [env attribute]}]
  {:use-hooks?    true
   :initial-state {::autocomplete-id {}}
   :query         [::autocomplete-id]}
  (let [k                  (::attr/qualified-key attribute)
        {::form/keys [form-instance]} env
        top-class          (sufo/top-class form-instance attribute)
        {:autocomplete/keys [search-key debounce-ms minimum-input]} (fo/get-field-options (comp/component-options form-instance) attribute)
        value              (-> (comp/props form-instance) (get k))
        id                 (hooks/use-generated-id)
        label              (form/field-label env attribute)
        read-only?         (form/read-only? form-instance attribute)
        omit-label?        (form/omit-label? form-instance attribute)
        invalid?           (form/invalid-attribute-value? env attribute)
        validation-message (when invalid? (form/validation-error-message env attribute))
        autocomplete-props (hooks/use-component (comp/any->app this) AutocompleteField
                             {:initialize?    true
                              :initial-params
                              {:id            id
                               :search-key    search-key
                               :debounce-ms   debounce-ms
                               :minimum-input minimum-input}
                              :keep-existing? false})]

    (hooks/use-gc this [::autocomplete-id id] #{})

    (ui-autocomplete-field autocomplete-props
      (cond-> {:value              value
               :invalid?           invalid?
               :validation-message validation-message
               :label              label
               :read-only?         read-only?
               :omit-label?        omit-label?
               :onChange           (fn [normalized-value]
                                     #?(:cljs
                                        (when normalized-value (form/input-changed! env k normalized-value))))}
        top-class (assoc :className top-class)))))

(def ui-autocomplete-field-root (comp/computed-factory AutocompleteFieldRoot
                                  {:keyfn (fn [props] (-> props :attribute ::attr/qualified-key))}))

(defn render-autocomplete-field [env {::attr/keys [cardinality] :or {cardinality :one} :as attribute}]
  (if (= :many cardinality)
    (log/error "Cannot autocomplete to-many attributes with renderer" `render-autocomplete-field)
    (ui-autocomplete-field-root {} {:env env :attribute attribute})))

