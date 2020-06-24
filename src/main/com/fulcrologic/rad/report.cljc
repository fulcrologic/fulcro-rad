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
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.control :as control :refer [Control]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.rad.routing.history :as history]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

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
        (log/error "No layout function found for report control style" control-style)
        nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def global-events {})

(defn report-options
  "Returns the report options from the current report actor."
  [uism-env & k-or-ks]
  (apply comp/component-options (uism/actor-class uism-env :actor/report) k-or-ks))

(defn- route-params-path [env control-key]
  (let [report-ident (uism/actor->ident env :actor/report)
        {:keys [local?] :as control} (comp/component-options (uism/actor-class env :actor/report) ::control/controls control-key)
        id           (second report-ident)]
    (if (or local? (nil? control))
      [id control-key]
      [control-key])))

(defn initial-sort-params
  [env]
  (merge {:ascending? true} (report-options env ::initial-sort-params)))

(defn initialize-parameters [{::uism/keys [app event-data] :as env}]
  (let [report-ident       (uism/actor->ident env :actor/report)
        path               (conj report-ident :ui/parameters)
        {:keys  [params]
         ::keys [externally-controlled?]} event-data
        {history-params :params} (history/current-route app)
        sort-path          (route-params-path env ::sort)
        selected-row       (get-in history-params (route-params-path env ::selected-row))
        current-page       (get-in history-params (route-params-path env ::current-page))
        controls           (report-options env :com.fulcrologic.rad.control/controls)
        initial-parameters (cond-> {::sort (initial-sort-params env)}
                             selected-row (assoc ::selected-row selected-row)
                             current-page (assoc ::current-page current-page))]
    (as-> env $
      (uism/apply-action $ assoc-in path (deep-merge initial-parameters {::sort (get-in history-params sort-path {})}))
      (reduce-kv
        (fn [new-env control-key {:keys [local? default-value]}]
          (let [param-path     (route-params-path env control-key)
                state-map      (::uism/state-map new-env)
                explicit-value (or (get-in params param-path) (get params control-key) (get-in history-params param-path))
                default-value  (?! default-value app)
                v              (or explicit-value default-value)]
            (if-not (nil? v)
              (cond
                ;; only the report knows about it, or it came from the route/history
                local? (uism/apply-action new-env assoc-in (conj report-ident :ui/parameters control-key) v)
                ;; Came in on explicit params...force it to the new value
                explicit-value (uism/apply-action new-env assoc-in [::control/id control-key ::control/value] v)
                ;; A container is controlling this report, and it is a global control. Leave it alone
                (and externally-controlled? (get-in state-map [::control/id control-key ::control/value])) new-env
                ;; It's a global control on a standalone report, or it is missing a value.
                :else (uism/apply-action new-env assoc-in [::control/id control-key ::control/value] v))
              new-env)))
        $
        controls))))

(defn- current-control-parameters [{::uism/keys [state-map] :as env}]
  (let [Report       (uism/actor-class env :actor/report)
        report-ident (uism/actor->ident env :actor/report)
        controls     (comp/component-options Report ::control/controls)
        controls     (control/control-map->controls controls)]
    (reduce
      (fn [result {:keys          [local?]
                   ::control/keys [id]}]
        (let [v (if local?
                  (get-in state-map (conj report-ident :ui/parameters id))
                  (get-in state-map [::control/id id ::control/value]))]
          (if (nil? v)
            result
            (assoc result id v))))
      {}
      controls)))


(defn load-report! [env]
  (let [Report         (uism/actor-class env :actor/report)
        report-ident   (uism/actor->ident env :actor/report)
        {::keys [BodyItem source-attribute]} (comp/component-options Report)
        current-params (current-control-parameters env)
        path           (conj report-ident :ui/loaded-data)]
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
                        (let [parameters (current-control-parameters uism-env)]
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

(declare goto-page*)

(defn- page-number-changed [env]
  (let [pg        (uism/alias-value env :current-page)
        row-path  (route-params-path env ::selected-row)
        page-path (route-params-path env ::current-page)]
    (rad-routing/update-route-params! (::uism/app env) (fn [p]
                                                         (-> p
                                                           (assoc-in row-path -1)
                                                           (assoc-in page-path pg)))))
  env)

(defn- populate-current-page [uism-env]
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
        (goto-page* uism-env 1)
        (-> uism-env
          (uism/assoc-aliased :current-rows rows :page-count pages))))
    (-> uism-env
      (uism/assoc-aliased
        :page-count 1
        :current-rows (uism/alias-value uism-env :sorted-rows)))))

