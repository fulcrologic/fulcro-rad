(ns com.fulcrologic.rad.controller
  (:require
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [clojure.set :as set]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as comp]))

(def machine-id ::crud-machine)

(defn authorities-required-for-route
  "Returns a set of providers that are required to properly render the given route"
  [{::uism/keys [event-data] :as env}]
  ;; TODO: Calculate providers from attributes that are on the query of the given route
  (log/spy :info event-data)
  (let [{::rad/keys [target-route]} event-data]
    (if (empty? target-route)
      #{}
      (let [controller  (uism/actor-class env :actor/crud-controller)
            {:keys [target]} (log/spy :info (dr/route-target controller target-route))
            ;; TODO: Nested form permissions
            attributes  (log/spy :info (some-> target (comp/component-options ::attr/attributes)))
            authorities (log/spy :info (into #{}
                                         (keep ::auth/authority)
                                         attributes))]
        authorities))))

(defn- activate-route [{::uism/keys [fulcro-app] :as env} target-route]
  (dr/change-route fulcro-app target-route)
  (-> env
    (uism/store :com.fulcrologic.rad/target-route nil)
    (uism/activate :state/idle)))

(defn- initialize-route-data [{::uism/keys [fulcro-app event-data] :as env}]
  (log/info "Initializing route data")
  (let [{::rad/keys [target-route]} event-data
        target-route (log/spy :info (or target-route (uism/retrieve env ::rad/target-route)))]
    (if (empty? target-route)
      (uism/activate env :state/idle)
      ;; The main thing we end up needing to know is the target class, and from
      ;; that we can pull the information we need to do the remaining steps.
      ;; I.e. Load, create a new, etc.
      (let [controller (uism/actor-class env :actor/crud-controller)
            {:keys [target]} (dr/route-target controller target-route)
            prepare!   (comp/component-options target :prepare-route!)]
        (if prepare!
          (do
            ;; TODO: Split off just the correct number of elements that represent the rou
            (prepare! fulcro-app {::rad/target-route target-route})
            (-> env
              (uism/store ::rad/target-route target-route)
              (uism/activate :state/routing)))
          (activate-route env target-route))))))

(defn prepare-for-route [{::uism/keys [fulcro-app event-data] :as env}]
  (log/info "Preparing for route")
  (let [target-route          (::rad/target-route event-data)
        necessary-authorities (authorities-required-for-route env)
        current-authorities   (auth/verified-authorities fulcro-app)
        missing-authorities   (set/difference necessary-authorities current-authorities)]
    ;; TODO: cancel any in-progress route loading (could be a route while a route was loading)
    (if (empty? missing-authorities)
      (initialize-route-data env)
      (-> env
        (uism/store ::rad/target-route target-route)
        (auth/authenticate (first missing-authorities) machine-id)
        (uism/activate :state/gathering-permissions)))))

(defstatemachine crud-machine
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

    :state/routing
    {::uism/events
     {:event/route        {::uism/handler prepare-for-route}
      :event/route-loaded {::uism/handler (fn [{::uism/keys [fulcro-app event-data] :as env}]
                                            (let [loaded-route (log/spy :debug (get event-data ::rad/target-route))
                                                  target-route (log/spy :debug (uism/retrieve env ::rad/target-route))]
                                              (if (= loaded-route target-route)
                                                (activate-route env target-route)
                                                env)))}}}

    :state/idle
    {::uism/events
     {:event/route {::uism/handler prepare-for-route}
      }}}})

(defn start! [app {::rad/keys [target-route]
                   :keys      [auth controller]}]
  (uism/begin! app auth/auth-machine auth/machine-id
    {:actor/controller auth})

  (uism/begin! app crud-machine machine-id
    {:actor/auth-controller auth
     :actor/crud-controller controller}
    {::rad/target-route target-route}))

(defn route-to! [app path]
  (uism/trigger! app machine-id :event/route {::rad/target-route path}))

(defn io-complete!
  "Notify the controller that the I/O is done for a given route, and that routing can continue."
  [app target-route]
  (log/info "Controller notified that target route I/O is complete." target-route)
  (uism/trigger! app machine-id :event/route-loaded {::rad/target-route target-route}))
