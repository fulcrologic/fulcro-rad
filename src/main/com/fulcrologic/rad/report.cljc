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
    [clojure.string :as str]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fstate]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.control :as control :refer [Control]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.rad.routing.history :as history]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn report-ident
  "Returns the ident of a RAD report. The parameter can be a react instance, a class, or the registry key(word)
   of the report."
  [report-class-or-registry-key]
  (if (keyword? report-class-or-registry-key)
    [::id report-class-or-registry-key]
    (comp/get-ident report-class-or-registry-key {})))

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
  "Get the report controls renderer for the given report instance. Returns a `(fn [this])`."
  [report-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        control-style (or (some-> report-instance comp/component-options ::control-style) :default)
        control       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::control-style->control control-style)]
    (if control
      control
      (do
        (log/error "No layout function found for report control style" control-style)
        nil))))

(defn render-controls
  "Renders just the control section of the report. See also `control-renderer` if you desire rendering the controls in
   more than one place in the UI at once (e.g. top/bottom)."
  [report-instance]
  ((control-renderer report-instance) report-instance))

(def render-control
  "[report-instance control-key]

   Render a single control, wrapped by minimal chrome. This is just an alias for control/render-control."
  control/render-control)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def global-events {:event/clear-sort
                    {::uism/handler (fn [env] (uism/dissoc-aliased env :sort-by))}})

(defn report-options
  "Returns the report options from the current report actor."
  [uism-env & k-or-ks]
  (apply comp/component-options (uism/actor-class uism-env :actor/report) k-or-ks))

(defn route-params-path
  "Path within the EDN stored on the URL (route params) where the given control key should be stored. When more than
   one report is one the screen these would collide, so when it is a global control it can be stored just by key, but
   when it is local it must be stored by report ID + key. This helper can be used by extensions to the stock state machine."
  [env control-key]
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
        current-page       (get-in history-params (route-params-path env ::current-page) 1)
        controls           (report-options env :com.fulcrologic.rad.control/controls)
        original-state-map (::uism/state-map env)
        initial-parameters (cond-> {::sort         (initial-sort-params env)
                                    ::current-page current-page}
                             selected-row (assoc ::selected-row selected-row))]
    (as-> env $
      (uism/apply-action $ assoc-in path (deep-merge initial-parameters {::sort (get-in history-params sort-path {})}))
      (reduce-kv
        (fn [new-env control-key {:keys [local? retain? default-value]}]
          (let [param-path         (route-params-path env control-key)
                event-value        (enc/nnil (get-in params param-path) (get params control-key))
                control-value-path (if local?
                                     (conj report-ident :ui/parameters control-key)
                                     [::control/id control-key ::control/value])
                state-value        (when-not (false? retain?) (get-in original-state-map control-value-path))
                url-value          (get-in history-params param-path)
                explicit-value     (enc/nnil event-value url-value)
                default-value      (?! default-value app)
                v                  (enc/nnil explicit-value state-value default-value)
                skip-assignment?   (or
                                     ;; A container is controlling this report, and it is a global control.
                                     (and (not local?) externally-controlled?)
                                     ;; There's nothing to assign
                                     (nil? v))]
            (if skip-assignment?
              new-env
              (uism/apply-action new-env assoc-in control-value-path v))))
        $
        controls))))

(defn current-control-parameters
  "Internal state machine helper. May be used by extensions to the stock state machine."
  [{::uism/keys [state-map] :as env}]
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
        {::keys [BodyItem source-attribute load-options before-load]} (comp/component-options Report)
        load-options   (?! load-options env)
        current-params (current-control-parameters env)
        path           (conj report-ident :ui/loaded-data)]
    (log/debug "Loading report" source-attribute (comp/component-name Report) (comp/component-name BodyItem))
    (-> env
      (cond->
        before-load (before-load))
      (uism/load source-attribute BodyItem (merge
                                             {:params            current-params
                                              ::uism/ok-event    :event/loaded
                                              ::uism/error-event :event/failed
                                              :marker            report-ident
                                              :target            path}
                                             load-options))
      (uism/activate :state/loading))))

