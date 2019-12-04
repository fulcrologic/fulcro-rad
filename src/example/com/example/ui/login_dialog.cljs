(ns com.example.ui.login-dialog
  (:require
    [com.example.model.account :as account]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-header :refer [ui-modal-header]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :refer [div label input]]
    [com.fulcrologic.fulcro.mutations :as m]
    [taoensso.timbre :as log]))

(defsc LoginForm [this {:ui/keys [username password] :as props} {:keys [visible?]}]
  {:query         [:ui/username
                   :ui/password]
   :initial-state {:ui/username "tony@example.com"
                   :ui/password "letmein"}
   :ident         (fn [] [:component/id ::LoginForm])}
  (ui-modal {:open (boolean visible?) :dimmer true}
    (ui-modal-header {} "Please Log In")
    (ui-modal-content {}
      (div :.ui.form
        (div :.ui.field
          (label "Username")
          (input {:type     "email"
                  :onChange (fn [evt] (m/set-string! this :ui/username :event evt))
                  :value    (or username "")}))
        (div :.ui.field
          (label "Password")
          (input {:type     "password"
                  :onChange (fn [evt] (m/set-string! this :ui/password :event evt))
                  :value    (or password "")}))
        (div :.ui.primary.button
          {:onClick (fn [] (comp/transact! this [(account/login {:username username :password password})]))}
          "Login")))))
