(ns com.fulcrologic.rad.report
  #?(:cljs (:require-macros com.fulcrologic.rad.report))
  (:require
    #?(:clj [cljs.analyzer :as ana])
    [com.fulcrologic.rad.options-util :refer [?! debounce]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [clojure.spec.alpha :as s]))

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

(defn render-parameter-input [report-instance parameter-key]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        {::keys [parameters]} (comp/component-options report-instance)
        input-type   (get-in parameters [parameter-key :type])
        input-style  (get-in parameters [parameter-key :style] :default)
        style->input (some-> runtime-atom deref ::rad/controls ::parameter-type->style->input (get input-type))
        input        (or (get style->input input-style) (get style->input :default))]
    (if input
      (input report-instance parameter-key)
      (do
        (log/error "No renderer installed to support parameter " parameter-key "with type/style" input-type input-style)
        nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def global-events {})

(defn exit-report [{::uism/keys [fulcro-app] :as env}]
  (let [Report       (uism/actor-class env :actor/report)
        ;; TODO: Rename cancel-route to common RAD ns
        cancel-route (some-> Report comp/component-options ::cancel-route)]
    (if cancel-route
      (dr/change-route fulcro-app (or cancel-route []))
      (log/error "Don't know where to route on cancel. Add ::report/cancel-route to your form."))
    (uism/exit env)))

(defn report-options
  "Returns the report options from the current report actor."
  [env & k-or-ks]
  (apply comp/component-options (uism/actor-class env :actor/report) k-or-ks))

(defn initialize-parameters [env]
  (let [report-ident       (uism/actor->ident env :actor/report)
        initial-parameters (?! (report-options env ::initial-parameters))]
    (cond-> env
      report-ident (uism/apply-action update report-ident merge initial-parameters))))

(defn load-report! [{::uism/keys [fulcro-app state-map event-data] :as env}]
  (let [Report         (uism/actor-class env :actor/report)
        report-ident   (uism/actor->ident env :actor/report)
        {::keys [parameters BodyItem source-attribute]} (comp/component-options Report)
        desired-params (some-> parameters ?! keys set)
        current-params (merge (select-keys (get-in state-map report-ident) desired-params) event-data)
        path           (conj (comp/get-ident Report {}) source-attribute)]
    (log/debug "Loading report" source-attribute (comp/component-name Report) (comp/component-name BodyItem))
    (uism/load env source-attribute BodyItem {:params current-params
                                              :marker report-ident
                                              :target path})))

(defstatemachine report-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [event-data]} env
                            run-on-mount? (report-options env ::run-on-mount?)]
                        (-> env
                          (uism/store :route-params (:route-params event-data))
                          (initialize-parameters)
                          (cond-> run-on-mount? (load-report!))
                          (uism/activate :state/gathering-parameters))))}

    :state/gathering-parameters
    (merge global-events
      {::uism/events
       {:event/parameter-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
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

(defn report-will-enter [app route-params report-class]
  (let [report-ident (comp/get-ident report-class {})]
    (dr/route-deferred report-ident
      (fn []
        (uism/begin! app report-machine report-ident {:actor/report report-class}
          {:route-params route-params})
        (comp/transact! app [(dr/target-ready {:target report-ident})])))))

(defn report-will-leave [_ _] true)

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
           {::keys [BodyItem edit-form columns row-pk row-actions source-attribute route parameters] :as options} (first args)]
       (req! &env sym options ::columns #(every? symbol? %))
       (req! &env sym options ::row-pk #(symbol? %))
       (req! &env sym options ::source-attribute keyword?)
       (let
         [generated-row-sym (symbol (str (name sym) "-Row"))
          ItemClass         (or BodyItem generated-row-sym)
          subquery          `(comp/get-query ~ItemClass)
          query             (into [{source-attribute subquery} [df/marker-table '(quote _)]] (keys parameters))
          options           (assoc options
                              :route-segment (if (vector? route) route [route])
                              :will-enter `(fn [app# route-params#] (report-will-enter app# route-params# ~sym))
                              :will-leave `report-will-leave
                              ::BodyItem ItemClass
                              :query query
                              :initial-state {source-attribute {}}
                              :ident (list 'fn [] [:component/id (keyword sym)]))
          body              (if (seq (rest args))
                              (rest args)
                              [`(render-layout ~this-sym)])
          row-query         (list 'fn [] `(into [(::attr/qualified-key ~row-pk)] (map ::attr/qualified-key) ~columns))
          props-sym         (gensym "props")
          row-ident         (list 'fn []
                              `(let [k# (::attr/qualified-key ~row-pk)]
                                 [k# (get ~props-sym k#)]))
          row-actions       (or row-actions [])
          body-options      (cond-> {:query        row-query
                                     :ident        row-ident
                                     ::row-actions row-actions
                                     ::columns     columns}
                              edit-form (assoc ::edit-form edit-form))
          defs              (if-not BodyItem
                              [`(comp/defsc ~generated-row-sym [this# ~props-sym computed#]
                                  ~body-options
                                  (render-row (:report-instance computed#) ~generated-row-sym ~props-sym))
                               `(comp/defsc ~sym ~arglist ~options ~@body)]
                              [`(comp/defsc ~sym ~arglist ~options ~@body)])]
         `(do
            ~@defs)))))

(def reload!
  (debounce
    (fn [report-instance]
      (uism/trigger! report-instance (comp/get-ident report-instance) :event/run))
    100))

(defn set-parameter!
  "Set the given parameter on the report, possibly triggering an auto-refresh."
  [report-instance parameter-name new-value]
  (let [reload? (comp/component-options report-instance ::run-on-parameter-change?)]
    (comp/transact! report-instance `[(m/set-props ~{parameter-name new-value})])
    (when reload?
      (reload! report-instance))))
