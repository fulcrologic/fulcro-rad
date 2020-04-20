(ns com.fulcrologic.rad.authorization
  "A basic implementation of an user/password auth scheme, with a state machine and support for a pluggable UI
  actor. Very Alpha."
  #?(:cljs (:require-macros com.fulcrologic.rad.authorization))
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [com.wsscode.pathom.core :as p]
    [com.fulcrologic.guardrails.core :refer [>defn => ? >def]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.rad.attributes :as attr :refer [new-attribute]]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.type-support.cache-a-bools :as cb]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def machine-id ::auth-machine)

(defsc Session [_ _]
  {:query [::provider
           ::status
           '*]
   :ident [::authorization ::provider]})

(defn verified-authorities
  "Returns the providers that are currently authenticated."
  [app-ish]
  (let [state-map (app/current-state app-ish)
        env       (uism/state-machine-env state-map machine-id)]
    (uism/retrieve env :authenticated)))

(defn authenticate!
  "Start an authentication sequence for the given provider, and report results to the source-machine-id.

  Sends and :event/authenticated or :event/authentication-failed to that source machine when done."
  [app-ish provider source-machine-id]
  (uism/trigger! app-ish machine-id :event/authenticate {:source-machine-id source-machine-id
                                                         :provider          provider}))

(defn logout!
  "Force logout on the given provider."
  [app-ish provider]
  (uism/trigger! app-ish machine-id :event/logout {:provider provider}))

(defn authenticate
  "Start an authentication sequence for the given provider, and report results to the source-machine-id. This version
   is identical to authenticate!, but accepts any state machine env as the first parameter.

  Sends and :event/authenticated or :event/authentication-failed to that source machine when done."
  [any-sm-env provider source-machine-id]
  (uism/trigger any-sm-env machine-id :event/authenticate {:source-machine-id source-machine-id
                                                           :provider          provider}))

(defn logged-in!
  "Tell the auth system that the given provider succeeded."
  [app-ish provider]
  (uism/trigger! app-ish ::auth-machine :event/logged-in {:provider provider}))

(defn failed!
  "Tell the auth system that the given provider failed."
  [app-ish provider]
  (uism/trigger! app-ish ::auth-machine :event/failed {:provider provider}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PRIVATE API and STATE MACHINE DEF
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- set-authentication-actor
  "Change the actor that acts as the UI for gathering the credentials based on env provider"
  [env provider]
  ;; TODO: swap actors for authentication provider
  (let [controller  (uism/actor-class env :actor/controller)
        provider-ui (some-> controller ::authentication-providers (get provider))]
    (cond-> env
      provider-ui (uism/reset-actor-ident :actor/auth-dialog (uism/with-actor-class
                                                               (comp/get-ident provider-ui {})
                                                               provider-ui)))))

(defn- -authenticate
  "Start the process of authenticating for a given provider"
  [{::uism/keys [event-data] :as env}]
  (let [{:keys [provider source-machine-id]} event-data]
    (-> env
      (uism/store :source-machine-id source-machine-id)
      (set-authentication-actor provider)
      (uism/assoc-aliased :username "")
      (uism/assoc-aliased :password "")
      (uism/activate :state/gathering-credentials))))

(defn- -reply-to-initiator [env event]
  (let [source-machine-id (uism/retrieve env :source-machine-id)]
    (log/info "Sending" event "to" source-machine-id)
    (cond-> (uism/store env :source-machine-id nil)
      (and source-machine-id (not= :none source-machine-id)) (uism/trigger source-machine-id event))))

(defn- -add-authenticated-provider [env p]
  (let [current (uism/retrieve env :authenticated)]
    (uism/store env :authenticated (set/union (or current #{}) #{p}))))

(defn- remove-authenticated-provider [env p]
  (let [current (uism/retrieve env :authenticated)]
    (uism/store env :authenticated (set/difference (or current #{}) #{p}))))

(defn- logged-in [env]
  (let [provider          (-> env ::uism/event-data :provider)
        expected-provider (uism/retrieve env :provider)]
    (when (not= provider expected-provider)
      (log/error "Provider mismatch" provider expected-provider))
    (-> env
      (-add-authenticated-provider (or provider expected-provider))
      (-reply-to-initiator :event/authenticated)
      (uism/activate :state/idle))))

(defn- logged-out [env]
  (let [provider          (-> env ::uism/event-data :provider)
        expected-provider (uism/retrieve env :provider)]
    (when (not= provider expected-provider)
      (log/error "Provider mismatch" provider expected-provider))
    (-> env
      (remove-authenticated-provider (or provider expected-provider))
      (-reply-to-initiator :event/authentication-failed)
      (uism/activate :state/idle))))

(defn- log-out [env]
  (let [provider          (-> env ::uism/event-data :provider)
        expected-provider (uism/retrieve env :provider)]
    (-> env
      (remove-authenticated-provider (or provider expected-provider))
      (uism/apply-action assoc-in [::authorization provider] {::provider provider
                                                              ::status   :logged-out})
      (uism/activate :state/idle))))

(def global-events
  {:event/logout          {::uism/handler log-out}
   :event/logged-in       {::uism/handler logged-in}
   :event/failed          {::uism/handler logged-out}
   :event/session-checked {::uism/handler (fn [{::uism/keys [state-map event-data] :as env}]
                                            (let [provider (:provider event-data)
                                                  status   (get-in state-map [::authorization provider ::status])
                                                  ok?      (= :success status)]
                                              (cond-> env
                                                ok? (-add-authenticated-provider provider)
                                                (not ok?) (remove-authenticated-provider provider))))}
   :event/authenticate    {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                            (let [{:keys [provider]} event-data
                                                  authenticated (uism/retrieve env :authenticated)]
                                              (log/debug "Checking for authentication ")
                                              (if (contains? authenticated provider)
                                                (do
                                                  (log/debug "Already authenticated")
                                                  (-reply-to-initiator env :event/authenticated))
                                                (-> env
                                                  (uism/store :provider provider)
                                                  (-authenticate)))))}})

(defstatemachine auth-machine
  {::uism/aliases
   {:username [:actor/auth-dialog :ui/username]
    :password [:actor/auth-dialog :ui/password]
    :status   [:actor/session]}

   ::uism/states
   {:initial                     {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}]
                                                   (let [actors (keys (uism/asm-value env ::uism/actor->ident))]
                                                     ;; Doing this as a side-effect, since we may need to handle the mutation via
                                                     ;; client request instead of server remote
                                                     (doseq [actor actors
                                                             :let [m (some-> env (uism/actor-class actor) comp/component-options
                                                                       ::check-session)]]
                                                       (when m
                                                         (comp/transact! fulcro-app `[(~m)])))
                                                     (-> env
                                                       ;; TODO: Ping auth providers to see if we already have auth. These could just sent `logged-in`
                                                       ;; events back to this machine async, and that will add them to the list of valid providers.
                                                       (uism/store :authenticated #{})
                                                       (uism/activate :state/idle))))}

    :state/idle                  {::uism/events global-events}

    :state/gathering-credentials {::uism/events global-events}}})

(defn start!
  "Start the authentication system and configure it to use the provided UI components (with options).

  `authority-ui-roots` - A vector of UI components with singleton idents. Each must have
  a unique ::auth/provider (the name of the authority) and ::auth/check-session (a mutation to run that
  should return a Session from a remote that has looked for an existing session.)"
  [app authority-ui-roots]
  (let [actors (into {}
                 (keep (fn [c]
                         (let [{::keys [provider]} (comp/component-options c)]
                           (if provider
                             (do
                               (log/info "Installing auth UI for provider" provider)
                               [provider c])
                             (do
                               (log/error "Unable to add auth root. Missing ::auth/provider key on" (comp/component-name c))
                               nil)))))
                 authority-ui-roots)]
    (uism/begin! app auth-machine machine-id actors)))

(defn readable?
  [env a]
  (let [{::keys [permissions]} a]
    (boolean
      (or (nil? permissions)
        (and permissions (contains? (set (permissions env)) :read))))))

(defn redact
  "Creates a post-processing plugin that redacts attributes that are marked as non-readable"
  [{attr-map ::attr/key->attribute
    :as      env} query-result]
  (p/transduce-maps (map (fn [[k v]]
                           (let [a (get attr-map k)]
                             (if (readable? env a)
                               [k v]
                               [k ::REDACTED]))))
    query-result))

#?(:clj
   (defmacro defauthenticator [sym authority-map]
     (let [query         (into [:ui/auth-context [::uism/asm-id (quote '_)]]
                           (map (fn [k] `{~k (comp/get-query ~(get authority-map k))})
                             (keys authority-map)))
           initial-state (into {}
                           (map (fn [k] [k {}]))
                           (keys authority-map))
           ident-fn      (list 'fn [] [::id (keyword sym)])]
       `(defsc ~sym [~'this ~'props]
          {:query                     ~query
           ::authentication-providers ~authority-map
           :ident                     ~ident-fn
           :initial-state             ~initial-state}
          ;; TODO: Logic to choose the correct factory for the provider being used
          (let [~'state (uism/get-active-state ~'this machine-id)
                ~'authenticating? (= :state/gathering-credentials ~'state)
                {:keys [~'local]} ~'props
                ~'factory (comp/computed-factory ~(get authority-map :local))]
            (~'factory ~'local {:visible? ~'authenticating?}))))))

(>def ::action #{:read :write :execute})
(>def ::context map?)
(>def ::subject any?)
(>def ::action-map (s/keys :req [::action ::subject] :opt [::context]))

(>defn Read
  "A read action. Used with `can?`."
  ([subj context]
   [(s/or
      :sym qualified-keyword?
      :symset (s/coll-of qualified-keyword? :kind set?)) map? => ::action-map]
   (cond-> {::action  :read
            ::subject subj}
     context (assoc ::context context)))
  ([subj]
   [(s/or :sym qualified-keyword? :symset (s/coll-of qualified-keyword? :kind set?)) => ::action-map]
   (Read subj nil)))

(>defn Write
  "A write action. Used with `can?`."
  ([subj context]
   [(s/or :sym qualified-keyword? :symset (s/coll-of qualified-keyword? :kind set?)) map? => ::action-map]
   (cond-> {::action  :write
            ::subject subj}
     context (assoc ::context context)))
  ([subj]
   [(s/or :sym qualified-keyword? :symset (s/coll-of qualified-keyword? :kind set?)) => ::action-map]
   (Write subj nil)))

(>defn Execute
  "A write action. Used with `can?`."
  ([subj context]
   [(s/or :sym qualified-symbol? :symset (s/coll-of qualified-symbol? :kind set?)) map? => ::action-map]
   (cond-> {::action  :execute
            ::subject subj}
     context (assoc ::context context)))
  ([subj]
   [(s/or :sym qualified-symbol? :symset (s/coll-of qualified-symbol? :kind set?)) => ::action-map]
   (Execute subj nil)))

#?(:cljs
   (defn can?
     "CLJS authorization check. Must be passed a component instance or the app, and an action map. The action
     map should be generated using one of the action generators `read`, `write`, or `execute`.

     Returns a cache-a-bool"
     [this-or-app action-map]
     cb/CT)
   :clj
   (defn can?
     "CLJ authorization check. Must be passed the current pathom env (i.e. mutation env). The action
     map should be generated using one of the action generators `read`, `write`, or `execute`."
     [env action-map] cb/CT))
