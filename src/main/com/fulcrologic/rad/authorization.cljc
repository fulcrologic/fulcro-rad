(ns com.fulcrologic.rad.authorization
  "Authentication and Authorization Support. ALPHA. SUBJECT TO CHANGES.

  RAD's authorization/authentication system is meant to support ideas and interfaces
  sufficient to protect the vast majority of applications in an extensible fashion.

  The authentication step supports both session initiation (e.g. with credentials) and resumption. The identity of
  the user and any amount of extended data can be stored in the session for use by the authorization mechanism.
  RAD recognizes that the authentication may require things like redirects, where we must be able to resume
  attempting an action after an authentication step has completed even though our application may have just reloaded.
  The login and session checks (which you implement) should return any session data necessary for your authorization
  mechanism to function properly.

  NOTE: The authentication mechanism included is half-baked. You will need to write you own in a real application
  that properly handles the various cases you need handled.

  The authorization system is fully designed, but mostly optional, and you can easily set up alternative systems
  on top of RAD as you see fit.

  Note that the primary method of the public authorization API (`can?`)  must be synchronous. In a CLJS environment this means
  authorization information for the user must be loaded at well-defined moments (e.g. login, auth promotion, prior
  to routing) and cached by the underlying implementation. The calculation of authorization can be as complex as it needs to be
  (for example a rules-based system), and the return value uses `cache-a-bool`s to allow such calculations to be
  easily cached to prevent undue overhead at runtime.

  This also means that any implementation must define a representation that is sufficiently compact (for complete
  transmission to the client) and expressive to answer the questions that the application will ask of it, or must be
  prepared to insert an auth promotion step or obtain information before the actual code needs to call `can?`.

  Another way to look at this system's design is to consider the following points:

  * The UI must be fast. Waiting for round-trips to answer an auth question during render is unacceptable.
  * The API must be simple-to-use:
  ** Mixing async code into the UI makes things complex very quickly, and is undesirable if it can be avoided.
  * We assert that a client can pre-load the information necessary to make decisions about authorization at well-defined
  moments when credentials are presented, and the extensible data model itself can solve the cases in which
  the synchronous nature of `can?` seems to be insufficient.
  * In some cases, we can rely on the server's refusal to supply data as an indication that an action wasn't allowed for
  some reason.

  One might assert that this is insufficient because the server has a very large amount of data, and any
  portion of that information might be required in order to make a decision. We cannot possibly know *in advance*
  which bits will be required. While this is a true statement, we must remember that RAD always allows additional
  information to be included when accessing a particular bit of information, and there are very few interactions
  where one cannot plan ahead for the information that will be needed.

  Take the questions related to working on an arbitrary thing, say \"invoice 99\". You might want to ask questions
  about this:

  . When the user uses HTML5 routing to access a form for invoice 99. (can user route to form for invoice 99?)
  ** Requires an async interaction. We don't even know if the user is logged in if this was just pasted
  into a URL bar. However, the async details can be hidden in the routing layer itself.
  . When the user tries to click on a link to form for invoice 99. (can user route to form for invoice 99?)
  . When making a decision about showing an link for invoice 99 in a report. (can user route to a form for invoice 99?)
  . When deciding if the overall form for invoice 99 is in read-only mode (can user save the form for invoice 99?).
  . When rendering a form field, to decide if that attribute for invoice 99 is even visible (can user read attribute X of invoice 99?).

  We assert that *no-context navigation* is the only case the requires asynchronous operation.
  *Contextual* routing and UI-layer decisions around data that is *already present* is always solvable with foresight
  (when that data was originally loaded).

  Narrowed keyword resolvers can be generated on the server that use server-side `can?` to answer permission questions. For
  example `:invoice/date` can have an additional auto-generated resolver for `:invoice.date/permissions` (which has a
  `::pc/input` of `:invoice/id`) can return the value `:read`, `:write`, `:none`. Thus, a form for invoices can simply
  auto-include these additional keys to pre-pull server-side permission information when the form is loaded. Alternative
  resolver schemes are equally simple (`:invoice/field-permissions {:x :write :y :read :z :none}`).

  The same thing works for reports. A narrowed keyword on an ID attribute such as `:invoice.id/permissions` can indicate
  general details about the user's general level of access to that entity in the database. Such an attribute can be
  included in a report's query and then used in UI code to decide when to present a user with a link or plain text.

  These details, of course, can be abstracted into configuration layers so that forms can be easily configured to query
  for the additional properties so that the `can?` interface will find the information to be present.

  If you now follow this pattern through your entire application you come up with the following logic:

  * Top-level menu items are always contextual, and based on information we know (i.e. we don't show a top level navigation unless the
    user is known to be allowed to do that kind of action). This is a finite and small set of information that is either
    hard-coded in the application or can be easily loaded at authentication-time.
  * Contextual links (from report columns, form field labels, dynamic menus) are always the result of a query,
    and such a query can easily include narrowed keywords that answer permissions questions before they are ever shown. Thus, dynamic
    links in the application can load general permissions information when the information for the link itself is loaded.
  * Non-contextual permission questions (e.g. HTML5 route given during load which assumes a session cookie will resume
    prior session) can always be treated as a request for general navigation. The navigation system is already async in
    nature, and can easily support async permission checks.
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
     "Synchronous CLJS authorization check. Must be passed a component instance or the app, and an action map. The action
     map should be generated using one of the action generators `Read`, `Write`, or `Execute`.

     NOTE: Most decisions in RAD are designed to be made synchronously. The primary exception is context-free routing,
     where several steps (including authentication) may be necessary. Use `determine` in those cases.

     If you have not installed authorization, then this function always returns cacheably-true.

     Returns a cache-a-bool"
     [this-or-app action-map]
     (if-let [authorization (some-> this-or-app comp/any->app ::app/runtime-atom deref ::authorization)]
       (authorization this-or-app action-map)
       cb/CT))
   :clj
   (defn can?
     "CLJ authorization check. Must be passed the current pathom env (i.e. mutation env). The action
     map should be generated using one of the action generators `Read`, `Write`, or `Execute`.

     If you have not installed authorization, then this function always returns cacheably-true."
     [env action-map]
     (if-let [authorization (some-> env ::authorization)]
       (authorization env action-map)
       cb/CT)))

(defn install-authorization!
  "Install your own implementation of `can?` on the given RAD application. Your
  `can-fn` must be a `(fn [env action-map] cache-a-bool)`. See `can?`."
  [app can-fn]
  (swap! (::app/runtime-atom app) assoc ::authorization can-fn))

(defn pathom-plugin
  "A pathom plugin that installs the given implementation of `can-fn` for the parser authorization. Your
   `can-fn` must be a `(fn [env action-map] cache-a-bool)`. See `can?`."
  [can-fn]
  (p/env-wrap-plugin
    (fn [env]
      (assoc env ::authorization can-fn))))

(defn readable?
  [env a]
  (let [{::attr/keys [qualified-key]
         ::keys      [permissions]} a]
    (cb/as-boolean
      (cb/And
        (can? env (Read qualified-key))
        (or
          (nil? permissions)
          (and permissions (contains? (set (permissions env)) :read)))))))

(defn redact
  "Creates a post-processing plugin that redacts attributes that have ::permissions (a set or `(fn [env] set?)`)
   which does not include :read, or for which the general `(auth/can? env (Read attr))` indicates false."
  [{attr-map ::attr/key->attribute
    :as      env} query-result]
  (p/transduce-maps (map (fn [[k v]]
                           (let [a (get attr-map k)]
                             (if (readable? env a)
                               [k v]
                               [k ::REDACTED]))))
    query-result))

