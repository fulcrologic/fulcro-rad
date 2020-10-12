(ns com.fulcrologic.rad.picker-options
  "Utilities to help support entity/enumeration pickers in the UI, along with loading/normalizing/caching the options.

  Pickers are commonly used for things like autocomplete fields and (cascading) dropdowns where the list of options must be loaded dynamically
  due to some event in the UI.

  Picker options are placed in a well-known top-level table which contains the original query result, the transformed (usable)
  options, and a timestamp for expiration. UI plugins will query for that table as `[::picker-options/options-cache '_]`
  which will cause the entire picker options cache to be visible in props.

  Picker options can be used in form fields, report controls, etc.  They are usable anywhere you can link their query
  in and trigger a load to ensure the options are properly loaded. Picker options can also be statically
  defined.
  "
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]))

(defmutation transform-options
  "INTERNAL MUTATION. Do not use."
  [{:keys  [ref]
    ::keys [pick-one]}]
  (action [{:keys [state] :as env}]
    (let [{:options/keys [subquery]} pick-one
          result    (:ui/query-result
                      (fdn/db->tree [{:ui/query-result (comp/get-query subquery)}]
                        (get-in @state ref) @state))
          transform (get pick-one :options/transform)
          options   (if transform
                      (mapv transform result)
                      result)]
      (swap! state assoc-in (conj ref :ui/options) options))))

(defn load-picker-options!
  "Load picker options based on raw picker options. This function does all of the picker-centric inner workings
   of caching and such, so calling this function may do little more than link your component to an existing cached
   value of options.

   * `app-ish` - An application or live component, used for pulling state and submitting loads.
   * `component-class` - The component class that will be passed through to the various picker option lambdas.
   Usually a form or report class, but could also be a UI control.
   * `props` - The UI props of that will be passed through to the various picker option lambdas. Usually the props
   of the instance that contains the options.
   * `picker-options` - A map that contains ::picker-options options. Often the component options, but could also be
   declared in a control's definition map.
   * `load-options` - Additional options you wish to pass to the load (optional). Only used if the cache was missing or
   stale for the options. Currently you cannot use `:target`, `:params`, or `:post-action`. `::picker-options/remote` can
   be used to select the load remote.

   This function can has general purpose application, and is not report or form specific. It should be called as part of
   a component's lifecycle to link/populate the options. For example in `componentDidMount` or in a `useEffect` that does
   has an empty dependency vector (one-shot on mount).
   "
  ([app-ish component-class props picker-options] (load-picker-options! app-ish component-class props picker-options {}))
  ([app-ish component-class props picker-options load-options]
   (let [{::app/keys [state-atom] :as app} (comp/any->app app-ish)
         {::keys [remote query-key query-component cache-key options-xform cache-time-ms query-parameters]} picker-options
         params        (or (?! query-parameters app component-class props) {})
         cache-time-ms (or cache-time-ms 100)
         state-map     @state-atom
         cache-key     (or (?! cache-key component-class props) query-key)
         time-path     [::options-cache cache-key :cached-at]
         target-path   [::options-cache cache-key :query-result]
         options-path  [::options-cache cache-key :options]
         now           (inst-ms (dt/now))
         cached-at     (get-in state-map time-path 0)
         reload?       (> (- now cached-at) cache-time-ms)]
     (when-not query-key
       (log/error "Cannot load picker options because there is no query-key."))
     (when-not query-component
       (log/warn "Cannot load picker options because ::query-component is missing."))
     (swap! state-atom assoc-in time-path now)
     (when reload?
       (df/load! app-ish query-key query-component
         (cond-> (merge load-options
                   {:target      target-path
                    :params      params
                    :post-action (fn [{:keys [state result]}]
                                   (let [query-result (get-in @state target-path)
                                         raw-result   (get-in result [:body query-key])
                                         options      (vec
                                                        (cond-> query-result
                                                          options-xform (options-xform raw-result)))]
                                     (fns/swap!-> state
                                       (assoc-in options-path options))))})
           remote (assoc :remote remote)))))))

(defn load-options!
  "Load picker options into the options cache for a form field. This should be used by the UI implementation of
  a form field to ensure picker options are loaded. Typically you'll call this in a component lifecycle method (or one-shot
  effect hook).

  * `app-ish` - Anything you can `transact!` against
  * `form-class` - The form that holds the field for which you are loading options (passed to picker option lambdas)
  * `props` - The ui props of the form (passed to the picker option lambdas)
  * `attribute` - A RAD attribute that could contain picker options, and also designates data that the field will
  be picking.
  * `load-options` - Additional options to pass to load if the options are loaded. Some options may be overridden
  internally. See `load-picker-options!`.

  The picker options will be a combination of the field options for the field and any found on the attribute."
  ([app-ish form-class props attribute] (load-options! app-ish form-class props attribute {}))
  ([app-ish form-class props {:com.fulcrologic.rad.attributes/keys [qualified-key] :as attribute} load-options]
   (let [{:com.fulcrologic.rad.form/keys [field-options]} (comp/component-options form-class)
         field-options  (get field-options qualified-key)
         picker-options (merge attribute field-options)]
     (load-picker-options! app-ish form-class props picker-options load-options))))

