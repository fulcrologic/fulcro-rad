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
  (:refer-clojure :exclude [run!])
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    [com.fulcrologic.rad :as rad]
    [taoensso.timbre :as log]))

(defn render-control
  "Render the control defined by `control-key` in the ::report/controls option. The control definition in question will be
   a `(fn [props])` where `props` is a map containing:

   * `owner` - The React instance of the mounted component where the controls will be shown.
   * `control-key` - The name of the control key being rendered .
   "
  ([owner control-key]
   (render-control owner control-key (get (comp/component-options owner ::controls) control-key)))
  ([owner control-key control]
   (let [{::app/keys [runtime-atom]} (comp/any->app owner)
         input-type   (get control :type)
         input-style  (get control :style :default)
         style->input (some-> runtime-atom deref ::rad/controls ::type->style->control (get input-type))
         input        (or (get style->input input-style) (get style->input :default))]
     (if input
       (input {:instance    owner
               :control     control
               :control-key control-key})
       (do
         (log/error "No renderer installed to support parameter " control-key "with type/style" input-type input-style)
         nil)))))

(def run!
  "Run the controlled content with the current values of the controlled parameters."
  (debounce
    (fn [instance]
      (uism/trigger! instance (comp/get-ident instance) :event/run))
    100))

(defn set-parameter!
  "Set the given parameter on a report or container."
  [instance parameter-name new-value]
  (uism/trigger! instance (comp/get-ident instance) :event/set-parameter {parameter-name new-value}))

