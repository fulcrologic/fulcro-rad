(ns com.fulcrologic.rad.controller
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.application :as app]
    [clojure.set :as set]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as comp]))

(defmulti -start-io! (fn [_ ComponentClass _]
                       (some-> ComponentClass (comp/component-options ::rad/type))))
(defmethod -start-io! :default [env ComponentClass options]
  (log/info "No Controller I/O defined for components of type" (comp/component-options ComponentClass ::rad/type)
    "(did you forget to require it?). This can be ignored if that component type does no I/O."))

(>defn start-io!
  [env ComponentClass {::rad/keys [target-route] :as options}]
  [::uism/env comp/component-class? (s/keys :req [::rad/target-route ::id]) => ::uism/env]
  (-> env
    (uism/store ::rad/target-route target-route)
    (-start-io! ComponentClass options)))

(defmulti -desired-attributes (fn [TargetClass]
                                (some-> TargetClass (comp/component-options ::rad/type))))

(defmethod -desired-attributes :default [c]
  (log/warn "No implementation found for `-desired-attributes` for the target component of the route " (comp/component-name c))
  [])

(>defn io-complete!
  "Custom components should call this to indicate that they are done with I/O, allowing the
   controller to complete the route."
  [app {::keys     [id]
        ::rad/keys [target-route]}]
  [::app/app (s/keys :req [::rad/target-route ::id]) => any?]
  (log/debug "Controller notified that target route I/O is complete." target-route)
  (uism/trigger! app id :event/route-loaded {::rad/target-route target-route}))

(>defn desired-attributes
  "Get the data attributes that are used by the component. The controller uses this to
   interact with the authorization model."
  [c]
  [comp/component-class? => (s/every ::attr/attribute :kind vector?)]
  (-desired-attributes c))

(defn machine-id [env] (::uism/asm-id env))

(defn does-io? [component-class]
  (boolean (some-> component-class (comp/component-options ::rad/io?))))

(defn authorities-required-for-route
  "Returns a set of providers that are required to properly render the given route"
  [{::uism/keys [event-data] :as env}]
  ;; TODO: Calculate providers from attributes that are on the query of the given route
  (let [{::rad/keys [target-route]} event-data]
    (if (empty? target-route)
      #{}
      (let [router      (uism/actor-class env :actor/router)
            {:keys [target]} (dr/route-target router target-route)
            attributes  (some-> target desired-attributes)
            authorities (into #{}
                          (keep ::auth/authority)
                          attributes)]
        authorities))))

(defn activate-route [{::uism/keys [fulcro-app] :as env} target-route]
  (dr/change-route fulcro-app target-route)
  (-> env
    (uism/store ::rad/target-route nil)
    (uism/activate :state/idle)))

(defn- initialize-route-data [{::uism/keys [event-data] :as env}]
  (log/debug "Initializing route data")
  (let [target-route (or (::rad/target-route event-data)
                       (uism/retrieve env ::rad/target-route))
        options      (assoc event-data ::rad/target-route target-route
                                       ::id (::uism/asm-id env))]
    (if (empty? target-route)
      (let [{::keys [home-page]} (uism/retrieve env :config)]
        (log/debug "No target route. Using home page.")
        (cond-> (uism/activate env :state/idle)
          (seq home-page) (activate-route home-page)))
      ;; The main thing we end up needing to know is the target class, and from
      ;; that we can pull the information we need to do the remaining steps.
      ;; I.e. Load, create a new, etc.
      ;; TODO: Consider that a route might contain *layers* that need I/O...not just the ultimate (leaf) target
      (let [router (uism/actor-class env :actor/router)
            {:keys [target]} (dr/route-target router target-route)]
        (if (does-io? target)
          (-> env
            (start-io! target options)
            ;; TODO: In case I/O fails to come back
            #_(uism/set-timeout! :event/io-faled 2000))
          (activate-route env target-route))))))

(defn prepare-for-route [{::uism/keys [fulcro-app event-data] :as env}]
  (log/debug "Preparing for route")
  (let [target-route          (::rad/target-route event-data)
        necessary-authorities (authorities-required-for-route env)
        current-authorities   (auth/verified-authorities fulcro-app)
        missing-authorities   (set/difference necessary-authorities current-authorities)]
    ;; TODO: cancel any in-progress route loading (could be a route while a route was loading)
    (if (empty? missing-authorities)
      (initialize-route-data env)
      (-> env
        (uism/store ::rad/target-route target-route)
        (auth/authenticate (first missing-authorities) (machine-id env))
        (uism/activate :state/gathering-permissions)))))

(defstatemachine central-controller
  {::uism/aliases
   {}

   ::uism/states
   {:initial
    {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                      (-> env
                        (uism/store :config event-data)
                        (prepare-for-route)))}

    :state/gathering-permissions
    {::uism/events
     {:event/route         {::uism/handler prepare-for-route}
      :event/authenticated {::uism/handler prepare-for-route}}}

    ;; TODO: Route timeout
    :state/routing
    {::uism/events
     {:event/route        {::uism/handler prepare-for-route}
      :event/route-loaded {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                            (let [loaded-route (get event-data ::rad/target-route)
                                                  target-route (uism/retrieve env ::rad/target-route)]
                                              (if (= loaded-route target-route)
                                                (activate-route env target-route)
                                                (do
                                                  (log/warn "Load notification from a route we don't care about" loaded-route)
                                                  env))))}}}

    :state/idle
    {::uism/events
     {:event/route {::uism/handler prepare-for-route}}}}})

(defn start!
  "Start the central controller and auth system."
  [app {::rad/keys [target-route]
        ::keys     [id router home-page]}]
  (uism/begin! app central-controller id
    {:actor/router router}
    {::rad/target-route target-route
     ::id               id
     ::home-page        (or home-page ["index"])}))

(defn route-to!
  "Tell the controller to route the application to the given path."
  [app machine-id path]
  (uism/trigger! app machine-id :event/route {::rad/target-route path}))