(defn- goto-page* [env page]
  (let [pg (uism/alias-value env :current-page)]
    (if (not= pg page)
      (-> env
        (uism/assoc-aliased :current-page (max 1 page) :selected-row -1)
        (populate-current-page)
        (page-number-changed))
      env)))

(defn rotate-result
  "Given a report class that has columns, and a raw result grouped by those columns: returns a vector of rows that
   rotate the grouped result into a normal report shape."
  [report-class grouped-result]
  (when-not (map? grouped-result)
    (log/warn "The incoming result looks like it was normalized. Did you forget `ro/denormalize? true` on your report?"))
  (let [columns  (comp/component-options report-class ::columns)
        ks       (map ::attr/qualified-key columns)
        row-data (map (fn [{::attr/keys [qualified-key]}]
                        (get grouped-result qualified-key [])) columns)]
    (apply mapv (fn [& args] (zipmap ks args)) row-data)))

(defn- preprocess-raw-result
  "Apply the raw result transform, if it is defined."
  [uism-env]
  (let [xform (report-options uism-env ::raw-result-xform)]
    (if xform
      (let [raw-result (uism/alias-value uism-env :raw-rows)
            report     (uism/actor-class uism-env :actor/report)
            new-result (xform report raw-result)]
        (cond-> uism-env
          new-result (uism/assoc-aliased :raw-rows new-result)))
      uism-env)))

