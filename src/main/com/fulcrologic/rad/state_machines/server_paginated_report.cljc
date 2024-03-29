(ns com.fulcrologic.rad.state-machines.server-paginated-report
  "A Report state machine that is tuned to work with large data sets on the server, where a small subset of the data
   is loaded at any given time, and the server is responsible for sorting and filtering. Requires that the data's
   resolver supports parameters that indicate the current offset, page size (limit), sort key, sort direction,
   and filter(s).

   # Resolver Specification

   The specification for the resolver is as follows:

   ## Input Parameters

   The query parameters on the top-level key (e.g. from df/load! params) will include a number of things. These can be:

   * Explicit values: Use the keyword itself.
   * Ranges: Using narrowed keywords with start/end as their name. E.g. if you are searching for invoices with
     :invoice/date between A and B, then the parameters used should be :invoice.date/start and :invoice.date/end.
   * Subsets: A multiselect control can send a set of values. This filter parameter should use an extended
     keyword with `status` as the name. E.g. :invoice/status -> :invoice.status/subset.

   Additionally the special key `:indexed-access/options` must be supported, and must allow for a map with the following
   keys:

   * `:include-total?` - (OPTIONAL SUPPORT) A boolean to indicate that the server should include the total number of
     possible results. The report state machine can be tuned to do a full paginated access or a \"Load more..\" approach.
     The former requires knowing the total number of possible items, but that can be expensive to compute.
     Therefore the resolver is not required to support this option, and should only do so if it can be done efficiently.
   * `:sort-column` - A keyword that indicates which result column the total results are sorted on. The resolver is only
     required to support sorting on a column when doing so on the result set can be done efficiently, but it must be
     capable of sorting on at least ONE column or at least must guarantee a stable order for pagination.
   * `:reverse?` - A boolean to indicate you wish to reverse the sort order of the results.
   * `:limit` - The page size desired for a given load
   * `:offset` - The offset from the beginning of the results
   * `:point-in-time` - (OPTIONAL SUPPORT) The report state machine MAY send an instant (e.g. java.util.Date) as
      part of each query. This instant will be the same for a given use of the report (see below for when it changes).
      If the database supports an efficient way to have a point-in-time view of the database and it receives
      this parameter, then it should return pages based on the database as-of that instant.
      The machine will update this instant when the user:
   ** Refreshes the report
   ** Changes the sort order/filters
   ** The report is resumed

   ## Output

   The resolver must have an output signature of:

   ```
   [{:top-level-key [:next-offset {:results [k k2 k3 ...]}]}]
   ```

   where `top-level-key` is generated by you, but `:next-offset` and `:results` are literal. The `selector` is
   the pull-style expression of the things that the resolver can return. It must be able to return all of the
   keys on which it can sort, and SHOULD include at least one identity attribute so that other resolvers can fill in missing
   gaps in the data.

   # The State Machine

   The report state machine in this namespace works pretty much like the other report machines; however, it does not
   assume that it is cheap to get the total result count. If the resolver is capable of getting that number efficiently,
   then you can enable that support using the `server-paginated-report-options/direct-page-access? true` option. Otherwise,
   the machine will only ever ask for a single page of data, and can support navigating forward (if the next offset
   is positive) or reverse (if offset > 0). You can still *resume* the machine on a given page, though that may result
   in an empty page if the server has removed enough data for it to be invalid.

   ## Consistent View

   Since the report is working in a distributed fashion the state machine will send a timestamp (instant) with each
   page load. This allows the resolver (if it can) to keep the view of the database constant while the user moves
   from page to page. The resolver, of course, must be able to accomplish this, and is allowed to ignore the
   instant, leading to the behavior that edits to the database may cause irregularities in the pagination. If you'd
   rather the user always sees the more recent data even if the resolver supports a point-in-time view, then
   this can be turned off using the option `server-paginated-report-options/point-in-time-view? false`.
   "
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.raw.application :as app]
    [com.fulcrologic.fulcro.raw.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.rad.routing.history :as history]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [taoensso.timbre :as log]))

