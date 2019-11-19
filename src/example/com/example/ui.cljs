(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.example.schema :as schema :refer [schema]]
    [com.example.ui.login-dialog :refer [LoginForm]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-header :refer [ui-modal-header]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.mutations :as m]
    [clojure.set :as set]))

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
  {:router-targets [AccountMaster]})

(def ui-crud-controller (comp/factory CRUDController))

;; TODO: This will be a macro or function that generates this
;; TODO: Composition of auth provider UI into this controller
(defsc AuthController [this {:ui/keys [auth-context] :as props}]
  ;; TODO: query is generated from the authentication providers
  {:query                          [:ui/auth-context
                                    {:production (comp/get-query LoginForm)}
                                    [::uism/asm-id '_]]
   ::auth/authentication-providers {:production LoginForm}
   :ident                          (fn [] [:component/id ::AuthController])
   :initial-state                  {}}
  ;; TODO: Logic to choose the correct factory for the provider being used
  (let [state           (uism/get-active-state this ::auth-machine)
        authenticating? (= :state/gathering-credentials state)
        {:keys [production]} props
        factory         (comp/computed-factory LoginForm)]
    (factory production {:visible? authenticating?})))

(def ui-auth-controller (comp/factory AuthController {:keyfn :id}))

(defsc Root [this {:keys [auth-controller crud-controller]}]
  {:query         [{:auth-controller (comp/get-query AuthController)}
                   {:crud-controller (comp/get-query CRUDController)}]
   :initial-state {:crud-controller {}}}
  (dom/div {}
    (dom/h3 "Main Layout")
    (ui-auth-controller auth-controller)
    (ui-crud-controller crud-controller)))

(def ui-root (comp/factory Root))

(defn providers-on-route
  "Returns a set of providers that are required to properly render the given route"
  [{::uism/keys [event-data] :as env}]
  ;; TODO: Calculate providers from attributes that are on the query of the given route
  #{:production})

(defn initialize-route-data [env]
  (uism/activate env :state/routing))

(defn prepare-for-route [{::uism/keys [fulcro-app] :as env}]
  (let [necessary-providers (providers-on-route env)
        current-providers   (auth/current-providers fulcro-app)
        missing-providers   (set/difference current-providers necessary-providers)]
    (if (empty? missing-providers)
      (initialize-route-data env)
      (-> env
        (auth/authenticate :production ::crud-machine)
        (uism/activate :state/gathering-permissions)))))

(defstatemachine crud-machine
  {::uism/aliases
   {}

   ::uism/states
   {:state/initial
    {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                      (-> env
                        (uism/store :config event-data)
                        (prepare-for-route)))}

    :state/gathering-permissions
    {}

    :state/routing
    {}

    :state/idle
    {::uism/events
     {:event/list {::uism/handler (fn [env] env)}
      }}}})

(defn start! [app config]
  (uism/begin! app auth/auth-machine auth/machine-id
    {:actor/controller AuthController})

  (uism/begin! app crud-machine ::crud-machine
    {:actor/auth-controller AuthController
     :actor/crud-controller CRUDController}
    {:default-route ["accounts"]}))
