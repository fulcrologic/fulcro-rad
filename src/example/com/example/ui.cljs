(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.example.schema :as schema :refer [schema]]
    [com.example.ui.login-dialog :refer [LoginForm]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-header :refer [ui-modal-header]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.controller :as controller]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.mutations :as m]
    [clojure.set :as set]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(defsc AccountForm [this props]
  {::schema/schema   schema
   ::attr/attributes [acct/name]
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
                         ;; TODO: Edit link
                         )}
      "Name: " name)))

(def ui-account-list-item (comp/factory AccountListItem {:keyfn ::acct/id}))

(defsc AccountList [this {:keys [::acct/all-accounts] :as props}]
  {:query [{::acct/all-accounts (comp/get-query AccountListItem)}]
   :load! (fn [app options]
            (df/load! app ::acct/all-accounts AccountListItem
              (merge
                options
                {:target [:component/id ::account-list ::acct/all-accounts]})))
   :ident (fn [] [:component/id ::account-list])}
  (dom/div
    (map ui-account-list-item all-accounts)))

(def ui-account-list (comp/factory AccountList))

(defsc AccountMaster
  "This is intended to be used as a singleton (only ever on-screen once at a time). The state machine ID can therefore
  be unique, as can the form machine that is used. The state machine can be started/stopped by the top-level
  controller, and therefore the lifecycle of the state machine is disconnected from its on-screen presence.

  The idea is that this master might be able to show a list of the items in question"
  [this {:ui/keys [route-id]
         ::keys   [form list]}]
  {:query            [:ui/route-id
                      [::uism/asm-id '_]
                      {::form (comp/get-query AccountForm)}
                      {::list (comp/get-query AccountList)}]
   :ident            (fn [] [:component/id ::AccountMaster])

   ;; A pure list of all recursive attributes derived (recursively) at compile time from the Form, List, etc.
   ::attr/attributes [acct/id acct/name]

   :route-segment    ["accounts" :id]

   :prepare-route!   (fn [app [_ id :as route-segment]]
                       (let [load! (comp/component-options AccountList :load!)]
                         (cond
                           (and load! (= "all" id)) (load! app {:post-action (fn [{:keys [app]}]
                                                                               (uism/trigger! app
                                                                                 controller/machine-id :event/route-loaded
                                                                                 {:target-route route-segment}))})
                           ;; TODO: new and load existing item
                           #_#_#_#_(= "new" id) (comp/transact! app [])
                               :otherwise (df/load! app [::acct/id id] AccountForm
                                            {:target [:component/id ::AccountMaster ::form]}))))

   :initial-state    {:ui/route-id "all"
                      ::form       {}
                      ::list       {}}

   :initLocalState   (fn [this]
                       {:list-factory (comp/factory AccountList)
                        :form-factory (comp/factory AccountForm)})

   ; :state-machine-id         ::AccountMaster
   ; :state-machine-definition ::form/form-machine

   ;; The main detail form for editing an instance. The ident of the form, of course, will vary based on what is
   ;; actively being edited.
   :actor/form       AccountForm
   ;; actor list is expected to have a constant ident
   :actor/list       AccountList}
  (let [{:keys [list-factory form-factory]} (comp/get-state this)]
    (cond
      (and (= "all" route-id) list) (list-factory list)
      (and route-id form) (form-factory form)
      :else (div :.ui.active.loader ""))))

(defsc LandingPage [this props]
  {:query         ['*]
   :ident         (fn [] [:component/id ::LandingPage])
   :initial-state {}
   :route-segment ["landing-page"]}
  (dom/div "Hello World"))

(defrouter CRUDController [this props]
  {:router-targets [LandingPage AccountMaster]})

(def ui-crud-controller (comp/factory CRUDController))

;; TODO: This will be a macro or function that generates this
;; TODO: Composition of auth provider UI into this controller
(defsc AuthController [this {:ui/keys [auth-context] :as props}]
  ;; TODO: query is generated from the authentication providers
  {:query                          [:ui/auth-context
                                    {:local (comp/get-query LoginForm)}
                                    [::uism/asm-id '_]]
   ::auth/authentication-providers {:local LoginForm}
   :ident                          (fn [] [:component/id ::AuthController])
   :initial-state                  {:local           {}
                                    :ui/auth-context nil}}
  ;; TODO: Logic to choose the correct factory for the provider being used
  (let [state           (uism/get-active-state this auth/machine-id)
        authenticating? (= :state/gathering-credentials state)
        {:keys [local]} props
        factory         (comp/computed-factory LoginForm)]
    (factory local {:visible? authenticating?})))

(def ui-auth-controller (comp/factory AuthController {:keyfn :id}))

(defsc Root [this {:keys [auth-controller crud-controller]}]
  {:query         [{:auth-controller (comp/get-query AuthController)}
                   {:crud-controller (comp/get-query CRUDController)}]
   :initial-state {:crud-controller {}
                   :auth-controller {}}}
  (dom/div
    (div :.ui.top.menu
      (div :.ui.item "Demo Application")
      (dom/a :.ui.item {:onClick (fn [])} "Accounts"))
    (div :.ui.container.segment
      (ui-auth-controller auth-controller)
      (ui-crud-controller crud-controller))))

(def ui-root (comp/factory Root))