(defn start-load
  "Starts a load of `current-page`."
  [env]
  (let [Report         (uism/actor-class env :actor/report)
        report-ident   (uism/actor->ident env :actor/report)
        point-in-time  (uism/retrieve env :point-in-time)
        {:keys [sort-by ascending? current-page total-results]} (uism/aliased-data env)
        {::report/keys [source-attribute load-options page-size BodyItem]
         ::keys        [direct-page-access?]
         :or           {direct-page-access? true}} (comp/component-options Report)
        page-size      (or (?! page-size env) 20)
        load-options   (?! load-options env)
        PageQuery      (comp/nc [:total
                                 {:results (comp/get-query BodyItem)}]
                         {:componentName (keyword (str (comp/component-name Report) "-pagequery"))})
        current-params (assoc (report/current-control-parameters env)
                         :indexed-access/options (cond-> {:limit  page-size
                                                          :offset (* (max 0 (dec current-page)) page-size)}
                                                   (and (not total-results) direct-page-access?) (assoc :include-total? true)
                                                   (keyword? sort-by) (assoc :sort-column sort-by)
                                                   (false? ascending?) (assoc :reverse? true)
                                                   (inst? point-in-time) (assoc :point-in-time point-in-time)))
        page-path      (uism/resolve-alias env :loaded-page)]
    (-> env
      (uism/assoc-aliased :raw-rows [])
      (uism/load source-attribute PageQuery (merge
                                              {:params            current-params
                                               ::uism/ok-event    :event/page-loaded
                                               ::uism/error-event :event/failed
                                               :marker            report-ident
                                               :target            page-path}
                                              load-options))
      (uism/activate :state/loading))))

(defn populate-current-page [{::uism/keys [state-map] :as env}]
  (let [current-page (uism/alias-value env :current-page)
        page-path    (conj (uism/resolve-alias env :page-cache) current-page)
        rows         (get-in state-map page-path)]
    (uism/assoc-aliased env :current-rows rows)))

(defn goto-page [{::uism/keys [state-map] :as env} page-number]
  (let [current-page (uism/alias-value env :current-page)
        page-path    (conj (uism/resolve-alias env :page-cache) page-number)
        rows         (get-in state-map page-path)]
    (cond
      (= page-number current-page) env
      (seq rows) (-> env
                   (uism/assoc-aliased :current-page page-number :selected-row -1)
                   (populate-current-page)
                   (report/page-number-changed))
      :else (-> env
              (uism/assoc-aliased :current-page page-number :selected-row -1)
              (report/page-number-changed)
              (start-load)))))

(defn process-loaded-page [env]
  (let [Report                         (uism/actor-class env :actor/report)
        {::report/keys [BodyItem page-size report-loaded]} (comp/component-options Report)
        page-size                      (or (?! page-size env) 20)
        {:keys [results total]} (uism/alias-value env :loaded-page)
        current-page                   (uism/alias-value env :current-page)
        page-count                     (when (number? total) (if (zero? total)
                                                               0
                                                               (cond-> (int (/ total page-size))
                                                                 (pos? (mod total page-size)) (inc))))
        raw-target-path                (uism/resolve-alias env :raw-rows)
        page-path                      (conj (uism/resolve-alias env :page-cache) current-page)
        move-raw-results-to-page-cache (fn [env]
                                         (let [rows (uism/alias-value env :raw-rows)]
                                           (-> env
                                             (uism/apply-action assoc-in page-path rows))))
        append-results                 (fn [state-map]
                                         (reduce
                                           (fn [s item] (merge/merge-component s BodyItem item :append raw-target-path))
                                           state-map
                                           results))]
    (-> env
      (cond->
        (number? page-count) (uism/assoc-aliased :page-count page-count)
        (number? total) (uism/assoc-aliased :total-results total))
      (uism/apply-action append-results)
      (report/preprocess-raw-result)
      (move-raw-results-to-page-cache)
      (populate-current-page)
      (uism/activate :state/gathering-parameters)
      (cond-> report-loaded report-loaded))))

(defn handle-resume-report
  "Internal state machine implementation. Called on :event/resumt to do the steps to resume an already running report
   that has just been re-mounted."
  [env]
  (let [{::uism/keys [fulcro-app event-data]} env
        {::keys [point-in-time-view?]} (report/report-options env)
        start-time   (when point-in-time-view? (dt/now))
        page-path    (report/route-params-path env ::current-page)
        desired-page (-> (history/current-route fulcro-app)
                       :params
                       (get-in page-path))]
    (-> env
      (uism/store :route-params (:route-params event-data))
      (uism/store :point-in-time start-time)
      (cond->
        desired-page (uism/assoc-aliased :current-page desired-page)
        (nil? desired-page) (uism/assoc-aliased :current-page 1))
      (report/initialize-parameters)
      (start-load))))