(defn filter-rows
  "Generates filtered rows, which is an intermediate cached value (not displayed). This function is used in the
   internal state machine, and may be useful when extending the pre-defined machine."
  [{::uism/keys [state-map] :as uism-env}]
  (let [all-rows   (uism/alias-value uism-env :raw-rows)
        parameters (current-control-parameters uism-env)
        {::keys [row-visible? skip-filtering?]} (report-options uism-env)]
    (if (and row-visible? (not (true? (?! skip-filtering? parameters))))
      (let [normalized?   (some-> all-rows (first) (eql/ident?))
            report        (uism/actor-class uism-env :actor/report)
            BodyItem      (comp/component-options report ro/BodyItem)
            filtered-rows (filterv
                            (fn [row]
                              (let [row (if normalized? (fstate/ui->props state-map BodyItem row) row)]
                                (row-visible? parameters row)))
                            all-rows)]
        (uism/assoc-aliased uism-env :filtered-rows filtered-rows))
      (uism/assoc-aliased uism-env :filtered-rows all-rows))))

(defn sort-rows
  "Sorts the filtered rows. Input is the cached intermediate filtered rows, output is cached sorted rows (not visible). This function is used in the
   internal state machine, and may be useful when extending the pre-defined machine."
  [{::uism/keys [state-map] :as uism-env}]
  (let [{desired-sort-by :sort-by :as sort-params} (merge (uism/alias-value uism-env :sort-params) {:state-map state-map})
        all-rows (uism/alias-value uism-env :filtered-rows)]
    (if desired-sort-by
      (let [compare-rows (report-options uism-env ::compare-rows)
            normalized?  (some-> all-rows (first) (eql/ident?))
            sorted-rows  (if compare-rows
                           (let [
                                 keyfn     (if normalized? #(get-in state-map %) identity)
                                 comparefn (fn [a b] (compare-rows sort-params a b))]
                             (vec (sort-by keyfn comparefn all-rows)))
                           all-rows)]
        (uism/assoc-aliased uism-env :sorted-rows sorted-rows))
      (uism/assoc-aliased uism-env :sorted-rows all-rows))))

(declare goto-page*)

(defn page-number-changed
  "Internal state machine helper. May be used by extensions.
   Sends a message to routing system that the page number changed. "
  [env]
  (when-not (false? (report-options env ro/track-in-url?))
    (let [pg        (uism/alias-value env :current-page)
          row-path  (route-params-path env ::selected-row)
          page-path (route-params-path env ::current-page)]
      (rad-routing/update-route-params! (::uism/app env) (fn [p]
                                                           (-> p
                                                             (assoc-in row-path -1)
                                                             (assoc-in page-path pg))))))
  env)

(defn postprocess-page
  "Internal state machine helper.

   Apply the user-defined UISM operation to the report state machine just after the current page has
   been populated. The :current-rows alias will have the result of filter/sort/paginate, and the
   report actor is :actor/report. See the definition of the report state machine for more information."
  [uism-env]
  (let [xform (report-options uism-env ro/post-process)]
    (if xform
      (xform uism-env)
      uism-env)))

(defn populate-current-page
  "Internal state machine implementation. May be used by extensions to the stock state machine."
  [uism-env]
  (->
    (if (report-options uism-env ::paginate?)
      (let [current-page   (max 1 (uism/alias-value uism-env :current-page))
            page-size      (or (?! (report-options uism-env ::page-size) uism-env) 20)
            available-rows (or (uism/alias-value uism-env :sorted-rows) [])
            n              (count available-rows)
            stragglers?    (pos? (rem n page-size))
            pages          (cond-> (int (/ n page-size))
                             stragglers? inc)
            current-page   (cond
                             (zero? pages) 1
                             (> current-page pages) pages
                             :else current-page)
            page-start     (* (dec current-page) page-size)
            rows           (cond
                             (= pages current-page) (subvec available-rows page-start n)
                             (> n page-size) (subvec available-rows page-start (+ page-start page-size))
                             :else available-rows)]
        (if (and (not= 1 current-page) (empty? rows))
          (goto-page* uism-env 1)
          (-> uism-env
            (uism/assoc-aliased :current-page current-page :current-rows rows :page-count pages))))
      (-> uism-env
        (uism/assoc-aliased
          :page-count 1
          :current-rows (uism/alias-value uism-env :sorted-rows))))
    (postprocess-page)))

(defn goto-page*
  "Internal state machine implementation. May be used by extensions to the stock state machine."
  [env page]
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

(defn preprocess-raw-result
  "Internal state machine implementation. May be used by extensions to the stock state machine.
   Apply the raw result transform, if it is defined."
  [uism-env]
  (let [xform (report-options uism-env ::raw-result-xform)]
    (if xform
      (let [raw-result (uism/alias-value uism-env :raw-rows)
            report     (uism/actor-class uism-env :actor/report)
            new-result (xform report raw-result)]
        (cond-> uism-env
          new-result (uism/assoc-aliased :raw-rows new-result)))
      uism-env)))

(defn handle-filter-event
  "Internal state machien implementation of handling :event/filter."
  [{::uism/keys [app] :as env}]
  ;; this ensures that the do sort doesn't get the CPU until the busy state is rendered
  (uism/trigger! app (uism/asm-id env) :event/do-filter)
  (uism/assoc-aliased env :busy? true))

(defn report-cache-expired?
  "Helper for state machines. Returns true if the report data looks like it has expired according to configured
   caching parameters."
  [{::uism/keys [state-map] :as uism-env}]
  (let [Report              (uism/actor-class uism-env :actor/report)
        {::keys [load-cache-seconds
                 load-cache-expired?
                 row-pk]} (comp/component-options Report)
        now-ms              (inst-ms (dt/now))
        last-load-time      (uism/retrieve uism-env :last-load-time)
        last-table-count    (uism/retrieve uism-env :raw-items-in-table)
        cache-expiration-ms (* 1000 (or load-cache-seconds 0))
        table-name          (::attr/qualified-key row-pk)
        current-table-count (count (keys (get state-map table-name)))
        cache-looks-stale?  (or
                              (nil? last-load-time)
                              (not= current-table-count last-table-count)
                              (< last-load-time (- now-ms cache-expiration-ms)))
        user-cache-expired? (?! load-cache-expired? uism-env cache-looks-stale?)]
    (if (boolean user-cache-expired?)
      user-cache-expired?
      cache-looks-stale?)))

(defn handle-resume-report
  "Internal state machine implementation. Called on :event/resumt to do the steps to resume an already running report
   that has just been re-mounted."
  [env]
  (let [env (initialize-parameters env)]
    (if (report-cache-expired? env)
      (load-report! env)
      (handle-filter-event env))))

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
    {::uism/events
     (merge global-events
       {:event/loaded {::uism/handler (fn [{::uism/keys [state-map] :as env}]
                                        (let [Report     (uism/actor-class env :actor/report)
                                              {::keys [row-pk report-loaded]} (comp/component-options Report)
                                              table-name (::attr/qualified-key row-pk)]
                                          (-> env
                                            (preprocess-raw-result)
                                            (filter-rows)
                                            (sort-rows)
                                            (populate-current-page)
                                            (uism/store :last-load-time (inst-ms (dt/now)))
                                            (uism/store :raw-items-in-table (count (keys (get state-map table-name))))
                                            (uism/activate :state/gathering-parameters)
                                            (cond-> report-loaded report-loaded))))}
        :event/failed {::uism/handler (fn [env] (log/error "Report failed to load.")
                                        (uism/activate env :state/gathering-parameters))}})}

    :state/gathering-parameters
    {::uism/events
     (merge global-events
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
                                                       (when-not (false? (report-options env ro/track-in-url?))
                                                         (rad-routing/update-route-params! app update-in sort-path merge
                                                           {:ascending? ascending?
                                                            :sort-by    qualified-key}))
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
                                                     (when (and (nat-int? row)
                                                             (not (false? (report-options env ro/track-in-url?))))
                                                       (rad-routing/update-route-params! app assoc-in selected-row-path row))
                                                     (uism/assoc-aliased env :selected-row row)))}

        :event/sort              {::uism/handler (fn [{::uism/keys [app event-data] :as env}]
                                                   ;; this ensures that the do sort doesn't get the CPU until the busy state is rendered
                                                   (uism/trigger! app (uism/asm-id env) :event/do-sort event-data)
                                                   (uism/assoc-aliased env :busy? true))}

        :event/do-filter         {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (-> env
                                                     (uism/assoc-aliased :busy? false)
                                                     (filter-rows)
                                                     (sort-rows)
                                                     (populate-current-page)))}

        :event/filter            {::uism/handler handle-filter-event}

        :event/set-ui-parameters {::uism/handler initialize-parameters}

        :event/run               {::uism/handler load-report!}

        :event/resume            {::uism/handler handle-resume-report}})}}})

