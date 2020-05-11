(ns com.fulcrologic.rad.authorization
  "Authentication and Authorization Support.

  WARNING: This part of RAD will not be standardized for some time. You will need to roll your own particular auth
  system, which can easily plug into the various hooks in Forms and Reports (e.g. field formatters, read-only checks, etc).
  If necessary you can simply make new wrapper macros around these if/when boilerplate becomes an issue. After all, forms
  and reports are just very thin macros around Fulcro's `defsc` that pre-fill component options for you.
  We hope the community will provide plugins for this, but generalized Auth is simply beyond the scope of RAD at this time due
  to time/resource constraints. Community members are highly encouraged to write their own auth plugin libraries for
  RAD, and we'll be glad to link to them on the main project pages.

  The implementation in this namespace is only partially written, and can satisfy some simple needs. The original idea
  was that each attribute would be under some authority (the owner of that data). These would be identified by
  user-generated keyword names.

  The state machine in this namespace was then meant to be used as a way to check which authorities had been authenticated
  against, so that incremental data access could be obtained over time as the user moved among features in the application.
  Each authority requires that the developer provide a component that can be used as the source of the mutation names
  and (optionally) UI for obtaining data from the user like passwords.

  This at least gives you the ability to control if the current user is at least *known* to the authority in question, but
  even this requires some integration with the network remotes of your application (are you using cookies? JWT? etc.).

  Also: *Real* Data access has to be ultimately controlled by the data owner. Therefore much of the real work needs to be
  done work at the network/database layer.

  Securing a real application needs the following (most of which RAD does NOT provide a default implementation for):

  * A way to auth against a server to establish a session.
  * A Pathom parser plugin that can check the query/mutation to see if it should be allowed.
  ** Optionally: granular query security, where individual attributes can be redacted. This would allow the same
  UI to be shown for users in different roles, and simple elision at the server can then easily be used to affect field/column
  visibility in the client.

  Some ideas around possible implementations can be found in this package's source directory as auth.adoc.
  "
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
    [taoensso.timbre :as log]))

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
  (if-let [source-machine-id (uism/retrieve env :source-machine-id)]
    (do
      (log/info "Sending" event "to" source-machine-id)
      (cond-> (uism/store env :source-machine-id nil)
        (and source-machine-id (not= :none source-machine-id)) (uism/trigger source-machine-id event)))
    env))

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

(defn- log-out [{::uism/keys [fulcro-app] :as env}]
  (let [provider          (-> env ::uism/event-data :provider)
        expected-provider (uism/retrieve env :provider)
        actors            (keys (uism/asm-value env ::uism/actor->ident))]
    (doseq [actor actors
            :let [m (some-> env (uism/actor-class actor) comp/component-options ::logout)]]
      (when m
        (comp/transact! fulcro-app `[(~m)])))
    (-> env
      (remove-authenticated-provider (or provider expected-provider))
      (uism/apply-action assoc-in [::authorization provider] {::provider provider
                                                              ::status   :logged-out})
      (uism/activate :state/idle))))

(def global-events
  {:event/logout          {::uism/handler log-out}
   :event/logged-in       {::uism/handler logged-in}
   :event/failed          {::uism/handler logged-out}
   :event/session-checked {::uism/handler (fn [{::uism/keys [state-map fulcro-app event-data] :as env}]
                                            (let [provider (:provider event-data)
                                                  status   (get-in state-map [::authorization provider ::status])
                                                  {:keys [after-session-check]} (uism/retrieve env :config)
                                                  ok?      (= :success status)]
                                              (when after-session-check
                                                (comp/transact! fulcro-app `[(~after-session-check {})]))
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
   {:initial                     {::uism/handler (fn [{::uism/keys [fulcro-app event-data] :as env}]
                                                   (let [actors (keys (uism/asm-value env ::uism/actor->ident))]
                                                     ;; Doing this as a side-effect, since we may need to handle the mutation via
                                                     ;; client request instead of server remote
                                                     (doseq [actor actors
                                                             :let [m (some-> env (uism/actor-class actor) comp/component-options
                                                                       ::check-session)]]
                                                       (when m
                                                         (comp/transact! fulcro-app `[(~m)])))
                                                     (-> env
                                                       (uism/store :config event-data)
                                                       (uism/store :authenticated #{})
                                                       (uism/activate :state/idle))))}

    :state/idle                  {::uism/events global-events}

    :state/gathering-credentials {::uism/events global-events}}})

(defn start!
  "Start the authentication system and configure it to use the provided UI components (with options).

  NOTE: THIS IS NOT A PRODUCTION-READY IMPLEMENTATION. You can use the source of this and its corresponding state
  machine as a basis for your own, but it needs more work. PRs welcome.

  * `app` - The Fulcro app to manage.
  * `authority-ui-roots` - A vector of UI components with singleton idents. Each must have
  a unique ::auth/provider (the name of the authority) and ::auth/check-session (a mutation to run that
  should return a Session from a remote that has looked for an existing session.)
  * `options` - A map of additional startup options."
  ([app authority-ui-roots] (start! app authority-ui-roots {}))
  ([app authority-ui-roots options]
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
     (uism/begin! app auth-machine machine-id actors options))))

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
