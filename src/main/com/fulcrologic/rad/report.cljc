(ns com.fulcrologic.rad.report
  "Support for generated reports. Report rendering is pluggable, so reports can be quite varied. The general
  definition of a report is a component that loads data and displays it, possibly paginates, sorts and
  filters it, but for which interactions are done via custom mutations (disable, delete, sort) or reloads.

  Reports can customize their layout via plugins, and the layout can then allow futher nested customization of element
  render. For example, it is trivial to create a layout renderer that is some kind of graph, and then use loaded data
  as the input for that display.

  Customizing the report's state machine and possibly wrapping it with more complex layout controls makes it possible
  to create UI dashboards and much more complex application features.
  "
  #?(:cljs (:require-macros com.fulcrologic.rad.report))
  (:require
    #?@(:clj
        [[clojure.pprint :refer [pprint]]
         [cljs.analyzer :as ana]])
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    [edn-query-language.core :as eql]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.rad.routing.history :as history]
    [taoensso.encore :as enc]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-layout [report-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        layout-style (or (some-> report-instance comp/component-options ::layout-style) :default)
        layout       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::style->layout layout-style)]
    (if layout
      (layout report-instance)
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

(defn render-row [report-instance row-class row-props]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        layout-style (or (some-> report-instance comp/component-options ::row-style) :default)
        render       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::row-style->row-layout layout-style)]
    (if render
      (render report-instance row-class row-props)
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

(defn control-renderer
  "Get the report controls renderer for the given report instance."
  [report-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        control-style (or (some-> report-instance comp/component-options ::control-style) :default)
        control       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::control-style->control control-style)]
    (if control
      control
      (do
        (log/error "No layout function found for form layout style" control-style)
        nil))))