(defn start [env]
  (let [{::uism/keys [event-data]} env
        {::keys        [point-in-time-view?]
         ::report/keys [run-on-mount?]} (report/report-options env)
        start-time (when point-in-time-view? (dt/now))]
    (-> env
      (uism/store :route-params (:route-params event-data))
      (uism/store :point-in-time start-time)
      (report/initialize-parameters)
      (cond->
        run-on-mount? (start-load)
        (not run-on-mount?) (uism/activate :state/gathering-parameters)))))

(defn refresh [env]
  (-> env
    (uism/dissoc-aliased :page-cache :total-results)
    (uism/assoc-aliased :point-in-time (dt/now))
    (start-load)))

(defstatemachine machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {:parameters    [:actor/report :ui/parameters]
    :sort-params   [:actor/report :ui/parameters ::report/sort]
    :sort-by       [:actor/report :ui/parameters ::report/sort :sort-by]
    :ascending?    [:actor/report :ui/parameters ::report/sort :ascending?]
    :raw-rows      [:actor/report :ui/loaded-data]
    :current-rows  [:actor/report :ui/current-rows]
    :current-page  [:actor/report :ui/parameters ::report/current-page]
    :selected-row  [:actor/report :ui/parameters ::report/selected-row]
    :page-count    [:actor/report :ui/page-count]
    :point-in-time [:actor/report :ui/point-in-time]        ; Time at which the report started
    :total-results [:actor/report :ui/total-results]        ; Count of the total possible results
    :loaded-page   [:actor/report :ui/cache :loaded-page]   ; map from page number (from 1) to rows of that page (vector of idents when normalized)
    :page-cache    [:actor/report :ui/cache :page-cache]    ; map from page number (from 1) to rows of that page (vector of idents when normalized)
    :busy?         [:actor/report :ui/busy?]}

   ::uism/states
   {:initial
    {::uism/handler start}

    :state/loading
    {::uism/events
     {:event/page-loaded {::uism/handler process-loaded-page}
      :event/failed      {::uism/handler (fn [env] (log/error "Report failed to load.")
                                           (uism/activate env :state/gathering-parameters))}}}

    :state/gathering-parameters
    {::uism/events
     {:event/goto-page         {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                 (let [{:keys [page]} event-data]
                                                   (goto-page env page)))}
      :event/next-page         {::uism/handler (fn [env]
                                                 (let [page (uism/alias-value env :current-page)]
                                                   (goto-page env (inc (max 1 page)))))}

      :event/prior-page        {::uism/handler (fn [env]
                                                 (let [page (uism/alias-value env :current-page)]
                                                   (goto-page env (dec (max 2 page)))))}

      :event/select-row        {::uism/handler (fn [{::uism/keys [app event-data] :as env}]
                                                 (let [row               (:row event-data)
                                                       selected-row-path (report/route-params-path env ::selected-row)]
                                                   (when (nat-int? row)
                                                     (rad-routing/update-route-params! app assoc-in selected-row-path row))
                                                   (uism/assoc-aliased env :selected-row row)))}

      :event/sort              {::uism/handler (fn [{::uism/keys [app event-data] :as env}]
                                                 (if-let [{::attr/keys [qualified-key]} (get event-data ::attr/attribute)]
                                                   (let [sort-by    (uism/alias-value env :sort-by)
                                                         sort-path  (report/route-params-path env ::sort)
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
                                                       (refresh)))
                                                   env))}

      :event/filter            {::uism/handler refresh}

      :event/set-ui-parameters {::uism/handler report/initialize-parameters}

      :event/run               {::uism/handler refresh}

      :event/resume            {::uism/handler handle-resume-report}}}}})

(defn raw-loaded-item-count
  "Returns the count of raw loaded items when given the props of the report. Can be used for progress reporting of
   the load/refresh"
  [report-instance]
  (let [state-map (app/current-state report-instance)
        path      (conj (comp/get-ident report-instance) :ui/loaded-data)]
    (count (get-in state-map path))))

