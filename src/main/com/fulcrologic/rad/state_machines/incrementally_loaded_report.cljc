(ns com.fulcrologic.rad.state-machines.incrementally-loaded-report
  "A Report state machine that will load the data in pages to prevent network timeouts for large result
   sets. This requires a resolver that can accept :report/offset and :report/limit parameters and that returns

   ```
   {:report/next-offset n
    :report/results data}
   ```

   where `n` is the next offset to use to get the next page of data, and `data` is a vector
   of the results in the current fetch.

   See incrementally-loaded-report-options for supported additional report options.
   "
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.raw.application :as app]
    [com.fulcrologic.fulcro.raw.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.rad.routing.history :as history]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [taoensso.timbre :as log]))

(defn start-load [env]
  (let [Report         (uism/actor-class env :actor/report)
        report-ident   (uism/actor->ident env :actor/report)
        {::report/keys [source-attribute load-options]
         ::keys        [chunk-size]} (comp/component-options Report)
        load-options   (?! load-options env)
        current-params (assoc (report/current-control-parameters env)
                         :report/offset 0
                         :report/limit (or chunk-size 100))
        page-path      (uism/resolve-alias env :loaded-page)]
    (-> env
      (uism/assoc-aliased :raw-rows [])
      (uism/load source-attribute nil (merge
                                        {:params            current-params
                                         ::uism/ok-event    :event/page-loaded
                                         ::uism/error-event :event/failed
                                         :marker            report-ident
                                         :target            page-path}
                                        load-options))
      (uism/activate :state/loading))))

(defn finalize-report [{::uism/keys [state-map] :as env}]
  (let [Report     (uism/actor-class env :actor/report)
        {::report/keys [row-pk report-loaded]} (comp/component-options Report)
        table-name (::attr/qualified-key row-pk)]
    (-> env
      (report/preprocess-raw-result)
      (report/filter-rows)
      (report/sort-rows)
      (report/populate-current-page)
      (uism/store :last-load-time (inst-ms (dt/now)))
      (uism/store :raw-items-in-table (count (keys (get state-map table-name))))
      (uism/activate :state/gathering-parameters)
      (cond-> report-loaded report-loaded))))

(defn process-loaded-page [env]
  (let [Report         (uism/actor-class env :actor/report)
        report-ident   (uism/actor->ident env :actor/report)
        {::report/keys [BodyItem source-attribute load-options]
         ::keys        [chunk-size]} (comp/component-options Report)
        load-options   (?! load-options env)
        {:report/keys [next-offset results]} (uism/alias-value env :loaded-page)
        page-path      (uism/resolve-alias env :loaded-page)
        target-path    (uism/resolve-alias env :raw-rows)
        current-params (assoc
                         (report/current-control-parameters env)
                         :report/offset next-offset
                         :report/limit (or chunk-size 100))
        more?          (and (number? next-offset) (pos? next-offset))
        append-results (fn [state-map]
                         (reduce
                           (fn [s item] (merge/merge-component s BodyItem item :append target-path))
                           state-map
                           results))]
    (-> env
      (uism/apply-action append-results)
      (cond->
        more? (uism/load source-attribute nil (merge
                                                {:params            current-params
                                                 ::uism/ok-event    :event/page-loaded
                                                 ::uism/error-event :event/failed
                                                 :marker            report-ident
                                                 :target            page-path}
                                                load-options))
        (not more?) (uism/trigger report-ident :event/loaded)))))

(defn handle-resume-report
  "Internal state machine implementation. Called on :event/resumt to do the steps to resume an already running report
   that has just been re-mounted."
  [{::uism/keys [state-map] :as env}]
  (let [env                 (report/initialize-parameters env)
        Report              (uism/actor-class env :actor/report)
        {::report/keys [load-cache-seconds
                        load-cache-expired?
                        row-pk]} (comp/component-options Report)
        now-ms              (inst-ms (dt/now))
        last-load-time      (uism/retrieve env :last-load-time)
        last-table-count    (uism/retrieve env :raw-items-in-table)
        cache-expiration-ms (* 1000 (or load-cache-seconds 0))
        table-name          (::attr/qualified-key row-pk)
        current-table-count (count (keys (get state-map table-name)))
        cache-looks-stale?  (or
                              (nil? last-load-time)
                              (not= current-table-count last-table-count)
                              (< last-load-time (- now-ms cache-expiration-ms)))
        user-cache-expired? (?! load-cache-expired? env cache-looks-stale?)
        cache-expired?      (if (boolean user-cache-expired?)
                              user-cache-expired?
                              cache-looks-stale?)]
    (if cache-expired?
      (start-load env)
      (report/handle-filter-event env))))

(defn start [env]
  (let [{::uism/keys [fulcro-app event-data]} env
        {::report/keys [run-on-mount?]} (report/report-options env)
        page-path    (report/route-params-path env ::current-page)
        desired-page (-> (history/current-route fulcro-app)
                       :params
                       (get-in page-path))
        run-now?     (or desired-page run-on-mount?)]
    (-> env
      (uism/store :route-params (:route-params event-data))
      (cond->
        (nil? desired-page) (uism/assoc-aliased :current-page 1))
      (report/initialize-parameters)
      (cond->
        run-now? (start-load)
        (not run-now?) (uism/activate :state/gathering-parameters)))))

(defstatemachine incrementally-loaded-machine
  (-> report/report-machine
    (assoc-in [::uism/aliases :loaded-page] [:actor/report :ui/incremental-page])
    (assoc-in [::uism/states :initial ::uism/handler] start)
    (assoc-in [::uism/states :state/loading ::uism/events :event/page-loaded ::uism/handler] process-loaded-page)
    (assoc-in [::uism/states :state/loading ::uism/events :event/loaded ::uism/handler] finalize-report)
    (assoc-in [::uism/states :state/gathering-parameters ::uism/events :event/run ::uism/handler] start-load)
    (assoc-in [::uism/states :state/gathering-parameters ::uism/events :event/resume ::uism/handler] handle-resume-report)))

(defn raw-loaded-item-count
  "Returns the count of raw loaded items when given the props of the report. Can be used for progress reporting of
   the load/refresh/"
  [report-instance]
  (let [state-map (app/current-state report-instance)
        path      (conj (comp/get-ident report-instance) :ui/loaded-data)]
    (count (get-in state-map path))))