(defn render-control
  "Render the control defined by `control-key` in the ::report/controls option. The control definition in question will be
   a `(fn [props])` where `props` is a map containing:

   * `:report-instance` - The React instance of the mounted report
   * `:control-key` - The name of the control key being rendererd (so the control can look up additional options on the component)
   "
  [report-instance control-key]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        {::keys [controls]} (comp/component-options report-instance)
        input-type   (get-in controls [control-key :type])
        input-style  (get-in controls [control-key :style] :default)
        style->input (some-> runtime-atom deref ::rad/controls ::control-type->style->input (get input-type))
        input        (or (get style->input input-style) (get style->input :default))]
    (if input
      (input {:report-instance report-instance
              :control-key     control-key})
      (do
        (log/error "No renderer installed to support parameter " control-key "with type/style" input-type input-style)
        nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def global-events {})

(defn exit-report [{::uism/keys [fulcro-app] :as env}]
  (let [Report (uism/actor-class env :actor/report)]
    (uism/exit env)))

(defn report-options
  "Returns the report options from the current report actor."
  [uism-env & k-or-ks]
  (apply comp/component-options (uism/actor-class uism-env :actor/report) k-or-ks))

(defn initialize-parameters [{::uism/keys [fulcro-app] :as env}]
  (let [report-ident        (uism/actor->ident env :actor/report)
        path                (conj report-ident :ui/parameters)
        {history-params :params} (history/current-route fulcro-app)
        controls            (report-options env ::controls)
        initial-sort-params (or (report-options env ::initial-sort-params) {})
        initial-parameters  (reduce-kv
                              (fn [result control-key {:keys [default-value]}]
                                (if default-value
                                  (assoc result control-key (?! default-value fulcro-app))
                                  result))
                              {::sort initial-sort-params}
                              controls)]
    (cond-> env
      report-ident (uism/apply-action assoc-in path (merge initial-parameters history-params)))))

(defn load-report! [{::uism/keys [state-map event-data] :as env}]
  (let [Report         (uism/actor-class env :actor/report)
        report-ident   (uism/actor->ident env :actor/report)
        {::keys [BodyItem source-attribute]} (comp/component-options Report)
        current-params (dissoc (uism/alias-value env :parameters) ::sort)
        path           (conj (comp/get-ident Report {}) :ui/loaded-data)]
    (log/debug "Loading report" source-attribute (comp/component-name Report) (comp/component-name BodyItem))
    (-> env
      (uism/load source-attribute BodyItem {:params            current-params
                                            ::uism/ok-event    :event/loaded
                                            ::uism/error-event :event/failed
                                            :marker            report-ident
                                            :target            path})
      (uism/activate :state/loading))))

(defn- filter-rows
  "Generates filtered rows, which is an intermediate cached value (not displayed)"
  [{::uism/keys [state-map] :as uism-env}]
  (let [all-rows      (uism/alias-value uism-env :raw-rows)
        row-visible?  (report-options uism-env ::row-visible?)
        normalized?   (some-> all-rows (first) (eql/ident?))
        filtered-rows (if row-visible?
                        (let [parameters (uism/alias-value uism-env :parameters)]
                          (filterv
                            (fn [row]
                              (let [row (if normalized? (get-in state-map row) row)]
                                (row-visible? parameters row)))
                            all-rows))
                        all-rows)]
    (uism/assoc-aliased uism-env :filtered-rows filtered-rows)))

(defn- sort-rows
  "Sorts the filtered rows. Input is the cached intermediate filtered rows, output is cached sorted rows (not visible)"
  [{::uism/keys [state-map] :as uism-env}]
  (let [all-rows     (uism/alias-value uism-env :filtered-rows)
        compare-rows (report-options uism-env ::compare-rows)
        normalized?  (some-> all-rows (first) (eql/ident?))
        sorted-rows  (if compare-rows
                       (let [sort-params (uism/alias-value uism-env :sort-params)
                             keyfn       (if normalized? #(get-in state-map %) identity)
                             comparefn   (fn [a b] (compare-rows sort-params a b))]
                         (vec (sort-by keyfn comparefn all-rows)))
                       all-rows)]
    (uism/assoc-aliased uism-env :sorted-rows sorted-rows)))

(defn- populate-current-page [{::uism/keys [state-map] :as uism-env}]
  (if (report-options uism-env ::paginate?)
    (let [current-page   (max 1 (uism/alias-value uism-env :current-page))
          page-size      (or (report-options uism-env ::page-size) 20)
          available-rows (or (uism/alias-value uism-env :sorted-rows) [])
          n              (count available-rows)
          stragglers?    (pos? (rem n page-size))
          pages          (cond-> (int (/ n page-size))
                           stragglers? inc)
          page-start     (* (dec current-page) page-size)
          rows           (cond
                           (= pages current-page) (subvec available-rows page-start n)
                           (> n page-size) (subvec available-rows page-start (+ page-start page-size))
                           :else available-rows)]
      (if (and (not= 1 current-page) (empty? rows))
        (-> uism-env
          (uism/assoc-aliased :current-page 1)
          (populate-current-page))
        (uism/assoc-aliased uism-env :current-rows rows :page-count pages)))
    (uism/assoc-aliased uism-env
      :page-count 1
      :current-rows (uism/alias-value uism-env :sorted-rows))))

(defstatemachine report-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {:parameters    [:actor/report :ui/parameters]
    :sort-params   [:actor/report :ui/parameters ::sort]
    :sort-by       [:actor/report :ui/parameters ::sort :sort-by]
    :forward?      [:actor/report :ui/parameters ::sort :forward?]
    :filtered-rows [:actor/report :ui/cache :filtered-rows]
    :sorted-rows   [:actor/report :ui/cache :sorted-rows]
    :raw-rows      [:actor/report :ui/loaded-data]
    :current-rows  [:actor/report :ui/current-rows]
    :current-page  [:actor/report :ui/current-page]
    :page-count    [:actor/report :ui/page-count]
    :busy?         [:actor/report :ui/busy?]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [event-data]} env
                            {::keys [run-on-mount?]} (report-options env)]
                        (-> env
                          (uism/store :route-params (:route-params event-data))
                          (uism/assoc-aliased :current-page 1)
                          (initialize-parameters)
                          (cond->
                            run-on-mount? (load-report!)
                            (not run-on-mount?) (uism/activate :state/gathering-parameters)))))}

    :state/loading
    (merge global-events
      {::uism/events
       {:event/loaded {::uism/handler (fn [env]
                                        (-> env
                                          (filter-rows)
                                          (sort-rows)
                                          (populate-current-page)
                                          (uism/activate :state/gathering-parameters)))}
        :event/failed {::uism/handler (fn [env] (log/error "Report failed to load.")
                                        ;; TASK: need global error reporting
                                        (uism/activate env :state/gathering-parameters))}}})

    :state/gathering-parameters
    (merge global-events
      {::uism/events
       {:event/goto-page         {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (let [{:keys [page]} event-data]
                                                     (-> env
                                                       (uism/assoc-aliased :current-page (max 1 page))
                                                       (populate-current-page))))}
        :event/next-page         {::uism/handler (fn [env]
                                                   (let [page (uism/alias-value env :current-page)]
                                                     (-> env
                                                       (uism/assoc-aliased :current-page (inc (max 1 page)))
                                                       (populate-current-page))))}

        :event/prior-page        {::uism/handler (fn [env]
                                                   (let [page (uism/alias-value env :current-page)]
                                                     (-> env
                                                       (uism/assoc-aliased :current-page (dec (max 2 page)))
                                                       (populate-current-page))))}

        :event/do-sort           {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (if-let [{::attr/keys [qualified-key]} (get event-data ::attr/attribute)]
                                                     (let [sort-by  (uism/alias-value env :sort-by)
                                                           forward? (uism/alias-value env :forward?)
                                                           forward? (if (= qualified-key sort-by)
                                                                      (not forward?)
                                                                      true)]
                                                       (-> env
                                                         (uism/assoc-aliased
                                                           :busy? false
                                                           :sort-by qualified-key
                                                           :forward? forward?)
                                                         (sort-rows)
                                                         (populate-current-page)))
                                                     env))}

        :event/sort              {::uism/handler (fn [{::uism/keys [fulcro-app event-data] :as env}]
                                                   ;; this ensures that the do sort doesn't get the CPU until the busy state is rendered
                                                   (uism/trigger! fulcro-app (uism/asm-id env) :event/do-sort event-data)
                                                   (uism/assoc-aliased env :busy? true))}

        :event/do-filter         {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (-> env
                                                     (uism/assoc-aliased :current-page 1 :busy? false)
                                                     (filter-rows)
                                                     (sort-rows)
                                                     (populate-current-page)))}

        :event/filter            {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}]
                                                   ;; this ensures that the do sort doesn't get the CPU until the busy state is rendered
                                                   (uism/trigger! fulcro-app (uism/asm-id env) :event/do-filter)
                                                   (uism/assoc-aliased env :busy? true))}

        :event/parameter-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   ;; NOTE: value at this layer is ALWAYS typed to the attribute.
                                                   ;; The rendering layer is responsible for converting the value to/from
                                                   ;; the representation needed by the UI component (e.g. string)
                                                   (let [{:keys [parameter value]} event-data
                                                         form-ident (uism/actor->ident env :actor/report)
                                                         path       (when (and form-ident parameter)
                                                                      (conj form-ident parameter))]
                                                     (when-not path
                                                       (log/error "Unable to record attribute change. Path cannot be calculated."))
                                                     (cond-> env
                                                       path (uism/apply-action assoc-in path value))))}
        :event/run               {::uism/handler load-report!}}})}})

