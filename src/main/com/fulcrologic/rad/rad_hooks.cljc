(ns com.fulcrologic.rad.rad-hooks
  "Hooks for using forms and reports in hook-based React components, without the need to compose them into routing,
   queries, etc."
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.report-options :as ro]))

(defn use-form
  "React hook. Use a RAD form.

   This hook:

   * Attaches the form to the parent component's lifecycle. The form will only exist as long as the parent is mounted
     on the DOM.
   * Creates a form state machine.
   * Runs the proper sequence for create/edit (based on the id you give. A tempid will cause a create, otherwise edit).
   * Returns a map with:

   `:form-factory` - A react factory for rendering the form
   `:form-props` - The current value of the form. Must be passed to `form-factory`.
   `:form-state` - The state of the state machine. See the form state machine definition for possible values.

  The component that calls this hook will trigger the form to start (load if it is a real ID, and do NEW if `id` is
  a tempid). The form machine will run until the containing component (that calls this) is unmounted from the DOM (not
  just hidden).

  The save-completion-mutation will be invoked with `:ident final-ident`, where `final-ident` is the potentially remapped
  (permanent) ID of the entity that was saved.

  The `cancel-mutation` is invoked if the user cancels out of the form and has made no changes.

  The options can include (extra) parameters that will be passed to the mutations when called (in addition to the
  auto-included `final-ident`).

  If you leave the form mounted, then it is possible for you to see multiple saves, BUT, if the cancel mutation is called
  then the form machine WILL exit, so the form will stop working and you really should unmount it from the DOM.
  "
  ([app-ish Form id save-complete-mutation cancel-mutation]
   (use-form app-ish Form id save-complete-mutation cancel-mutation {}))
  ([app-ish Form id save-complete-mutation cancel-mutation {:keys [save-mutation-params cancel-mutation-params]}]
   (let [container-id    (hooks/use-generated-id)
         app             (rc/any->app app-ish)
         id-key          (-> Form (rc/component-options) fo/id ao/qualified-key)
         machine         (or (comp/component-options Form fo/machine) form/form-machine)
         form-ident      [id-key id]
         [container-component] (hooks/use-state (fn [] (rc/nc [{:ui/form (rc/get-query Form)}]
                                                         {:initial-state (fn [_] {:ui/form {id-key id}})
                                                          :ident         (fn [_ _] [::id container-id])})))
         container-props (hooks/use-component app container-component {:initialize true :keep-existing? false})
         [form-factory] (hooks/use-state (fn [] (comp/computed-factory Form {:keyfn id-key})))
         active-state    (get-in container-props [:ui/form ::uism/asm-id form-ident ::uism/active-state])]
     (hooks/use-lifecycle
       (fn []
         (uism/begin! app machine form-ident {:actor/form (uism/with-actor-class form-ident Form)}
           {:embedded?     true
            :on-saved      [(save-complete-mutation (merge save-mutation-params {:ident form-ident}))]
            :on-cancel     [(cancel-mutation (or cancel-mutation-params {:ident form-ident}))]
            ::form/create? (tempid/tempid? id)}))
       (fn [] (uism/remove-uism! app form-ident)))
     {:form-factory (fn [props]
                      (when (and (map? props) (contains? props id-key))
                        (form-factory props)))
      :form-props   (get container-props :ui/form)
      :form-state   active-state})))

(defn use-report
  "React hook. Use a RAD Report."
  ([app-ish Report] (use-report app-ish Report {}))
  ([app-ish Report {:keys [keep-existing?] :as options}]
   (let [app          (rc/any->app app-ish)
         [id-key _ :as report-ident] (comp/get-ident Report {})
         report-props (hooks/use-component app Report {:initialize? false})
         [report-factory] (hooks/use-state (fn [] (comp/computed-factory Report {:keyfn id-key})))
         active-state (get-in report-props [::uism/asm-id report-ident ::uism/active-state])]
     (hooks/use-lifecycle
       (fn [] (report/start-report! app Report (assoc options :embedded? true)))
       (fn [] (when-not keep-existing? (uism/remove-uism! app report-ident))))
     {:report-factory (fn [props]
                        (when (map? props)
                          (report-factory props)))
      :report-props   report-props
      :report-state   active-state})))
