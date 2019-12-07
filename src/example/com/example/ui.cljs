(ns com.example.ui
  (:require
    ;; This require pulls in the multimethods for rendering w/semantic UI
    [com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls]
    [com.example.model.account :as acct]
    [com.example.ui.login-dialog :refer [LoginForm]]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-header :refer [ui-modal-header]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.controller :as controller]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
    [taoensso.timbre :as log]))

(form/defsc-form AccountForm [this props]
  {::form/id           acct/id
   ::form/attributes   [acct/name acct/email]
   ; ::form/read-only?   {::acct/email false}
   ::form/cancel-route ["landing-page"]
   ::form/route-prefix "account"
   ::form/title        "Edit Account"
   ;;::form/confirm-exit? true
   ;; TODO: Derive query of attributes that are needed to manage the entities that hold the
   ;; attributes being edited.
   })

(defsc AccountListItem [this {::acct/keys [id name active? last-login] :as props}]
  {::report/columns         [::acct/name ::acct/active? ::acct/last-login]
   ::report/column-headings ["Name" "Active?" "Last Login"]
   ::report/edit-form       AccountForm
   :query                   [::acct/id ::acct/name ::acct/active? ::acct/last-login]
   :ident                   ::acct/id}
  #_(dom/div :.item
      (dom/i :.large.github.middle.aligned.icon)
      (div :.content
        (dom/a :.header {:onClick (fn [] (form/edit! this AccountForm id))} name)
        (dom/div :.description
          (str (if active? "Active" "Inactive") ". Last logged in " last-login)))))

(def ui-account-list-item (comp/factory AccountListItem {:keyfn ::acct/id}))

(report/defsc-report AccountList [this props]
  {::report/BodyItem         AccountListItem
   ::report/source-attribute ::acct/all-accounts
   ::report/parameters       {:ui/show-inactive? :boolean}
   ::report/route            "accounts"})

(defsc LandingPage [this props]
  {:query         ['*]
   :ident         (fn [] [:component/id ::LandingPage])
   :initial-state {}
   :route-segment ["landing-page"]}
  (dom/div "Hello World"))

;; This will just be a normal router...but there can be many of them.
(defrouter MainRouter [this props]
  {:router-targets [LandingPage AccountList AccountForm]})

(def ui-main-router (comp/factory MainRouter))

(auth/defauthenticator Authenticator {:local LoginForm})

(def ui-authenticator (comp/factory Authenticator))

(defsc Root [this {:keys [authenticator router]}]
  {:query         [{:authenticator (comp/get-query Authenticator)}
                   {:router (comp/get-query MainRouter)}]
   :initial-state {:router        {}
                   :authenticator {}}}
  (dom/div
    (div :.ui.top.menu
      (div :.ui.item "Demo Application")
      ;; TODO: Show how we can check authority to hide UI
      (dom/a :.ui.item {:onClick (fn [] (form/edit! this AccountForm (new-uuid 1)))} "My Account")
      #_(dom/a :.ui.item {:onClick (fn []
                                     (controller/route-to! this :main-controller
                                       ["account" "create" (str (new-uuid))]))} "New Account")
      (dom/a :.ui.item {:onClick (fn []
                                   (controller/route-to! this :main-controller ["accounts"]))} "List Accounts"))
    (div :.ui.container.segment
      (ui-authenticator authenticator)
      (ui-main-router router))))

(def ui-root (comp/factory Root))