(defn run-report!
  "Run a report with the current parameters"
  [this]
  (uism/trigger! this (comp/get-ident this) :event/run))

#?(:clj
   (defn req!
     ([env sym options k pred?]
      (when-not (and (contains? options k) (pred? (get options k)))
        (throw (ana/error env (str "defsc-report " sym " is missing or invalid option " k)))))
     ([env sym options k]
      (when-not (contains? options k)
        (throw (ana/error env (str "defsc-report " sym " is missing option " k)))))))

(defn start-report!
  "Start a report. Not normally needed, since a report is started when it is routed to; however, if you put
  a report on-screen initially (or don't use dynamic router), then you must call this to start your report."
  ([app report-class]
   (start-report! app report-class {}))
  ([app report-class options]
   (let [machine-def (or (comp/component-options report-class ::machine) report-machine)]
     (uism/begin! app report-machine (comp/ident report-class {}) {:actor/report report-class} options))))

(defn report-will-enter [app route-params report-class]
  (let [report-ident (comp/get-ident report-class {})]
    (dr/route-deferred report-ident
      (fn []
        (start-report! app report-class {:route-params route-params})
        (comp/transact! app [(dr/target-ready {:target report-ident})])))))

#?(:clj
   (defmacro defsc-report
     "Define a report. Just like defsc, but you do not specify query/ident/etc.

     Instead:

     ::report/BodyItem FulcroClass?
     ::report/columns (every? attribute? :kind vector?)
     ::report/column-key attribute?
     ::report/source-attribute keyword?
     ::report/route string?
     ::report/parameters (map-of ui-keyword? rad-data-type?)

     NOTE: Parameters MUST have a `ui` namespace, like `:ui/show-inactive?`.

     If you elide the body, one will be generated for you.
     "
     [sym arglist & args]
     (let [this-sym (first arglist)
           options  (first args)]
       (req! &env sym options ::columns #(every? symbol? %))
       (req! &env sym options ::row-pk #(symbol? %))
       (req! &env sym options ::source-attribute keyword?)
       (let
         [generated-row-sym (symbol (str (name sym) "-Row"))
          options           (opts/macro-optimize-options &env options #{::field-formatters ::column-headings ::form-links} {})
          {::keys [BodyItem edit-form columns row-pk form-links
                   row-query-inclusion denormalize?
                   row-actions route] :as options} options
          _                 (when edit-form (throw (ana/error &env "::edit-form is no longer supported. Use ::form-links instead.")))
          ItemClass         (or BodyItem generated-row-sym)
          subquery          `(comp/get-query ~ItemClass)
          query             [:ui/parameters
                             :ui/cache
                             :ui/busy?
                             :ui/page-count
                             :ui/current-page
                             {:ui/current-rows subquery}
                             [df/marker-table '(quote _)]]
          nspc              (if (enc/compiling-cljs?) (-> &env :ns :name str) (name (ns-name *ns*)))
          fqkw              (keyword (str nspc) (name sym))
          options           (assoc options
                              :route-segment (if (vector? route) route [route])
                              :will-enter `(fn [app# route-params#] (report-will-enter app# route-params# ~sym))
                              ::BodyItem ItemClass
                              :query query
                              :initial-state {:ui/parameters   {}
                                              :ui/cache        {}
                                              :ui/busy?        false
                                              :ui/current-page 1
                                              :ui/page-count   1
                                              :ui/current-rows []}
                              :ident (list 'fn [] [::id fqkw]))
          body              (if (seq (rest args))
                              (rest args)
                              [`(render-layout ~this-sym)])
          row-query         (list 'fn [] `(let [forms#    ~(::form-links options)
                                                id-attrs# (keep #(comp/component-options % ::form/id) (vals forms#))]
                                            (vec
                                              (into #{~@row-query-inclusion}
                                                (map ::attr/qualified-key (conj (set (concat id-attrs# ~columns)) ~row-pk))))))
          props-sym         (gensym "props")
          row-ident         (list 'fn []
                              `(let [k# (::attr/qualified-key ~row-pk)]
                                 [k# (get ~props-sym k#)]))
          row-actions       (or row-actions [])
          body-options      (cond-> {:query        row-query
                                     ::row-actions row-actions
                                     ::columns     columns}
                              (not denormalize?) (assoc :ident row-ident)
                              form-links (assoc ::form-links form-links))
          defs              (if-not BodyItem
                              [`(comp/defsc ~generated-row-sym [this# ~props-sym computed#]
                                  ~body-options
                                  (render-row (:report-instance computed#) ~generated-row-sym ~props-sym))
                               `(comp/defsc ~sym ~arglist ~options ~@body)]
                              [`(comp/defsc ~sym ~arglist ~options ~@body)])]
         `(do
            ~@defs)))))

(def reload!
  "[report-instance]

   Reload the report."
  (debounce
    (fn [report-instance]
      (uism/trigger! report-instance (comp/get-ident report-instance) :event/run))
    100))

(m/defmutation merge-params
  "Mutation: Merges the given params (a map) into the current report instance's ui parameters."
  [params]
  (action [{:keys [state ref]}]
    (let [path (conj ref :ui/parameters)]
      (swap! state update-in path merge params))))

(defn set-parameter!
  "Set the given parameter on the report, possibly triggering an auto-refresh."
  [report-instance parameter-name new-value]
  (let [reload? (comp/component-options report-instance ::run-on-parameter-change?)]
    (comp/transact! report-instance [(merge-params {parameter-name new-value})])
    (rad-routing/update-route-params! report-instance assoc parameter-name new-value)
    (when reload?
      (reload! report-instance))))

(defn form-link
  "Get the form link info for a given (column) key.

  Returns nil if there is no link info, otherwise returns:

  ```
  {:edit-form FormClass
   :entity-id id-of-entity-to-edit}
  ```
  "
  [report-instance row-props column-key]
  (let [{::keys [form-links]} (comp/component-options report-instance)
        cls    (get form-links column-key)
        id-key (some-> cls (comp/component-options ::form/id ::attr/qualified-key))]
    (when cls
      {:edit-form cls
       :entity-id (get row-props id-key)})))

(defn link
  "Get a regular lambda link for a given (column) key.

  Returns nil if there is no link info, otherwise returns:

  ```
  {:edit-form FormClass
   :entity-id id-of-entity-to-edit}
  ```
  "
  [report-instance row-props column-key]
  (let [{::keys [form-links]} (comp/component-options report-instance)
        cls    (get form-links column-key)
        id-key (some-> cls (comp/component-options ::form/id ::attr/qualified-key))]
    (when cls
      {:edit-form cls
       :entity-id (get row-props id-key)})))

;; TASK: More default type formatters
(defn inst->human-readable-date
  "Converts a UTC Instant into the correctly-offset and human-readable (e.g. America/Los_Angeles) date string."
  ([inst]
   #?(:cljs
      (when (inst? inst)
        (.toLocaleDateString ^js inst js/undefined #js {:weekday "short" :year "numeric" :month "short" :day "numeric"})))))

(defn format-column [v]
  (cond
    (string? v) v
    (inst? v) (str (inst->human-readable-date v))
    (math/numeric? v) (math/numeric->str v)
    :else (str v)))

(defn formatted-column-value
  "Given a report instance, a row of props, and a column attribute for that report:
   returns the formatted value of that column using the field formatter(s) defined
   on the column attribute or report. If no formatter is provided a default formatter
   will be used."
  [report-instance row-props {::keys      [field-formatter]
                              ::attr/keys [qualified-key] :as column-attribute}]
  (let [value                  (get row-props qualified-key)
        report-field-formatter (comp/component-options report-instance ::field-formatters qualified-key)
        formatted-value        (or
                                 (?! report-field-formatter report-instance value)
                                 (?! field-formatter report-instance value)
                                 (format-column value)
                                 (str value))]
    formatted-value))

(defn current-rows
  "Get a vector of the current rows that should be shown by the renderer (sorted/paginated/filtered). `report-instance`
   is available in the rendering `env`."
  [report-instance]
  (let [props (comp/props report-instance)]
    (get props :ui/current-rows [])))

(defn loading?
  "Returns true if the given report instance has an active network load in progress."
  [report-instance]
  (when report-instance
    (df/loading? (get-in (comp/props report-instance) [df/marker-table (comp/get-ident report-instance)]))))

(defn sort-rows!
  "Sort the report by the given attribute. Changes direction if the report is already sorted by that attribute. The implementation
   of sorting is built-in and uses compare, but you can override how sorting works by defining `::report/sort-rows` on your report."
  [this by-attribute]
  (uism/trigger! this (comp/get-ident this) :event/sort {::attr/attribute by-attribute}))

(defn filter-rows!
  "Update the filtered rows based on current report parameters."
  [this]
  (uism/trigger! this (comp/get-ident this) :event/filter))

(defn goto-page!
  "Move to the next page (if there is one)"
  [this page-number]
  (uism/trigger! this (comp/get-ident this) :event/goto-page {:page page-number}))

(defn next-page!
  "Move to the next page (if there is one)"
  [this]
  (uism/trigger! this (comp/get-ident this) :event/next-page))

(defn prior-page!
  "Move to the next page (if there is one)"
  [this]
  (uism/trigger! this (comp/get-ident this) :event/prior-page))
