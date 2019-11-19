(ns com.fulcrologic.rad.controller
  (:require
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [clojure.set :as set]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as comp]))

(defn machine-id [env] (::uism/asm-id env))

(defn io-complete!
  "Notify the controller that the I/O is done for a given route, and that routing can continue."
  [app machine-id target-route]
  (log/debug "Controller notified that target route I/O is complete." target-route)
  (uism/trigger! app machine-id :event/route-loaded {::rad/target-route target-route}))

(defn authorities-required-for-route
  "Returns a set of providers that are required to properly render the given route"
  [{::uism/keys [event-data] :as env}]
  ;; TODO: Calculate providers from attributes that are on the query of the given route
  (log/spy :debug event-data)
  (let [{::rad/keys [target-route]} event-data]
    (if (empty? target-route)
      #{}
      (let [controller  (uism/actor-class env :actor/router)
            {:keys [target]} (log/spy :debug (dr/route-target controller target-route))
            ;; TODO: Nested form permissions
            attributes  (log/spy :debug (some-> target (comp/component-options ::attr/attributes)))
            authorities (log/spy :debug (into #{}
                                          (keep ::auth/authority)
                                          attributes))]
        authorities))))

(defn- activate-route [{::uism/keys [fulcro-app] :as env} target-route]
  (dr/change-route fulcro-app target-route)
  (-> env
    (uism/store :com.fulcrologic.rad/target-route nil)
    (uism/activate :state/idle)))

(defn- initialize-route-data [{::uism/keys [fulcro-app event-data] :as env}]
  (log/debug "Initializing route data")
  (let [{::rad/keys [target-route]} event-data
        target-route (log/spy :debug (or target-route (uism/retrieve env ::rad/target-route)))]
    (if (empty? target-route)
      (uism/activate env :state/idle)
      ;; The main thing we end up needing to know is the target class, and from
      ;; that we can pull the information we need to do the remaining steps.
      ;; I.e. Load, create a new, etc.
      (let [controller (uism/actor-class env :actor/router)
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

(defn load-list! [app TargetListClass {::keys     [id]
                                       ::rad/keys [target-route] :as options}]
  (let [{::rad/keys [ListItem source-attribute]} (comp/component-options TargetListClass)
        path (conj (comp/get-ident TargetListClass {}) source-attribute)]
    (log/info "Loading all accounts" source-attribute (comp/component-name ListItem))
    (df/load! app source-attribute ListItem
      (merge
        options
        {:post-action (fn [{:keys [app]}] (io-complete! app id target-route))
         :target      path}))))

(defn- start-edit! [app TargetClass {machine-id ::id
                                     ::rad/keys [id target-route]}]
  (log/debug "START EDIT" (comp/component-name TargetClass))
  (let [id-key (some-> TargetClass (comp/ident {}) first)
        ;; TODO: Coercion from string IDs to type of ID field
        id     (new-uuid id)]
    (df/load! app [id-key id] TargetClass
      {:post-action (fn [{:keys [state]}]
                      (log/debug "Marking the form complete")
                      (fns/swap!-> state
                        (assoc-in [id-key id :ui/new?] false)
                        (fs/mark-complete* [id-key id]))
                      (io-complete! app machine-id target-route))})))

;; TODO: ID generation pluggable? Use tempids?  NOTE: The controller has to generate the ID because the incoming
;; route is already determined
(defn- start-create! [app TargetClass {machine-id ::id
                                       ::rad/keys [target-route tempid]}]
  (log/debug "START CREATE" (comp/component-name TargetClass))
  (let [id-key        (some-> TargetClass (comp/ident {}) first)
        ident         [id-key tempid]
        fields        (comp/component-options TargetClass ::attr/attributes)
        ;; TODO: Make sure there is one and only one unique identity key on the form
        initial-value (into {:ui/new? true}
                        (keep (fn [{::attr/keys [qualified-key default-value unique]}]
                                (cond
                                  (= unique :identity) [qualified-key tempid]
                                  default-value [qualified-key default-value])))
                        fields)
        filled-fields (keys initial-value)
        tx            (into []
                        (map (fn [k]
                               (fs/mark-complete! {:entity-ident ident
                                                   :field        k})))
                        filled-fields)]
    (merge/merge-component! app TargetClass initial-value)
    (when (seq tx)
      (log/debug "Marking fields with default values complete")
      (comp/transact! app tx))
    (io-complete! app machine-id target-route)))

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

(defn start!
  "Start the central controller and auth system."
  [app {::rad/keys [target-route]
        ::keys     [id]}]
  (uism/begin! app central-controller id
    {}
    {::rad/target-route target-route}))

(defn route-to!
  "Tell the controller to route the application to the given path."
  [app machine-id path]
  (uism/trigger! app machine-id :event/route {::rad/target-route path}))

(defn new! [app form-class]
  (let [{:keys      [route-segment]
         ::rad/keys [type attributes]} (comp/component-options form-class)])
  )
