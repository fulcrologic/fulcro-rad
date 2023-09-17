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
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [taoensso.timbre :as log]))

(defmutation transform-options
  "INTERNAL MUTATION. Do not use."
  [{:keys  [ref]
    ::keys [pick-one]}]
  (action [{:keys [state]}]
    (let [{:options/keys [subquery]} pick-one
          result    (:ui/query-result
                      (fdn/db->tree [{:ui/query-result (rc/get-query subquery)}]
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

   This function has general purpose application, and is not report or form specific. It should be called as part of
   a component's lifecycle to link/populate the options. For example in an augmentation of the state machine,
   `componentDidMount`, or in a `useEffect` that has an appropriate dependency list.
   "
  ([app-ish component-class props picker-options] (load-picker-options! app-ish component-class props picker-options {}))
  ([app-ish component-class props picker-options load-options]
   (let [{::app/keys [state-atom] :as app} (rc/any->app app-ish)
         {::keys [remote query-key query query-component cache-key options-xform cache-time-ms query-parameters]} picker-options
         params          (or (?! query-parameters app component-class props) {})
         cache-time-ms   (or cache-time-ms 100)
         state-map       @state-atom
         cache-key       (or (?! cache-key component-class props) query-key)
         time-path       [::options-cache cache-key :cached-at]
         target-path     [::options-cache cache-key :query-result]
         options-path    [::options-cache cache-key :options]
         now             (inst-ms (dt/now))
         cached-at       (get-in state-map time-path 0)
         reload?         (or (:force-reload? load-options) (> (- now cached-at) cache-time-ms))
         query-component (cond
                           (keyword? query-component) (rc/registry-key->class query-component)
                           (comp/component-class? query-component) query-component
                           (vector? query) (rc/nc query))]
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
   (let [field-options  (fo/get-field-options (rc/component-options form-class) attribute)
         picker-options (merge attribute field-options)]
     (load-picker-options! app-ish form-class props picker-options load-options))))

(defn current-picker-options
  "Gets the current picker options (when not using a form). See `current-form-options` for a form-specific variant.

  `component-instance` - A mounted component whose props will contain the picker options table (e.g. the link query
  `[::picker-options/options-cache '_]`). This instance can also have picker options in its `component-options`.
  `picker-options-or-cache-key` - Optional.
    Can be a keyword, in which case the component-instance can be any component instance, and the options
    will be looked up at that cache key in global app state. Note that if the component does not include
    the options cache in its query, then UI refreshes on the requesting component will not trigger if/when the options change.
    If it is a map then it should contain picker option that will be merged (as overrides) into any that are found in
    component options on the `component-instance`. Note that this function does no loading.
  "
  ([component-instance] (current-picker-options component-instance {}))
  ([component-instance picker-options-or-cache-key]
   (if (keyword? picker-options-or-cache-key)
     (let [state-map (app/current-state component-instance)]
       (get-in state-map [::options-cache picker-options-or-cache-key :options]))
     (let [coptions       (rc/component-options component-instance)
           picker-options (merge coptions picker-options-or-cache-key)
           props          (rc/props component-instance)
           {::keys [cache-key query-key]} picker-options
           cls            (comp/react-type component-instance)
           cache-key      (or (?! cache-key cls props) query-key)
           options        (get-in props [::options-cache cache-key :options])]
       (when (and #?(:cljs goog.DEBUG :clj true)
               (not cache-key))
         (log/error "Could not find picker option for cache or query key in options: " picker-options))
       (when (and #?(:cljs goog.DEBUG :clj true)
               (not (contains? props ::options-cache)))
         (log/warn "No options cache found in props for" (rc/component-name cls) ". This could mean options have not been "
           "loaded, or that you forgot to put `[::picker-options/options-cache '_]` on your query. NOTE: This warning can be "
           "a false alarm if the application just started and no picker options have yet been loaded."))
       options))))

(defn current-form-options
  "Gets the current picker options for the given form-instance and attribute."
  [form-instance attr]
  (let [k             (ao/qualified-key attr)
        form-options  (rc/component-options form-instance)
        field-options (fo/get-field-options form-options attr)
        {::keys [cache-key query-key]} (merge attr field-options) ; not desired, but bw compat
        cache-key     (or (?! cache-key (comp/react-type form-instance) (rc/props form-instance)) query-key)
        cache-key     (or cache-key query-key (log/error "Ref field MUST have either a ::picker-options/cache-key or ::picker-options/query-key in attribute " k))
        props         (rc/props form-instance)
        options       (get-in props [::options-cache cache-key :options])]
    options))

(def current-options "DEPRECATED NAME: Use current-form-options" current-form-options)

(defn current-to-one-value
  "Returns the current to-one ref value for the given reference attribute as an ident."
  [form-instance attr]
  (let [{:com.fulcrologic.rad.form/keys [attributes]} (rc/component-options form-instance)
        {::attr/keys [qualified-key]} attr
        target-id-key (first (keep (fn [{k ::attr/qualified-key ::attr/keys [target]}]
                                     (when (= k qualified-key) target)) attributes))
        props         (rc/props form-instance)
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
  "The keyword name of the remote that the picker options are loaded from. Defaults to :remote.

    This goes on `fo/field-options`."
  ::remote)

(def query-key
  "The top-level query keyword to use when pulling the options.

   This goes on `fo/field-options`."
  ::query-key)

(def query-component
  "A Fulcro defsc with the subquery to use for pulling options. If this is a keyword, symbol, or string, then that will be used
   to pull the component from the Fulcro component registry. You must use this OR `po/query`. This one has precedence.

   This goes in `fo/field-options`"
  ::query-component)

(def query
  "An EQL (vector) query to use when pulling the options. Will cause a normalizing component to be created with
   `rc/nc`, which will only normalize data if there is an id field in the query.

   This goes on `fo/field-options`."
  ::query)

(def query-parameters
  "A map of query parameters to include in the option load, or a `(fn [app cls props] map?)` that can generate those
   props on demand.

    This goes on `fo/field-options`."
  ::query-parameters)

(def cache-key
  "A keyword or `(fn [cls props] keyword?)` under which the normalized picker options will be saved.

   This goes on `fo/field-options`."
  ::cache-key)

(def options-xform
  "A `(fn [normalized-result raw-result] [{:text t :value v} ...])`. This generates the options to show the user. If
  not provided then it is assumed that the query result itself is a vector of these text/value pair maps.

    This goes on `fo/field-options`."
  ::options-xform)

(def cache-time-ms
  "How long the options can be cached. This allows multiple uses of the same options load to be used over time. Caching
  is done under ::cache-key.

   This goes on `fo/field-options`."
  ::cache-time-ms)

(def form
  "Picker option (placed on an attribute or within fo/field-options). If present (and the rendering plugin supports it) this option names the class
   of a `defsc-form` that CAN be used to create or edit instances of the type being picked. Including this option
   automatically infers that creation should be allowed (rendering plugins should automatically show a way to trigger
   the creation of a new element).

   The value can be one of: A component class, a component registry key, or a `(fn [form-instance attr-to-pick] component)`.

    This goes on `fo/field-options`."
  ::form)

(def allow-edit?
  "Picker option (e.g. for fo/field-options). Requires po/form. Indicates that editing of a picked item should be possible.
   Requires support from rendering plugin.

   Boolean or `(fn [parent-instance parent-relation] bool)`

   This goes on `fo/field-options`."
  ::allow-edit?)

(def allow-create?
  "Picker option (e.g. for fo/field-options). Requires po/form. Indicates that creating a new picked item should be possible.
   Requires support from rendering plugin.

   Boolean or `(fn [parent-instance parent-relation] bool)`

    This goes on `fo/field-options`."
  ::allow-create?)

(def quick-create
  "Picker option (e.g. for fo/field-options).

   A `(fn [parent-instance parent-relation] entity-with-tempid)` that must return an entity that can be immediately saved to the server
   via `form/save!`. You MUST give the entity a tempid.

   `po/form` is not required and is not used.

   CAN be combined with allow-create?, allow-edit?, but that will enable both buttons for edit/create as well as
   the ability to type a string and quick-add it.

   This goes on `fo/field-options`."
  ::quick-create)
