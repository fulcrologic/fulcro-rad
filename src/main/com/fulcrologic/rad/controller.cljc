(ns com.fulcrologic.rad.controller
  (:require
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
  [{::uism/keys [event-data state-map] :as env}]
  ;; TODO: Calculate providers from attributes that are on the query of the given route
  (log/spy :info event-data)
  (let [{:keys [target-route]} event-data]
    (if (empty? target-route)
      #{}
      (let [controller  (uism/actor-class env :actor/crud-controller)
            target      (log/spy :info (dr/route-target controller target-route))
            ;; TODO: Nested form permissions
            attributes  (log/spy :info (some-> target comp/component-options ::attr/attributes))
            authorities (log/spy :info (into #{}
                                         (keep ::attr/authority)
                                         attributes))]
        authorities))))

(defn load-data-for-route [env]
  env)

(defn initialize-route-data [env]
  (-> env
    (load-data-for-route)
    (uism/activate :state/routing)))

(defn prepare-for-route [{::uism/keys [fulcro-app] :as env}]
  (let [necessary-authorities (authorities-required-for-route env)
        current-authorities   (auth/verified-authorities fulcro-app)
        missing-authorities   (set/difference necessary-authorities current-authorities)]
    (if (empty? missing-authorities)
      (initialize-route-data env)
      (-> env
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
     {:event/authenticated {::uism/handler prepare-for-route}}}

    :state/routing
    {::uism/handler (fn [env] env)}

    :state/idle
    {::uism/events
     {:event/list {::uism/handler (fn [env] env)}
      }}}})

(defn start! [app {:keys [auth
                          controller
                          target-route]}]
  (uism/begin! app auth/auth-machine auth/machine-id
    {:actor/controller auth})

  (uism/begin! app crud-machine machine-id
    {:actor/auth-controller auth
     :actor/crud-controller controller}
    {:target-route target-route}))