(defn current-picker-options
  "Gets the current picker options (when not using a form). See `current-form-options` for a form-specific variant.

  `component-instance` - A mounted component whose props will contain the picker options table (e.g. the link query
  `[::picker-options/options-cache '_]`). This instance can also have picker options in its `component-options`.
  `picker-options` - Optional. A map that contains picker option (overrides). Will be merged (as overrides) for any that are found in
  component options on the component instance. Note that since this function does no loading, this is only useful if your
  original picker options are not on the same component as the query.
  "
  ([component-instance] (current-picker-options component-instance {}))
  ([component-instance picker-options]
   (let [coptions       (comp/component-options component-instance)
         picker-options (merge coptions picker-options)
         props          (comp/props component-instance)
         {::keys [cache-key query-key]} picker-options
         cls            (comp/react-type component-instance)
         cache-key      (or (?! cache-key cls props) query-key)
         options        (get-in props [::options-cache cache-key :options])]
     (when (and #?(:cljs goog.DEBUG :clj true)
             (not cache-key))
       (log/error "Could not find picker option for cache or query key in options: " picker-options))
     (when (and #?(:cljs goog.DEBUG :clj true)
             (not (contains? props ::options-cache)))
       (log/warn "No options cache found in props for" (comp/component-name cls) ". This could mean options have not been "
         "loaded, or that you forgot to put `[::picker-options/options-cache '_]` on your query. NOTE: This warning can be "
         "a false alarm if the application just started and no picker options have yet been loaded."))
     options)))

(defn current-form-options
  "Gets the current picker options for the given form-instance and attribute."
  [form-instance attr]
  (let [{:com.fulcrologic.rad.form/keys [field-options]} (comp/component-options form-instance)
        {:com.fulcrologic.rad.attributes/keys [qualified-key]} attr
        field-options (get field-options qualified-key)
        {::keys [cache-key query-key]} (merge attr field-options)
        cache-key     (or (?! cache-key (comp/react-type form-instance) (comp/props form-instance)) query-key)
        cache-key     (or cache-key query-key (log/error "Ref field MUST have either a ::picker-options/cache-key or ::picker-options/query-key in attribute " qualified-key))
        props         (comp/props form-instance)
        options       (get-in props [::options-cache cache-key :options])]
    options))

(def current-options "DEPRECATED NAME: Use current-form-options" current-form-options)

(defn current-to-one-value
  "Returns the current to-one ref value for the given reference attribute as an ident."
  [form-instance attr]
  (let [{:com.fulcrologic.rad.form/keys [attributes]} (comp/component-options form-instance)
        {::attr/keys [qualified-key]} attr
        target-id-key (first (keep (fn [{k ::attr/qualified-key ::attr/keys [target]}]
                                     (when (= k qualified-key) target)) attributes))
        props         (comp/props form-instance)
        id            (get-in props [qualified-key target-id-key])]
    (when id
      [target-id-key id])))

(defn current-to-one-label
  "Extracts the current to-one label from the given attribute's value via the picker options"
  [form-instance attr]
  (let [options (current-form-options form-instance attr)
        value   (current-to-one-value form-instance attr)
        {:keys [text]} (first (filter #(= (:value %) value) options))]
    (str (or text (tr "UNSELECTED")))))

(def remote
  "The keyword name of the remote that the picker options are loaded from. Defaults to :remote."
  ::remote)

(def query-key
  "The top-level query keyword to use when pulling the options."
  ::query-key)

(def query-component
  "A Fulcro defsc with the subquery to use for pulling options."
  ::query-component)

(def query-parameters
  "A map of query parameters to include in the option load, or a `(fn [app cls props] map?)` that can generate those
   props on demand."
  ::query-parameters)

(def cache-key
  "A keyword or `(fn [cls props] keyword?)` under which the normalized picker options will be saved."
  ::cache-key)

(def options-xform
  "A `(fn [normalized-result raw-result] [{:text t :value v} ...])`. This generates the options to show the user. If
  not provided then it is assumed that the query result itself is a vector of these text/value pair maps."
  ::options-xform)

(def cache-time-ms
  "How long the options can be cached. This allows multiple uses of the same options load to be used over time. Caching
  is done under ::cache-key."
  ::cache-time-ms)