(defn run-report!
  "Run a report with the current parameters"
  ([this]
   (uism/trigger! this (comp/get-ident this) :event/run))
  ([app-ish class-or-registry-key]
   (uism/trigger! app-ish (report-ident class-or-registry-key) :event/run)))

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
   (let [machine-def (or (comp/component-options report-class ::machine) report-machine)
         params      (:route-params options)
         asm-id      (comp/ident report-class options)      ; options might contain ::report/id to instance the report
         state-map   (app/current-state app)
         asm         (some-> state-map (get-in [::uism/asm-id asm-id]))
         running?    (some-> asm ::uism/active-state boolean)]
     (if (not running?)
       (uism/begin! app machine-def asm-id {:actor/report (uism/with-actor-class asm-id report-class)} (assoc options :params params))
       (uism/trigger! app asm-id :event/resume (assoc options :params params))))))

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

     If you elide the body, one will be generated for you with the classname `{sym}-Row` where `sym` is the sym you supply
     for the report itself.
     "
     [sym arglist & args]
     (let [this-sym  (first arglist)
           props-sym (second arglist)
           props-sym (if (map? props-sym) (:as props-sym) props-sym)
           options   (first args)
           options   (opts/macro-optimize-options &env options #{::column-formatters ::field-formatters ::column-headings ::form-links} {})]
       (when (or (= '_ props-sym) (= '_ this-sym) (= props-sym this-sym) (not (symbol? this-sym)) (not (symbol? props-sym)))
         (throw (ana/error &env (str "defsc-report argument list must use a real (unique) symbol (or a destructuring with `:as`) for the `this` and `props` (1st and 2nd) arguments."))))
       (req! &env sym options ::columns #(or (symbol? %) (every? symbol? %)))
       (req! &env sym options ::row-pk #(symbol? %))
       (req! &env sym options ::source-attribute keyword?)
       (let
         [generated-row-sym (symbol (str (name sym) "-Row"))
          {::control/keys [controls]
           ::keys [BodyItem edit-form columns row-pk form-links query-inclusions
                   row-query-inclusion denormalize? row-actions route] :as options} options
          _                 (when edit-form (throw (ana/error &env "::edit-form is no longer supported. Use ::form-links instead.")))
          normalize?        (not denormalize?)
          ItemClass         (or BodyItem generated-row-sym)
          subquery          `(comp/get-query ~ItemClass)
          nspc              (if (enc/compiling-cljs?) (-> &env :ns :name str) (name (ns-name *ns*)))
          fqkw              (keyword (str nspc) (name sym))
          query             (into [::id
                                   :ui/parameters
                                   :ui/cache
                                   :ui/busy?
                                   :ui/page-count
                                   :ui/current-page
                                   [::uism/asm-id [::id fqkw]]
                                   [::picker-options/options-cache (quote '_)]
                                   {:ui/controls `(comp/get-query Control)}
                                   {:ui/current-rows subquery}
                                   [df/marker-table '(quote _)]]
                              query-inclusions)
          options           (merge
                              {::compare-rows `default-compare-rows
                               :will-enter    `(fn [app# route-params#] (report-will-enter app# route-params# ~sym))}
                              options
                              {:route-segment (if (vector? route) route [route])
                               ::BodyItem     ItemClass
                               :query         query
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
                               :ident         (list 'fn [] [::id `(or (::id ~props-sym) ~fqkw)])})
          body              (if (seq (rest args))
                              (rest args)
                              [`(render-layout ~this-sym)])
          row-query         (list 'fn [] `(let [forms#    ~(::form-links options)
                                                id-attrs# (keep #(comp/component-options % ::form/id) (vals forms#))]
                                            (vec
                                              (into #{~@row-query-inclusion}
                                                (map (fn [attr#] (or
                                                                   (::column-EQL attr#)
                                                                   (::attr/qualified-key attr#))) (conj (set (concat id-attrs# ~columns)) ~row-pk))))))
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
  "[instance k v]

   Alias to `control/set-parameter!`. Set the given parameter value on the report. Usually used internally by controls."
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
     :keyword {:default (fn [_ value _ column-attribute]
                          (if-let [labels (::attr/enumerated-labels column-attribute)]
                            (labels value)
                            (some-> value (name) str/capitalize)))}
     :enum    {:default (fn [_ value _ column-attribute]
                          (if-let [labels (::attr/enumerated-labels column-attribute)]
                            (labels value) (str value)))}
     :int     {:default (fn [_ value] (str value))}
     :decimal {:default    (fn [_ value] (math/numeric->str value))
               :currency   (fn [_ value] (math/numeric->str (math/round value 2)))
               :percentage (fn [_ value] (math/numeric->percent-str value))
               :USD        (fn [_ value] (math/numeric->currency-str value))}
     :boolean {:default (fn [_ value] (if value (tr "true") (tr "false")))}}
    [type style]))

(defn formatted-column-value
  "Given a report instance, a row of props, and a column attribute for that report:
   returns the formatted value of that column using the field formatter(s) defined
   on the column attribute or report. If no formatter is provided a default formatter
   will be used."
  [report-instance row-props {::keys      [field-formatter column-formatter]
                              ::attr/keys [qualified-key type style] :as column-attribute}]
  (let [value                  (get row-props qualified-key)
        report-field-formatter (or
                                 (comp/component-options report-instance ::column-formatters qualified-key)
                                 (comp/component-options report-instance ::field-formatters qualified-key))
        {::app/keys [runtime-atom]} (comp/any->app report-instance)
        formatter              (cond
                                 report-field-formatter report-field-formatter
                                 column-formatter column-formatter
                                 field-formatter field-formatter
                                 :else (let [style                (or
                                                                    (comp/component-options report-instance ::column-styles qualified-key)
                                                                    style
                                                                    :default)
                                             installed-formatters (some-> runtime-atom deref :com.fulcrologic.rad/controls ::type->style->formatter)
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
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls ::type->style->formatter type style] formatter)))

(defn install-layout!
  "Install a report layout renderer for the given style. `render-row` is a `(fn [report-instance])`.

  See other support functions in this ns for help rendering, such as `formatted-column-value`, `form-link`,
  `select-row!`.

   This should be called before mounting your app.
   "
  [app report-style render]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls ::style->layout report-style] render)))

(defn install-row-layout!
  "Install a row layout renderer for the given style. `render-row` is a `(fn [report-instance row-class row-props])`.

  See other support functions in this ns for help rendering, such as `formatted-column-value`, `form-link`,
  `select-row!`.

   This should be called before mounting your app.
   "
  [app row-style render-row]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls ::row-style->row-layout row-style] render-row)))

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
   of sorting is built-in and uses compare, but you can override how sorting works by defining `ro/compare-rows` on your report."
  ([this by-attribute]
   (uism/trigger! this (comp/get-ident this) :event/sort {::attr/attribute by-attribute}))
  ([app class-or-reg-key by-attribute]
   (uism/trigger! app (report-ident class-or-reg-key) :event/sort {::attr/attribute by-attribute})))

(defn clear-sort!
  "Make it so the report is not sorted (skips the sort step on any action that would normally (re)sort
   the report). This can be used to speed up loading of large results, especially if they were
   already in an acceptable order from the server.

   NOTE: This does NOT refresh the report. The natural order will appear next time the report needs sorted."
  ([this]
   (uism/trigger! this (comp/get-ident this) :event/clear-sort))
  ([app class-or-reg-key]
   (uism/trigger! app (report-ident class-or-reg-key) :event/clear-sort)))

(defn filter-rows!
  "Update the filtered rows based on current report parameters."
  ([this]
   (uism/trigger! this (comp/get-ident this) :event/filter))
  ([app class-or-reg-key]
   (uism/trigger! app (report-ident class-or-reg-key) :event/filter)))

(defn goto-page!
  "Move to the next page (if there is one)"
  ([this page-number]
   (uism/trigger! this (comp/get-ident this) :event/goto-page {:page page-number}))
  ([app class-or-reg-key page-number]
   (uism/trigger! app (report-ident class-or-reg-key) :event/goto-page {:page page-number})))

(defn next-page!
  "Move to the next page (if there is one)"
  ([this]
   (uism/trigger! this (comp/get-ident this) :event/next-page))
  ([app class-or-reg-key]
   (uism/trigger! app (report-ident class-or-reg-key) :event/next-page)))

(defn prior-page!
  "Move to the next page (if there is one)"
  ([this]
   (uism/trigger! this (comp/get-ident this) :event/prior-page))
  ([app class-or-reg-key]
   (uism/trigger! app (report-ident class-or-reg-key) :event/prior-page)))

(defn current-page
  "Returns the current page number displayed on the report"
  ([report-instance]
   (get-in (comp/props report-instance) [:ui/parameters ::current-page] 1))
  ([state-map report-class-or-registry-key]
   (get-in state-map (conj (report-ident report-class-or-registry-key) :ui/parameters ::current-page) 1)))

(defn page-count
  "Returns how many pages the current report has."
  ([report-instance]
   (get-in (comp/props report-instance) [:ui/page-count] 1))
  ([state-map report-class-or-registry-key]
   (get-in state-map (conj (report-ident report-class-or-registry-key) :ui/page-count) 1)))

(defn currently-selected-row
  "Returns the currently-selected row index, if any (-1 if nothing is selected)."
  ([report-instance]
   (get-in (comp/props report-instance) [:ui/parameters ::selected-row] -1))
  ([state-map report-class-or-registry-key]
   (get-in state-map (conj (report-ident report-class-or-registry-key) :ui/parameters ::selected-row) -1)))

(defn select-row!
  ([report-instance idx]
   (uism/trigger! report-instance (comp/get-ident report-instance) :event/select-row {:row idx}))
  ([app class-or-reg-key idx]
   (uism/trigger! app (report-ident class-or-reg-key) :event/select-row {:row idx})))

(defn column-classes
  "Returns a string of column classes that can be defined on the attribute at ::report/column-class or on the
   report in the ::report/column-classes map. The report map overrides the attribute"
  [report-instance-or-class {::keys      [column-class]
                             ::attr/keys [qualified-key] :as attr}]
  (let [rpt-column-class (comp/component-options report-instance-or-class ::column-classes qualified-key)]
    (or rpt-column-class column-class)))

(defn genrow
  "Generates a row class for reports. Mainly meant for internal use, but might be useful in custom report generation code.

  registry-key - The unique key to register the generated class under
  options - The top report options"
  [registry-key options]
  (let [{::keys [columns row-pk form-links initLocalState
                 row-query-inclusion denormalize? row-actions]} options
        normalize?   (not denormalize?)
        row-query    (let [id-attrs (keep #(comp/component-options % ::form/id) (vals form-links))]
                       (vec
                         (into (set row-query-inclusion)
                           (map (fn [attr] (or
                                             (::column-EQL attr)
                                             (::attr/qualified-key attr))) (conj (set (concat id-attrs columns)) row-pk)))))
        row-key      (::attr/qualified-key row-pk)
        row-ident    (fn [this props] [row-key (get props row-key)])
        row-actions  (or row-actions [])
        row-render   (fn [this]
                       (comp/wrapped-render this
                         (fn []
                           (let [props (comp/props this)]
                             (render-row this (rc/registry-key->class registry-key) props)))))
        body-options (cond-> {:query        (fn [this] row-query)
                              ::row-actions row-actions
                              ::columns     columns}
                       normalize? (assoc :ident row-ident)
                       form-links (assoc ::form-links form-links))]
    (comp/sc registry-key body-options row-render)))

(defn report
  "Create a RAD report component. `options` is the map of report/Fulcro options. The `registry-key` is the globally
   unique name (as a keyword) that this component should be known by, and `render` is a `(fn [this props])` (optional)
   for rendering the body, which defaults to the built-in `render-layout`.

   WARNING: The macro version ensures that there is a constant react type to refer to. Using this function MAY cause
   hot code reload behaviors that rely on react-type to misbehave due to the mismatch (closure over old version)."
  ([registry-key options]
   (report registry-key options (fn [this _] (render-layout this))))
  ([registry-key options render]
   (assert (vector? (options ::columns)))
   (assert (attr/attribute? (options ::row-pk)))
   (assert (keyword? (options ::source-attribute)))
   (let [generated-row-key (keyword (namespace registry-key) (str (name registry-key) "-Row"))
         {::control/keys [controls]
          ::keys         [BodyItem query-inclusions route]} options
         constructor       (comp/react-constructor (:initLocalState options))
         get-class         (fn [] constructor)
         ItemClass         (or BodyItem (genrow generated-row-key options))
         query             (into [::id
                                  :ui/parameters
                                  :ui/cache
                                  :ui/busy?
                                  :ui/page-count
                                  :ui/current-page
                                  [::uism/asm-id [::id registry-key]]
                                  [::picker-options/options-cache '_]
                                  {:ui/controls (comp/get-query Control)}
                                  {:ui/current-rows (comp/get-query ItemClass)}
                                  [df/marker-table '_]]
                             query-inclusions)
         render            (fn [this]
                             (comp/wrapped-render this
                               (fn []
                                 (let [props (comp/props this)]
                                   (render this props)))))
         options           (merge
                             {::compare-rows default-compare-rows
                              :will-enter    (fn [app route-params] (report-will-enter app route-params (get-class)))}
                             options
                             {:route-segment (if (vector? route) route [route])
                              :render        render
                              ::BodyItem     ItemClass
                              :query         (fn [] query)
                              :initial-state (fn [params]
                                               (cond-> {:ui/parameters   {}
                                                        :ui/cache        {}
                                                        :ui/controls     (mapv #(select-keys % #{::control/id})
                                                                           (remove :local? (control/control-map->controls controls)))
                                                        :ui/busy?        false
                                                        :ui/current-page 1
                                                        :ui/page-count   1
                                                        :ui/current-rows []}
                                                 (contains? params ::id) (assoc ::id (::id params))))
                              :ident         (fn [this props] [::id (or (::id props) registry-key)])})]
     (comp/sc registry-key options render))))

(def ^:deprecated generated-row-class "Accidental duplication. Use genrow instead" genrow)
(def ^:deprecated sc-report "Accidental duplication. Use `report` instead." report)

(defn clear-report*
  "Mutation helper. Clear a report out of app state. The report should not be visible when you do this."
  [state-map ReportClass]
  (let [report-ident (comp/get-ident ReportClass {})
        [table report-class-registry-key] report-ident]
    (-> state-map
      (update ::uism/asm-id dissoc report-ident)
      (update table dissoc report-class-registry-key)
      (merge/merge-component ReportClass (comp/get-initial-state ReportClass {})))))

(defmutation clear-report
  "MUTATION: Clear a report (which should not be on screen) out of app state."
  [{:keys [report-ident]}]
  (action [{:keys [state]}]
    (let [[table report-class-registry-key] report-ident
          Report (comp/registry-key->class report-class-registry-key)]
      (swap! state clear-report* Report))))

(defn clear-report!
  "Run a transaction that completely clears a report (which should not be on-screen) out of app state."
  [app-ish ReportClass]
  (comp/transact! app-ish [(clear-report {:report-ident (comp/get-ident ReportClass {})})]))

(defn trigger!
  "Trigger an event on a report. You can use the `this` of the report with arity-2 and -3.

   For arity-4 the `report-class-ish` is something from which the report's ident can be derived: I.e. The
   report class, report's Fulcro registry key, or the ident itself.

   This should not be used from within the state machine itself. Use `uism/trigger` for that."
  ([report-instance event]
   (trigger! report-instance event {}))
  ([report-instance event event-data]
   (trigger! report-instance report-instance event event-data))
  ([app-ish report-class-ish event event-data]
   (let [report-ident (cond
                        (or
                          (string? report-class-ish)
                          (symbol? report-class-ish)
                          (keyword? report-class-ish)) (some-> report-class-ish (comp/registry-key->class) (comp/get-ident {}))
                        (vector? report-class-ish) report-class-ish
                        (comp/component-class? report-class-ish) (comp/get-ident report-class-ish {})
                        (comp/component-instance? report-class-ish) (comp/get-ident report-class-ish))]
     (when-not (vector? report-ident)
       (log/error (ex-info "Cannot trigger an event on a report with invalid report identifier"
                    {:value report-class-ish
                     :type  (type report-class-ish)})))
     (uism/trigger!! app-ish report-ident event event-data))))
