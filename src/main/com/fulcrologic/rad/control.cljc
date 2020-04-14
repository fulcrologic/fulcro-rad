(ns com.fulcrologic.rad.control
  "Controls are buttons and inputs in the UI that are not backed by model data, but instead
   control things like report parameters or provide action buttons. This namespace provides
   functions to help with UI plugin development, and other functions that reduce the amount
   of boilerplate data when declaring controls.

   A typical control is added to a component by adding a ::control/controls key, which
   is a map from made-up control key to a control definition map.

   ```
   (defsc-form Form [this props]
     {::control/controls {::new {:type :button
                                 :label \"Go\"
                                 :action (fn [this] ...)}}})
   ```

   Render plugins can then expose layout keys that allow you to place the controls. For example as action
   buttons. See ::form/action-buttons.
   "
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad :as rad]
    [taoensso.timbre :as log]))

(defn render-control
  "Render the control defined by `control-key` in the ::report/controls option. The control definition in question will be
   a `(fn [props])` where `props` is a map containing:

   * `master-component` - The React instance of the mounted component where the controls will be shown.
   * `control-key` - The name of the control key being rendered .
   "
  [master-component control-key]
  (let [{::app/keys [runtime-atom]} (comp/any->app master-component)
        {::keys [controls]} (comp/component-options master-component)
        input-type   (get-in controls [control-key :type])
        input-style  (get-in controls [control-key :style] :default)
        style->input (some-> runtime-atom deref ::rad/controls ::type->style->control (get input-type))
        input        (or (get style->input input-style) (get style->input :default))]
    (if input
      (input {:report-instance master-component
              :control-key     control-key})
      (do
        (log/error "No renderer installed to support parameter " control-key "with type/style" input-type input-style)
        nil))))
