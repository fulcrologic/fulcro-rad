(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.example.schema :as schema :refer [schema]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-header :refer [ui-modal-header]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.mutations :as m]))

(defsc AccountForm [this props]
  {::schema/schema   schema
   ::form/attributes [acct/name]
   ;; TODO: Derive query of attributes that are needed to manage the entities that hold the
   ;; attributes being edited.
   :form-fields      #{::acct/name}
   :query            [::acct/id ::acct/name]
   :ident            ::acct/id}
  (form/render-form this props))

(defsc AccountListItem [this {::acct/keys [id name] :as props}]
  {:query [::acct/id ::acct/name]
   :ident ::acct/id}
  (dom/div
    (dom/div {:onClick (fn []
                         (uism/trigger! this :some-machine-id :event/edit {:id id}))}
      "Name: " name)))

(def ui-account-list-item (comp/factory AccountListItem {:keyfn :id}))

(defsc AccountList [this {:keys [::acct/all-accounts] :as props}]
  {:query [{::acct/all-accounts (comp/get-query AccountListItem)}]
   :ident (fn [] [:component/id ::account-list])}
  (dom/div
    (map ui-account-list-item all-accounts)))

(def ui-account-list (comp/factory AccountList {:keyfn ::acct/all-accounts}))

(defsc AccountMaster
  "This is intended to be used as a singleton (only ever on-screen once at a time). The state machine ID can therefore
  be unique, as can the form machine that is used. The state machine can be started/stopped by the top-level
  controller, and therefore the lifecycle of the state machine is disconnected from its on-screen presence.

  The idea is that this master might be able to show a list of the items in question"
  [this {::keys [form list]}]
  {:query         [{::form (comp/get-query AccountForm)}
                   {::list (comp/get-query AccountList)}]
   :ident         (fn [] [:component/id ::AccountMaster])
   :route-segment ["accounts"]
   :initial-state {::form {}
                   ::list {}}

   ; :state-machine-id         ::AccountMaster
   ; :state-machine-definition ::form/form-machine

   ;; The main detail form for editing an instance. The ident of the form, of course, will vary based on what is
   ;; actively being edited.
   :actor/form    AccountForm
   ;; actor list is expected to have a constant ident
   :actor/list    AccountList}
  (dom/div
    "Master. No route requested."))

(defrouter CRUDController
  [this {:keys [:active-master] :as props}]
  {:router-targets [AccountMaster]

   })

(def ui-crud-controller (comp/factory CRUDController))

(defn set-authentication-actor
  "Change the actor that acts as the UI for gathering the credentials based on env provider"
  [env]
  ;; TODO: swap actors for authentication provider
  env)

(defn authenticate [env]
  (-> env
    (set-authentication-actor)
    (uism/assoc-aliased :username "")
    (uism/assoc-aliased :password "")
    (uism/activate :state/gathering-credentials)))

(defstatemachine auth-machine
  {::uism/aliases
   {:username [:actor/auth-dialog :ui/username]
    :password [:actor/auth-dialog :ui/password]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (-> env
                        ;; TODO: Ping auth providers to see if we already have auth
                        (uism/store :authenticated #{})
                        (uism/activate :state/idle)))}

    :state/idle
    {::uism/events
     {:event/authenticate
      {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                        (let [{:keys [provider source-machine-id]} event-data
                              authenticated (uism/retrieve env :authenticated)]
                          (log/debug "Checking for authentication ")
                          (if (contains? authenticated provider)
                            (do
                              (log/debug "Already authenticated")
                              (uism/trigger env source-machine-id :event/authenticated))
                            (authenticate env))))}}}

    :state/gathering-credentials
    {::uism/events
     {:event/authenticate
      {::uism/handler (fn [env]
                        )}}}

    }})

(defsc LoginForm [this {:keys [:id    ] :as props}]
  {:query [:id    ]
   :ident :id}
  (dom/div ))

;; TODO: Composition of auth provider UI into this controller
(defsc AuthController [this {:ui/keys [username password] :as props}]
  {:query         [:ui/auth-context
                   :ui/username
                   :ui/password
                   [::uism/asm-id '_]]
   :authentication-providers {:production LoginForm}
   :ident         (fn [] [:component/id ::AuthController])
   :initial-state {}}
  (let [state           (uism/get-active-state this ::auth-machine)
        authenticating? (= :state/gathering-credentials state)]
    (ui-modal {:open authenticating? :dimmer true}
      (ui-modal-header {} "Please Log In")
      (ui-modal-content {}
        (div :.ui.form
          (div :.ui.field
            (label "Username")
            (input {:type     "email"
                    :onChange (fn [evt] (m/set-string! this :ui/password :event evt))
                    :value    password}))
          (div :.ui.field
            (label "Password")
            (input {:type     "password"
                    :onChange (fn [evt] (m/set-string! this :ui/password :event evt))
                    :value    password}))
          (div :.ui.primary.button
            {:onClick (fn [] (uism/trigger! this ::auth-machine :event/login {:username username :password password}))}
            "Login"))))))

(def ui-auth-controller (comp/factory AuthController {:keyfn :id}))

(defsc Root [this {:keys [auth-controller crud-controller]}]
  {:query         [{:auth-controller (comp/get-query CRUDController)}
                   {:crud-controller (comp/get-query CRUDController)}]
   :initial-state {:crud-controller {}}}
  (dom/div {}
    (dom/h3 "Main Layout")
    (ui-auth-controller auth-controller)
    (ui-crud-controller crud-controller)))

(def ui-root (comp/factory Root))

(defn prepare-for-route [env route])

(defstatemachine crud-machine
  {::uism/actors
   #{:actor/auth-controller
     :actor/crud-controller}

   ::uism/aliases
   {}

   ::uism/states
   {:state/initial
    {::uism/handler (fn [{::uism/keys [event-data] :as env}
                         {:keys [default-route]} event-data]
                      (-> env
                        (uism/store :config event-data)
                        (prepare-for-route default-route)
                        (uism/activate :state/routing)))}

    :state/routing
    {}

    :state/idle
    {::uism/events
     {:event/list {::uism/handler (fn [env] env)}
      }}}})

(defn start! [app config]
  ;; this stuff would come from config...hard coded for playing
  (uism/begin! app crud-machine ::crud-machine
    {:actor/auth-controller AuthController
     :actor/crud-controller CRUDController}
    {:default-route ["accounts"]
     })

  (uism/begin! app auth-machine ::auth-machine
    {:actor/auth-dialog AuthController}))