(defstatemachine report-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {:parameters    [:actor/report :ui/parameters]
    :sort-params   [:actor/report :ui/parameters ::sort]
    :sort-by       [:actor/report :ui/parameters ::sort :sort-by]
    :ascending?    [:actor/report :ui/parameters ::sort :ascending?]
    :filtered-rows [:actor/report :ui/cache :filtered-rows]
    :sorted-rows   [:actor/report :ui/cache :sorted-rows]
    :raw-rows      [:actor/report :ui/loaded-data]
    :current-rows  [:actor/report :ui/current-rows]
    :current-page  [:actor/report :ui/parameters ::current-page]
    :selected-row  [:actor/report :ui/parameters ::selected-row]
    :page-count    [:actor/report :ui/page-count]
    :busy?         [:actor/report :ui/busy?]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [fulcro-app event-data]} env
                            {::keys [run-on-mount?]} (report-options env)
                            page-path    (route-params-path env ::current-page)
                            desired-page (-> (history/current-route fulcro-app)
                                           :params
                                           (get-in page-path))
                            run-now?     (or desired-page run-on-mount?)]
                        (-> env
                          (uism/store :route-params (:route-params event-data))
                          (cond->
                            (nil? desired-page) (uism/assoc-aliased :current-page 1))
                          (initialize-parameters)
                          (cond->
                            run-now? (load-report!)
                            (not run-now?) (uism/activate :state/gathering-parameters)))))}

    :state/loading
    (merge global-events
      {::uism/events
       {:event/loaded {::uism/handler (fn [{::uism/keys [state-map] :as env}]
                                        (let [table-name (comp/component-options (uism/actor-class env :actor/report) ::row-pk ::attr/qualified-key)]
                                          (-> env
                                            (preprocess-raw-result)
                                            (filter-rows)
                                            (sort-rows)
                                            (populate-current-page)
                                            (uism/store :last-load-time (inst-ms (dt/now)))
                                            (uism/store :raw-items-in-table (count (keys (get state-map table-name))))
                                            (uism/activate :state/gathering-parameters))))}
        :event/failed {::uism/handler (fn [env] (log/error "Report failed to load.")
                                        ;; TASK: need global error reporting
                                        (uism/activate env :state/gathering-parameters))}}})

    :state/gathering-parameters
    (merge global-events
      {::uism/events
       {:event/goto-page         {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (let [{:keys [page]} event-data]
                                                     (goto-page* env page)))}
        :event/next-page         {::uism/handler (fn [env]
                                                   (let [page (uism/alias-value env :current-page)]
                                                     (goto-page* env (inc (max 1 page)))))}

        :event/prior-page        {::uism/handler (fn [env]
                                                   (let [page (uism/alias-value env :current-page)]
                                                     (goto-page* env (dec (max 2 page)))))}

        :event/do-sort           {::uism/handler (fn [{::uism/keys [event-data app] :as env}]
                                                   (if-let [{::attr/keys [qualified-key]} (get event-data ::attr/attribute)]
                                                     (let [sort-by    (uism/alias-value env :sort-by)
                                                           sort-path  (route-params-path env ::sort)
                                                           ascending? (uism/alias-value env :ascending?)
                                                           ascending? (if (= qualified-key sort-by)
                                                                        (not ascending?)
                                                                        true)]
                                                       (rad-routing/update-route-params! app update-in sort-path merge
                                                         {:ascending? ascending?
                                                          :sort-by    qualified-key})
                                                       (-> env
                                                         (uism/assoc-aliased
                                                           :busy? false
                                                           :sort-by qualified-key
                                                           :ascending? ascending?)
                                                         (sort-rows)
                                                         (populate-current-page)))
                                                     env))}

        :event/select-row        {::uism/handler (fn [{::uism/keys [app event-data] :as env}]
                                                   (let [row               (:row event-data)
                                                         selected-row-path (route-params-path env ::selected-row)]
                                                     (when (nat-int? row)
                                                       (rad-routing/update-route-params! app assoc-in selected-row-path row))
                                                     (uism/assoc-aliased env :selected-row row)))}

        :event/sort              {::uism/handler (fn [{::uism/keys [app event-data] :as env}]
                                                   ;; this ensures that the do sort doesn't get the CPU until the busy state is rendered
                                                   (uism/trigger! app (uism/asm-id env) :event/do-sort event-data)
                                                   (uism/assoc-aliased env :busy? true))}

        :event/do-filter         {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (-> env
                                                     (uism/assoc-aliased :current-page 1 :busy? false)
                                                     (filter-rows)
                                                     (sort-rows)
                                                     ;; TODO: Why isn't this goto-page* 1???
                                                     (populate-current-page)))}

        :event/filter            {::uism/handler (fn [{::uism/keys [app] :as env}]
                                                   ;; this ensures that the do sort doesn't get the CPU until the busy state is rendered
                                                   (uism/trigger! app (uism/asm-id env) :event/do-filter)
                                                   (uism/assoc-aliased env :busy? true))}

        :event/set-ui-parameters {::uism/handler (fn [env]
                                                   (-> env
                                                     (initialize-parameters)))}

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
  a report on-screen initially (or don't use dynamic router), then you must call this to start your report.

  `options` can contain `::id`, which will cause an instance of the report to be started. Used by containers so that
  multiple instances of the same report can co-exist with different views on the same screen."
  ([app report-class]
   (start-report! app report-class {}))
  ([app report-class options]
   (let [machine-def         (or (comp/component-options report-class ::machine) report-machine)
         now-ms              (inst-ms (dt/now))
         params              (:route-params options)
         cache-expiration-ms (* 1000 (or (comp/component-options report-class ::load-cache-seconds) 0))
         asm-id              (comp/ident report-class options) ; options might contain ::report/id to instance the report
         state-map           (app/current-state app)
         asm                 (some-> state-map (get-in [::uism/asm-id asm-id]))
         running?            (some-> asm ::uism/active-state boolean)
         last-load-time      (some-> asm ::uism/local-storage :last-load-time)
         table-name          (comp/component-options report-class ::row-pk ::attr/qualified-key)
         current-table-count (count (keys (get state-map table-name)))
         last-table-count    (some-> asm ::uism/local-storage :raw-items-in-table)
         cache-expired?      (or (nil? last-load-time)
                               (not= current-table-count last-table-count)
                               (< last-load-time (- now-ms cache-expiration-ms)))]
     (if (not running?)
       (uism/begin! app machine-def asm-id {:actor/report (uism/with-actor-class asm-id report-class)} options)
       (do
         (uism/trigger! app asm-id :event/set-ui-parameters (merge options {:params params}))
         (if cache-expired?
           (uism/trigger! app asm-id :event/run)
           (uism/trigger! app asm-id :event/filter)))))))

(defn default-compare-rows
  [{:keys [sort-by ascending?]} a b]
  (try
    (let [av (get a sort-by)
          bv (get b sort-by)]
      (if ascending?
        (compare av bv)
        (compare bv av)))
    (catch #?(:clj Exception :cljs :default) _
      0)))

(defn report-will-enter [app route-params report-class]
  (let [report-ident (comp/get-ident report-class {})]
    (dr/route-deferred report-ident
      (fn []
        (start-report! app report-class {:route-params route-params})
        (comp/transact! app [(dr/target-ready {:target report-ident})])))))

#?(:clj
   (defmacro defsc-report
     "Define a report. Just like defsc, but you do not specify query/ident/etc.

     Instead, use report-options (aliased as ro below):

     ro/columns
     ro/route
     ro/row-pk
     ro/source-attribute

     If you elide the body, one will be generated for you.
     "
     [sym arglist & args]
     (let [this-sym  (first arglist)
           props-sym (second arglist)
           props-sym (if (map? props-sym) (:as props-sym) props-sym)
           options   (first args)
           options   (opts/macro-optimize-options &env options #{::field-formatters ::column-headings ::form-links} {})]
       (when (or (= '_ props-sym) (= '_ this-sym) (= props-sym this-sym) (not (symbol? this-sym)) (not (symbol? props-sym)))
         (throw (ana/error &env (str "defsc-report argument list must use a real (unique) symbol (or a destructuring with `:as`) for the `this` and `props` (1st and 2nd) arguments."))))
       (req! &env sym options ::columns #(or (symbol? %) (every? symbol? %)))
       (req! &env sym options ::row-pk #(symbol? %))
       (req! &env sym options ::source-attribute keyword?)
       (let
         [generated-row-sym (symbol (str (name sym) "-Row"))
          {::control/keys [controls]
           ::keys [BodyItem edit-form columns row-pk form-links
                   row-query-inclusion denormalize? row-actions route] :as options} options
          _                 (when edit-form (throw (ana/error &env "::edit-form is no longer supported. Use ::form-links instead.")))
          normalize?        (not denormalize?)
          ItemClass         (or BodyItem generated-row-sym)
          subquery          `(comp/get-query ~ItemClass)
          query             [::id
                             :ui/parameters
                             :ui/cache
                             :ui/busy?
                             :ui/page-count
                             :ui/current-page
                             [::picker-options/options-cache (quote '_)]
                             {:ui/controls `(comp/get-query Control)}
                             {:ui/current-rows subquery}
                             [df/marker-table '(quote _)]]
          nspc              (if (enc/compiling-cljs?) (-> &env :ns :name str) (name (ns-name *ns*)))
          fqkw              (keyword (str nspc) (name sym))
          options           (assoc (merge {::compare-rows `default-compare-rows} options)
                              :route-segment (if (vector? route) route [route])
                              :will-enter `(fn [app# route-params#] (report-will-enter app# route-params# ~sym))
                              ::BodyItem ItemClass
                              :query query
                              :initial-state (list 'fn ['params]
                                               `(cond-> {:ui/parameters   {}
                                                         :ui/cache        {}
                                                         :ui/controls     (mapv #(select-keys % #{::control/id})
                                                                            (remove :local? (control/control-map->controls ~controls)))
                                                         :ui/busy?        false
                                                         :ui/current-page 1
                                                         :ui/page-count   1
                                                         :ui/current-rows []}
                                                  (contains? ~'params ::id) (assoc ::id (::id ~'params))))
                              :ident (list 'fn [] [::id `(or (::id ~props-sym) ~fqkw)]))
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
                              normalize? (assoc :ident row-ident)
                              form-links (assoc ::form-links form-links))
          defs              (if-not BodyItem
                              [`(comp/defsc ~generated-row-sym [this# ~props-sym computed#]
                                  ~body-options
                                  (render-row (:report-instance computed#) ~generated-row-sym ~props-sym))
                               `(comp/defsc ~sym ~arglist ~options ~@body)]
                              [`(comp/defsc ~sym ~arglist ~options ~@body)])]
         `(do
            ~@defs)))))

#?(:clj (s/fdef defsc-report :args ::comp/args))

(def ^:deprecated reload!
  "Alias to `control/run!`. Runs the report."
  control/run!)

(def ^:deprecated set-parameter!
  "Alias to `control/set-parameter!`. Set the given parameter value on the report. Usually used internally by controls."
  control/set-parameter!)

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

(defn built-in-formatter [type style]
  (get-in
    {:string  {:default (fn [_ value] value)}
     :instant {:default         (fn [_ value] (dt/inst->human-readable-date value))
               :short-timestamp (fn [_ value] (dt/tformat "MMM d, h:mma" value))
               :timestamp       (fn [_ value] (dt/tformat "MMM d, yyyy h:mma" value))
               :date            (fn [_ value] (dt/tformat "MMM d, yyyy" value))
               :month-day       (fn [_ value] (dt/tformat "MMM d" value))
               :time            (fn [_ value] (dt/tformat "h:mma" value))}
     :int     {:default (fn [_ value] (str value))}
     :decimal {:default    (fn [_ value] (math/numeric->str value))
               :currency   (fn [_ value] (math/numeric->str (math/round value 2)))
               :percentage (fn [_ value] (math/numeric->percent-str value))
               :USD        (fn [_ value] (math/numeric->currency-str value))}
     :boolean {:default (fn [_ value] (if value "true" "false"))}}
    [type style]))

(defn formatted-column-value
  "Given a report instance, a row of props, and a column attribute for that report:
   returns the formatted value of that column using the field formatter(s) defined
   on the column attribute or report. If no formatter is provided a default formatter
   will be used."
  [report-instance row-props {::keys      [field-formatter column-styles]
                              ::attr/keys [qualified-key type style] :as column-attribute}]
  (let [value                  (get row-props qualified-key)
        report-field-formatter (comp/component-options report-instance ::field-formatters qualified-key)
        {::app/keys [runtime-atom]} (comp/any->app report-instance)
        formatter              (cond
                                 report-field-formatter report-field-formatter
                                 field-formatter field-formatter
                                 :else (let [style                (or
                                                                    (get column-styles qualified-key)
                                                                    style
                                                                    :default)
                                             installed-formatters (some-> runtime-atom deref ::type->style->formatter)
                                             formatter            (get-in installed-formatters [type style])]
                                         (or
                                           formatter
                                           (built-in-formatter type style)
                                           (fn [_ v] (str v)))))
        formatted-value        (formatter report-instance value row-props column-attribute)]
    formatted-value))

(defn install-formatter!
  "Install a formatter for the given data type and style. The data type must match a supported data type
   of attributes, and the style can either be `:default` or a user-defined keyword the represents the
   style you want to support. Some common styles have predefined support, such as `:USD` for US Dollars.

   This should be called before mounting your app.

   Ex.:

   ```clojure
   (install-formatter! app :boolean :default (fn [report-instance value] (if value \"yes\" \"no\")))
   ```"
  [app type style formatter]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [::type->style->formatter type style] formatter)))

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

(defn current-page
  "Returns the current page number displayed on the report"
  [report-instance]
  (get-in (comp/props report-instance) [:ui/parameters ::current-page] 1))

(defn page-count
  "Returns how many pages the current report has."
  [report-instance]
  (get-in (comp/props report-instance) [:ui/page-count] 1))

(defn currently-selected-row
  "Returns the currently-selected row index, if any."
  [report-instance]
  (get-in (comp/props report-instance) [:ui/parameters ::selected-row] -1))

(defn select-row! [report-instance idx]
  (uism/trigger! report-instance (comp/get-ident report-instance) :event/select-row {:row idx}))

(defn column-classes
  "Returns a string of column classes that can be defined on the attribute at ::report/column-class or on the
   report in the ::report/column-classes map. The report map overrides the attribute"
  [report-instance {::keys      [column-class]
                    ::attr/keys [qualified-key] :as attr}]
  (let [rpt-column-class (comp/component-options report-instance ::column-classes qualified-key)]
    (or rpt-column-class column-class)))
