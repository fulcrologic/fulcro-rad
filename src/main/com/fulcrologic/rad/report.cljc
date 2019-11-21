(ns com.fulcrologic.rad.report
  #?(:cljs (:require-macros com.fulcrologic.rad.report))
  (:require
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.controller :as controller :refer [io-complete!]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti render-layout (fn [this] (-> this comp/component-options ::layout)))
(defmulti render-parameter-input (fn [this parameter-key]
                                   (-> this comp/component-options ::parameters (get parameter-key))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-report! [app TargetReportClass parameters]
  (let [{::keys [BodyItem source-attribute]} (comp/component-options TargetReportClass)
        path (conj (comp/get-ident TargetReportClass {}) source-attribute)]
    (log/info "Loading report" source-attribute
      (comp/component-name TargetReportClass)
      (comp/component-name BodyItem))
    (df/load! app source-attribute BodyItem {:params parameters :target path})))

(def global-events {})

(defn exit-report [{::uism/keys [fulcro-app] :as env}]
  (let [Report       (uism/actor-class env :actor/report)
        id           (uism/retrieve env ::controller/id)
        ;; TODO: Rename cancel-route to common RAD ns
        cancel-route (some-> Report comp/component-options ::cancel-route)]
    (when-not cancel-route
      (log/error "Don't know where to route on cancel. Add ::report/cancel-route to your form."))
    ;; TODO: probably return to original route instead
    (controller/route-to! fulcro-app id (or cancel-route []))
    (uism/exit env)))

(defstatemachine report-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [fulcro-app event-data]} env
                            {::controller/keys [id]
                             ::keys            [action]} event-data
                            Report (uism/actor-class env :actor/report)]
                        (-> env
                          (uism/store ::action action)
                          (uism/store ::controller/id id)
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
        :event/run               {::uism/handler (fn [{::uism/keys [fulcro-app state-map event-data] :as env}]
                                                   (let [Report         (uism/actor-class env :actor/report)
                                                         report-ident   (uism/actor->ident env :actor/report)
                                                         desired-params (some-> Report comp/component-options ::parameters keys set)
                                                         current-params (merge
                                                                          (select-keys (log/spy :info (get-in state-map report-ident)) (log/spy :info desired-params))
                                                                          event-data)]
                                                     (load-report! fulcro-app Report current-params)
                                                     env))}}})}})

(defmethod controller/-start-io! ::rad/report
  [{::uism/keys [fulcro-app] :as env} TargetClass {::rad/keys [target-route] :as options}]
  (log/info "Starting Report " (comp/component-name TargetClass))
  (let [report-machine-id (comp/ident TargetClass {})
        event-data        (assoc options
                            ::id report-machine-id)]
    (uism/begin! fulcro-app report-machine report-machine-id
      {:actor/report (uism/with-actor-class report-machine-id TargetClass)}
      event-data)
    (controller/activate-route env target-route)))

(defn run-report!
  "Run a report with the current parameters"
  [this]
  (uism/trigger! this (comp/get-ident this) :event/run))

(defn req!
  ([sym options k pred?]
   (when-not (and (contains? options k) (pred? (get options k)))
     (throw (ex-info (str "defsc-report " sym " is missing or invalid option " k) {}))))
  ([sym options k]
   (when-not (contains? options k)
     (throw (ex-info (str "defsc-report " sym " is missing option " k) {})))))

(defn opt!
  [sym options k pred?]
  (when-not (pred? (get options k))
    (throw (ex-info (str "defsc-report " sym " has an invalid option " k) {}))))

#?(:clj
   (defmacro defsc-report
     "Define a report. Just like defsc, but you do not specify query/ident/etc.

     Instead:

     ::report/BodyItem FulcroClass?
     ::report/source-attribute keyword?
     ::report/route string?
     ::report/parameters (map-of ui-keyword? rad-data-type?)

     NOTE: Parameters MUST have a `ui` namespace, like `:ui/show-inactive?`.

     If you elide the body, one will be generated for you.
     "
     [sym arglist & args]
     (let [this-sym (first arglist)
           {::keys [BodyItem source-attribute route parameters] :as options} (first args)
           query    (into [{source-attribute `(comp/get-query ~BodyItem)}]
                      (keys parameters))
           options  (assoc options
                      ::rad/io? true
                      :route-segment [route]
                      ::rad/type ::rad/report
                      :query query
                      :ident (list 'fn [] [:component/id (keyword sym)]))
           body     (if (seq (rest args))
                      (rest args)
                      [`(render-layout ~this-sym)])]
       (req! sym options ::BodyItem)
       (req! sym options ::source-attribute keyword?)
       (req! sym options ::route string?)
       (opt! sym options ::parameters
         (fn [p] (and (map? p)
                   (every? #(and
                              (keyword? %)
                              (= "ui" (namespace %))) (keys p)))))
       `(comp/defsc ~sym ~arglist ~options ~@body))))

#_(macroexpand-1 '(defsc-report A [t p] {
                                         :com.fulcrologic.rad.report/BodyItem         Boo
                                         :com.fulcrologic.rad.report/source-attribute ::all
                                         :com.fulcrologic.rad.report/route            "accounts"
                                         :com.fulcrologic.rad.report/parameters       {:ui/active? boolean?}
                                         }
                    (dom/div :.hello "Hello")))