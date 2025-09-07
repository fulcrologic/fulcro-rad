(ns com.fulcrologic.rad.rendering.semantic-ui.modals
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.rad.rad-hooks :as rad-hooks]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]))

(defsc FormModal [this {:keys [id Form
                               save-mutation
                               cancel-mutation
                               save-params
                               cancel-params]}]
  {:use-hooks? true}
  (ui-modal {:open true}
    (ui-modal-content {}
      (let [[generated-id] (hooks/use-state (or id (tempid/tempid)))
            {:keys [form-factory
                    form-props
                    form-state]} (rad-hooks/use-form this Form generated-id save-mutation cancel-mutation
                                   {:save-mutation-params   save-params
                                    :cancel-mutation-params cancel-params})]
        (form-factory form-props)))))

(def ui-form-modal
  "[{:keys [id Form save-mutation cancel-mutation save-params cancel-params]}]

    Render a form in a Semantic UI Modal.

    :Form - Required. The form to use for edit/create
    :save-mutation - Required. A *mutation* that will be transacted with the final ident if/when the form is saved.
    :cancel-mutation - Required. A *mutation* that will be transacted if the cancel button is pressed.
    :id - Optional. If not supplied will create a new instance. If supplied it will load and edit it.
    :save-params - Optional. Extra parameters (beyond the `:ident` that is auto-included) to pass to the save-mutation`
    :cancel-params - Optional. Parameters to pass to the cancel-mutation`

    Example usage:

    ```
    (defmutation saved [{:keys [ident]}]
      (action [{:keys [state]}]
        (swap! state update-in [:component/id ::Container] assoc
          :ui/selected-account ident
          :ui/open? false)))

    (defmutation cancel [_]
      (action [{:keys [state]}]
        (swap! state update-in [:component/id ::Container] assoc
          :ui/open? false)))

    (defsc Container [this {:ui/keys [open? selected-account edit-id] :as props}]
      {:query         [:ui/open? :ui/selected-account]
       :ident         (fn [] [:component/id ::Container])
       :initial-state {}}
      (comp/fragment {}
        (when open?
          (ui-form-modal {:Form            BriefAccountForm
                          :save-mutation   saved
                          :cancel-mutation cancel}))
        (dom/div (str selected-account))
        (dom/button {:onClick (fn []
                                (comp/transact! this [(m/set-props {:ui/open?   true})]))} \"New\")))

        ```
  "
  (comp/factory FormModal))
