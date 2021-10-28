(ns com.fulcrologic.rad.form
  #?(:cljs (:require-macros [com.fulcrologic.rad.form]))
  (:require
    [clojure.spec.alpha :as s]
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.errors :refer [required!]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.application :as rapp]
    [com.fulcrologic.rad.ids :as ids :refer [new-uuid]]
    [com.fulcrologic.rad.type-support.integer :as int]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [edn-query-language.core :as eql]
    [taoensso.tufte :refer [p profile]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    #?@(:clj  [[cljs.analyzer :as ana]]
        :cljs [[goog.object :as gobj]])
    [com.fulcrologic.rad.options-util :as opts :refer [?! narrow-keyword]]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.rad.routing.history :as history]))

(def view-action "view")
(def create-action "create")
(def edit-action "edit")
(declare form-machine valid? invalid? cancel! undo-all! save!)

(def standard-action-buttons
  "The standard ::form/action-buttons button layout. Requires you include stardard-controls in your ::control/controls key."
  [::done ::undo ::save])

(def standard-controls
  "The default value of ::control/controls for forms. Includes a ::done, ::undo, and ::save button."
  {::done {:type   :button
           :local? true
           :label  (fn [this]
                     (let [props           (comp/props this)
                           read-only-form? (?! (comp/component-options this ::read-only?) this)
                           dirty?          (if read-only-form? false (or (:ui/new? props) (fs/dirty? props)))]
                       (if dirty? (tr "Cancel") (tr "Done"))))
           :class  (fn [this]
                     (let [props  (comp/props this)
                           dirty? (or (:ui/new? props) (fs/dirty? props))]
                       (if dirty? "ui tiny primary button negative" "ui tiny primary button positive")))
           :action (fn [this] (cancel! {::master-form this}))}
   ::undo {:type      :button
           :local?    true
           :disabled? (fn [this]
                        (let [props           (comp/props this)
                              read-only-form? (?! (comp/component-options this ::read-only?) this)
                              dirty?          (if read-only-form? false (or (:ui/new? props) (fs/dirty? props)))]
                          (not dirty?)))
           :label     (fn [_] (tr "Undo"))
           :action    (fn [this] (undo-all! {::master-form this}))}
   ::save {:type      :button
           :local?    true
           :disabled? (fn [this]
                        (let [props           (comp/props this)
                              read-only-form? (?! (comp/component-options this ::read-only?) this)
                              remote-busy?    (seq (::app/active-remotes props))
                              dirty?          (if read-only-form? false (or (:ui/new? props) (fs/dirty? props)))]
                          (or (not dirty?) remote-busy?)))
           :label     (fn [_] (tr "Save"))
           :class     (fn [this]
                        (let [props        (comp/props this)
                              remote-busy? (seq (::app/active-remotes props))]
                          (when remote-busy? "ui tiny primary button loading")))
           :action    (fn [this] (save! {::master-form this}))}})


(>def ::form-env map?)

(>defn picker-join-key
  "Returns a :ui/picker keyword customized to the qualified keyword"
  [qualified-key]
  [qualified-keyword? => qualified-keyword?]
  (keyword "ui" (str (namespace qualified-key) "-"
                  (name qualified-key)
                  "-picker")))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-fn
  "Find the correct UI renderer for the given form layout `element`.

   `element` must be one of :

   ```
   #{:form-container :form-body-container}
   ```
  "
  [{::keys [form-instance] :as form-env} element]
  (let [{::app/keys [runtime-atom]} (comp/any->app form-instance)
        style-path             [::layout-styles element]
        layout-style           (or (some-> form-instance comp/component-options (get-in style-path)) :default)
        element->style->layout (some-> runtime-atom deref ::rad/controls ::element->style->layout)
        render-fn              (some-> element->style->layout (get element) (get layout-style))]
    (cond
      (not runtime-atom) (log/error "Form instance was not in the rendering environment. This means the form did not mount properly")
      (not render-fn) (log/error "No renderer was installed for layout style" layout-style "for UI element" element))
    render-fn))

(defn form-container-renderer
  "The top-level container for the entire on-screen form"
  [form-env] (render-fn form-env :form-container))

(defn form-layout-renderer
  "The container for the form fields. Used to wrap the main set of fields, and as the container for
   fields in nested forms. This renderer can determine layout of the fields themselves."
  [form-env] (render-fn form-env :form-body-container))

(declare render-field)

(defn ref-container-renderer
  "Given the current rendering environment and an attribute: Returns the renderer that wraps and lays out
   elements of refs. This function interprets the ::form/subforms settings for referenced objects that
   will render as sub-forms, and looks for ::form/layout-style first in the subform, and next on the
   component options of the ::form/ui class."
  [{::keys [form-instance] :as form-env} {::keys      [field-style]
                                          ::attr/keys [qualified-key] :as attr}]
  (let [{::keys [subforms field-styles] :as options} (comp/component-options form-instance)
        field-style (or (get field-styles qualified-key) field-style)]
    (if field-style
      (fn [env attr _] (render-field env attr))
      (let [{::keys [ui layout-styles]} (get subforms qualified-key)
            {target-styles ::layout-styles} (comp/component-options ui)
            {::app/keys [runtime-atom]} (comp/any->app form-instance)
            element      :ref-container
            layout-style (or
                           (get layout-styles element)
                           (get target-styles element)
                           :default)
            render-fn    (some-> runtime-atom deref ::rad/controls ::element->style->layout
                           (get-in [element layout-style]))]
        render-fn))))

(defn master-form
  "Return the master form for the given component instance."
  [component]
  (or (some-> component comp/get-computed ::master-form) component))

(defn attr->renderer
  "Given a form rendering environment and an attribute: returns the renderer that can render the given attribute.

  The attribute style of :default is the default, and can be overridden in ::form/field-styles on the form (master
  has precedence, followed by the form it actually appears on) or
  using ::form/field-style on the attribute itself."
  [{::keys [form-instance master-form]} {::attr/keys [type qualified-key style]
                                         ::keys      [field-style] :as attr}]
  (let [{::app/keys [runtime-atom]} (comp/any->app form-instance)
        field-style (?! (or
                          (some-> master-form comp/component-options ::field-styles qualified-key)
                          (some-> form-instance comp/component-options ::field-styles qualified-key)
                          field-style
                          style
                          :default)
                      form-instance)
        control-map (some-> runtime-atom deref ::rad/controls ::type->style->control)
        control     (or
                      (get-in control-map [type field-style])
                      (do
                        (log/warn "Renderer not found: " type field-style)
                        (get-in control-map [type :default])))]
    (if control
      control
      (log/error "Unable to find control (no default) for attribute " attr))))

(defn render-field
  "Given a form rendering environment and an attrbute: renders that attribute according to its type/style/value."
  [env attr]
  (let [render (attr->renderer env attr)]
    (if render
      (render env attr)
      (do
        (log/error "No renderer installed to support attribute" attr)
        nil))))

(defn rendering-env
  "Create a form rendering environment. `form-instance` is the react element instance of the form (typically a master form),
   but this function can be called using an active sub-form. `props` should be the props of the `form-instance`, and are
   allowed to be passed as an optimization when you've already got them.

   NOTE: This function will automatically extract the master form from the computed props of form-instance in cases
   where you are in the context of a sub-form."
  ([form-instance]
   (let [props  (comp/props form-instance)
         cprops (comp/get-computed props)]
     (merge cprops
       {::master-form    (master-form form-instance)
        ::form-instance  form-instance
        ::props          props
        ::computed-props cprops})))
  ([form-instance props]
   (let [cprops (comp/get-computed props)]
     (merge cprops
       {::master-form    (master-form form-instance)
        ::form-instance  form-instance
        ::props          props
        ::computed-props cprops}))))

(defn master-form?
  "Returns true if the given react element `form-instance` is the master form in the supplied rendering env."
  [rendering-env form-instance]
  (let [master-form (::master-form rendering-env)]
    (= form-instance master-form)))

(defn render-layout
  "Render the complete layout of a form. This is the default body of normal form classes. It will call a render factory
   on any subforms, and they, in turn, will use this to render *their* body. Thus, any form can have a manually-overriden
   render body."
  [form-instance props]
  (when-not (comp/component? form-instance)
    (throw (ex-info "Invalid form instance propagated to render layout." {:form-instance form-instance})))
  (let [env    (rendering-env form-instance props)
        render (form-container-renderer env)]
    (if render
      (render env)
      nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Form creation/logic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-fields
  "Recursively walks the definition of a RAD form (form and all subforms), and returns the attribute qualified keys
   that match `(pred attribute)`"
  [form-class pred]
  (let [attributes        (or
                            (comp/component-options form-class ::attributes)
                            [])
        local-optional    (into #{} (comp (filter pred) (map ::attr/qualified-key)) attributes)
        children          (some->> form-class comp/get-query eql/query->ast :children (keep :component))
        children-optional (map #(find-fields % pred) children)]
    (apply set/union local-optional children-optional)))

(defn optional-fields
  "Returns all of the form fields from a form (recursively) that are not marked ao/required?"
  [form-class]
  (find-fields form-class #(not (true? (get % ::attr/required?)))))

#?(:clj
   (s/def ::defsc-form-args (s/cat
                              :sym symbol?
                              :doc (s/? string?)
                              :arglist (s/and vector? #(<= 2 (count %) 5))
                              :options map?
                              :body (s/* any?))))

#?(:clj
   (s/def ::defsc-form-options (s/keys :req [::attr/attributes])))

(defn- sc [registry-key options]
  (let [cls (fn [])]
    (comp/configure-component! cls registry-key options)))

;; NOTE: This MUST be used within a lambda in the component, not as a static bit of query at compile time.
(defn form-options->form-query
  "Converts form options to the necessary EQL query for a form class."
  [{id-attr ::id
    ::keys  [attributes field-styles subforms] :as form-options}]
  (let [id-key             (::attr/qualified-key id-attr)
        {refs true scalars false} (group-by #(= :ref (::attr/type %)) attributes)
        query-with-scalars (into
                             [id-key
                              :ui/confirmation-message
                              [::picker-options/options-cache '_]
                              [::app/active-remotes '_]
                              [::uism/asm-id '_]
                              fs/form-config-join]
                             (map ::attr/qualified-key)
                             scalars)
        full-query         (into query-with-scalars
                             (mapcat (fn [{::attr/keys [qualified-key]}]
                                       (if-let [subform (get-in subforms [qualified-key ::ui])]
                                         [{qualified-key (comp/get-query subform)}]
                                         (let [style          (get field-styles qualified-key)
                                               k->attr        (into {} (map (fn [{::attr/keys [qualified-key] :as attr}] [qualified-key attr])) attributes)
                                               target-id-key  (::attr/target (k->attr qualified-key))
                                               fake-component (sc qualified-key {:query (fn [_] [target-id-key])
                                                                                 :ident (fn [_ props] [target-id-key (get props target-id-key)])})]
                                           (when-not (and style target-id-key)
                                             (log/warn "Reference attribute" qualified-key "in form has no subform information and no field style/target id key."))
                                           [{qualified-key (comp/get-query fake-component)}]))))
                             refs)]
    full-query))

(def ^:deprecated parse-long "moved to integer.cljs" int/parse-long)

(defn start-form!
  "Forms use a state machine to control their behavior. Normally that state machine is started when you route to
  it using Fulcro's dynamic router system. If you start with a form on-screen, or do not use routing, then you will
  have to call this function when the form first appears in order to ensure it operates. Calling this function is
  *destructive* and will re-start the form's machine and destroy any current state in that form.

  * app - The app
  * id - The ID of the form, in the correct type (i.e. int, UUID, etc.). Use a `tempid` to create something new, otherwise
  the form will attempt to load the current value from the server.
  * form-class - The component class that will render the form and has the form's configuration.
  * params - Extra parameters to include in the initial event data. The state machine definition you're using will
    determine the meanings of these (if any). The default machine supports:
    ** `:on-saved fulcro-txn` A transaction to run when the form is successfully saved. Exactly what you'd pass to `transact!`.
    ** `:on-cancel fulcro-txn` A transaction to run when the edit is cancelled.
    ** `:on-save-failed fulcro-txn` A transaction to run when the server refuses to save the data.

  The state machine definition used by this method can be overridden by setting `::form/machine` in component options
  to a different Fulcro uism state machine definition. Machines do *not* run in subforms, only in the master, which
  is what `form-class` will become for that machine.
  "
  ([app id form-class] (start-form! app id form-class {}))
  ([app id form-class params]
   (let [{::attr/keys [qualified-key type]} (comp/component-options form-class ::id)
         machine    (or (comp/component-options form-class ::machine) form-machine)
         new?       (tempid/tempid? id)
         form-ident [qualified-key id]]
     (uism/begin! app machine
       form-ident
       {:actor/form (uism/with-actor-class form-ident form-class)}
       (merge params {::create? new?})))))

(defn form-will-enter
  "Used as the implementation and return value of a form target's will-enter dynamic routing hook."
  [app {:keys [action id] :as route-params} form-class]
  (let [{::attr/keys [qualified-key type]} (comp/component-options form-class ::id)
        new?       (= create-action action)
        coerced-id (if new? (tempid/tempid) (ids/id-string->id type id))
        form-ident [qualified-key coerced-id]]
    (when (and new? (not (ids/valid-uuid-string? id)))
      (log/error (comp/component-name form-class) "Invalid UUID string " id "used in route for new entity. The form may misbehave."))
    (dr/route-deferred form-ident (fn [] (start-form! app coerced-id form-class route-params)))))

(defn form-will-leave
  "Checks to see if the UISM is still running (indicating an exit via routing) and cleans up the machine."
  [this]
  (let [master-form (or (comp/get-computed this ::master-form) this)
        state-map   (app/current-state this)
        form-ident  (comp/get-ident master-form)
        machine     (get-in state-map [::uism/asm-id form-ident])]
    (when machine
      (uism/trigger! master-form form-ident :event/exit {}))
    true))

(defn form-allow-route-change [this]
  "Used as a form route target's :allow-route-change?"
  (let [id            (comp/get-ident this)
        form-props    (comp/props this)
        read-only?    (?! (comp/component-options this ::read-only?) this)
        current-state (app/current-state this)
        abandoned?    (get-in current-state [::uism/asm-id id ::uism/local-storage :abandoned?] false)
        dirty?        (and (not abandoned?) (fs/dirty? form-props))]
    (or read-only? (not dirty?))))

(defn form-pre-merge
  "Generate a pre-merge for a component that has the given for attribute map. Returns a proper
  pre-merge fn, or `nil` if none is needed"
  [{::keys [subforms]} key->attribute]
  (let [sorters-by-k (into {}
                       (keep (fn [k]
                               (when-let [sorter (get-in subforms [k ::sort-children])]
                                 [k sorter])) (keys key->attribute)))]
    (when (seq sorters-by-k)
      (fn [{:keys [data-tree]}]
        (let [ks (keys sorters-by-k)]
          (log/debug "Form system sorting data tree children for keys " ks)
          (reduce
            (fn [tree k]
              (if (vector? (get tree k))
                (try
                  (update tree k (comp vec (get sorters-by-k k)))
                  (catch #?(:clj Exception :cljs :default) e
                    (log/error "Sort failed: " (str e))
                    tree))
                tree))
            data-tree
            ks))))))

(defn convert-options
  "Runtime conversion of form options to what comp/configure-component! needs."
  [get-class location options]
  (required! location options ::attributes vector?)
  (required! location options ::id attr/attribute?)
  (let [{::keys [id attributes route-prefix query-inclusion]
         :keys  [will-enter]} options
        id-key                     (::attr/qualified-key id)
        form-field?                (fn [{::attr/keys [identity? computed-value]}] (and
                                                                                    (not computed-value)
                                                                                    (not identity?)))
        attribute-query-inclusions (set (mapcat ::query-inclusion attributes))
        attribute-map              (attr/attribute-map attributes)
        pre-merge                  (form-pre-merge options attribute-map)
        base-options               (merge
                                     {::validator        (attr/make-attribute-validator attributes)
                                      ::control/controls standard-controls
                                      :route-denied      (fn [this relative-root proposed-route]
                                                           #?(:cljs
                                                              (when-let [confirm (or (comp/component-options (get-class) ::confirm) js/confirm)]
                                                                (when (confirm "You will lose unsaved changes. Are you sure?")
                                                                  (dr/retry-route! this relative-root proposed-route)))))}
                                     options
                                     (cond->
                                       {:ident           (fn [_ props] [id-key (get props id-key)])
                                        ::key->attribute attribute-map
                                        :form-fields     (into #{}
                                                           (comp
                                                             (filter form-field?)
                                                             (map ::attr/qualified-key))
                                                           attributes)}
                                       pre-merge (assoc :pre-merge pre-merge)
                                       route-prefix (merge {:route-segment       [route-prefix :action :id]
                                                            :allow-route-change? form-allow-route-change
                                                            :will-leave          (fn [this props] (form-will-leave this))
                                                            :will-enter          (or will-enter
                                                                                   (fn [app route-params]
                                                                                     (form-will-enter app route-params (get-class))))})))
        inclusions                 (set/union attribute-query-inclusions (set query-inclusion))
        query                      (cond-> (form-options->form-query base-options)
                                     (seq inclusions) (into inclusions))]
    (when (and #?(:cljs goog.DEBUG :clj true) (not (string? route-prefix)))
      (log/info "NOTE: " location " does not have a route prefix and will only be usable as a sub-form."))
    (when (and #?(:cljs goog.DEBUG :clj true) will-enter (not route-prefix))
      (log/info "NOTE: There's a :will-enter option in form/defsc-form" location "that will be ignored because ::report/route-prefix is not specified"))
    (assoc base-options :query (fn [_] query))))

#?(:clj
   (defn form-body [argslist body]
     (if (empty? body)
       `[(render-layout ~(first argslist) ~(second argslist))]
       body)))

#?(:clj
   (defn defsc-form*
     [env args]
     (let [{:keys [sym doc arglist options body]} (s/conform ::defsc-form-args args)
           options      (opts/macro-optimize-options env options #{::subforms ::validation-messages ::field-styles} {})
           nspc         (if (comp/cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
           fqkw         (keyword (str nspc) (name sym))
           body         (form-body arglist body)
           [thissym propsym computedsym extra-args] arglist
           location     (str nspc "." sym)
           render-form  (#'comp/build-render sym thissym propsym computedsym extra-args body)
           options-expr `(let [get-class# (fn [] ~sym)]
                           (assoc (convert-options get-class# ~location ~options) :render ~render-form))]
       (when (some #(= '_ %) arglist)
         (throw (ana/error env "The arguments of defsc-form must be unique symbols other than _.")))
       (if (comp/cljs? env)
         `(do
            (declare ~sym)
            (let [options# ~options-expr]
              (defonce ~(vary-meta sym assoc :doc doc :jsdoc ["@constructor"])
                (fn [props#]
                  (cljs.core/this-as this#
                    (if-let [init-state# (get options# :initLocalState)]
                      (set! (.-state this#) (cljs.core/js-obj "fulcro$state" (init-state# this# (goog.object/get props# "fulcro$value"))))
                      (set! (.-state this#) (cljs.core/js-obj "fulcro$state" {})))
                    nil)))
              (com.fulcrologic.fulcro.components/configure-component! ~sym ~fqkw options#)))
         `(do
            (declare ~sym)
            (let [options# ~options-expr]
              (def ~(vary-meta sym assoc :doc doc :once true)
                (com.fulcrologic.fulcro.components/configure-component! ~(str sym) ~fqkw options#))))))))

#?(:clj
   (defmacro defsc-form [& args]
     (try
       (defsc-form* &env args)
       (catch Exception e
         (if (contains? (ex-data e) :tag)
           (throw e)
           (throw (ana/error &env "Unexpected internal error while processing defsc. Please check your syntax." e)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj
   (defn save-form*
     "Internal implementation of clj-side form save. Can be used in your own mutations to accomplish writes through
      the save middleware.

      params MUST contain:

      * `::form/delta` - The data to save. Map keyed by ident whose values are maps with `:before` and `:after` values.
      * `::form/id` - The actual ID of the entity being changed.
      * `::form/master-pk` - The keyword representing the form's ID in your RAD model's attributes.

      Returns:

      {:tempid {} ; tempid remaps
       master-pk id} ; the k/id of the entity saved. The id here will be remapped already if it was a tempid.
      "
     [env params]
     (let [save-middleware (::save-middleware env)
           save-env        (assoc env ::params params)
           result          (if save-middleware
                             (save-middleware save-env)
                             (throw (ex-info "form/pathom-plugin is not installed on the parser." {})))
           {::keys [id master-pk]} params
           {:keys [tempids]} result
           id              (get tempids id id)]
       (merge result {master-pk id}))))

;; do-saves! params/env => return value
;; -> params/env -> middleware-in -> do-saves -> middleware-out
#?(:clj
   (pc/defmutation save-form [env params]
     {::pc/params #{::id ::master-pk ::delta}}
     (save-form* env params))
   :cljs
   (m/defmutation save-form
     "MUTATION: DO NOT USE. See save-as-form mutation for a mutation you can use to leverage the form save mechansims for
      arbitrary purposes."
     [_]
     (action [_] :noop)))

#?(:clj
   (pc/defmutation save-as-form [env params]
     {::pc/params #{::id ::master-pk ::delta}}
     (save-form* env params))
   :cljs
   (m/defmutation save-as-form
     "MUTATION: Run a full-stack write as-if it were the save of a form. This allows you to leverage the save middleware
      to do all of the save magic without using a form. Useful for implementing simple model updates from action buttons.

      Required params:

      :root-ident - The ident of the entity to change

      And ONE of:

      :entity - A flat entity to write at :root-ident
      :delta - A proper form delta, a map ident->attr-key->before-after-map.

      If you specify both, only delta will be used.

      This mutation's ok-action will also update the data in the local state."
     [{:keys [root-ident entity delta]}]
     (ok-action [{:keys [state]}]
       (if delta
         (doseq [[ident changes] delta
                 :let [data-to-merge (reduce-kv
                                       (fn [m k v] (assoc m k (:after v)))
                                       {}
                                       changes)]]
           (swap! state update-in ident merge data-to-merge))
         (swap! state update-in root-ident merge entity)))
     (remote [env]
       (let [delta (or delta
                     {root-ident (reduce-kv
                                   (fn [m k v]
                                     (assoc m k {:after v}))
                                   {}
                                   entity)})]
         (-> env
           (m/with-params {::master-pk (first root-ident)
                           ::id        (second root-ident)
                           ::delta     delta}))))))

#_(defn attr-value
    "UISM helper. When interpreting an event from a form field, this function will extract the pair of:
    [attribute value] from the `env`."
    [uism-env]
    [(-> uism-env ::uism/event-data ::attr/qualified-key)
     (-> uism-env ::uism/event-data :value)])

(defn- start-edit [uism-env _]
  (let [FormClass  (uism/actor-class uism-env :actor/form)
        form-ident (uism/actor->ident uism-env :actor/form)]
    (log/debug "Issuing load of pre-existing form entity" form-ident)
    (-> uism-env
      (uism/load form-ident FormClass {::uism/ok-event    :event/loaded
                                       ::uism/error-event :event/failed})
      (uism/activate :state/loading))))

(declare default-state)

(defn default-to-many
  "Use `default-state` on the top level form. This is part of the recursive implementation.

   Calculate a default value for any to-many attributes on the form. This is part of the recursive algorithm that
   can generate initial state for a new instance of a form.

   If a form has subform configuration that declares a `::form/default` which is a vector, then each element
   in that vector will generate new subform state.

   The result will be a `merge` of:

   ```
   (merge (form/default-state SubformClass id) default-value {id-key id})
   ```

   If no defaults are provided you will at least get something that will normalize properly.

   Example:

   ```
   (defattr people :people :ref
     {::attr/cardinality :many
      ::form/default-value [{}] ; used if form doesn't declare
      ...})

   (defsc Form [this props]
     {::form/id id
      ::form/columns [people]
      ::form/default-values {:people [{} {} {}]} ; overrides what is on attributes
      ::form/subforms {:people {::form/ui Person}}})
   ```

   Default value can be a no-arg function, but the argument list may change in future versions.
   "
  [FormClass attribute]
  (let [{::keys [subforms]} (comp/component-options FormClass)
        {:keys [::attr/qualified-key ::default-value]} attribute
        default       (get-in subforms [qualified-key ::default-values])
        default-value (?! (get default qualified-key default-value))]
    (enc/if-let [SubClass (get-in subforms [qualified-key ::ui])
                 id-key   (some-> SubClass comp/component-options ::id ::attr/qualified-key)]
      (do
        (when-not SubClass
          (log/error "Subforms for class" (comp/component-name FormClass)
            "must include a ::form/ui entry for" qualified-key))
        (when-not (keyword? id-key)
          (log/error "Subform class" (comp/component-name SubClass)
            "must include a ::form/id that is an attr/attribute"))
        (if (or (nil? default-value) (vector? default-value))
          (mapv (fn [v]
                  (let [id (tempid/tempid)]
                    (merge
                      (default-state SubClass id)
                      (?! v)
                      {id-key id})))
            default-value)
          (do
            (log/error "Default value for" qualified-key "MUST be a vector.")
            nil)))
      (do
        (log/error "Subform not declared (or is missing ::form/id) for" qualified-key "on" (comp/component-name FormClass))
        nil))))

(defn default-to-one
  "Use `default-state` on the top level form. This is part of the recursive implementation.

  Generates the default value for a to-one ref in a new instance of a form set. Has the same
  behavior as default-to-many, though the default values must be a map instead of a vector.

  Default value can be a no-arg function, but the argument list may change in future versions.
  "
  [FormClass attribute]
  (let [{::keys [subforms default-values]} (comp/component-options FormClass)
        {::keys [default-value] ::attr/keys [qualified-key]} attribute
        default-value (?! (get default-values qualified-key default-value))
        SubClass      (get-in subforms [qualified-key ::ui])
        new-id        (tempid/tempid)
        id-key        (comp/component-options SubClass ::id ::attr/qualified-key)]
    (when-not SubClass
      (log/error "Subforms for class" (comp/component-name FormClass)
        "must include a ::form/ui entry for" qualified-key))
    (when-not (keyword? id-key)
      (log/error "Subform class" (comp/component-name SubClass)
        "must include a ::form/id that is an attr/attribute"))
    (cond
      id-key
      (merge
        (default-state SubClass new-id)
        (when (map? default-value) default-value)
        {id-key new-id})

      :otherwise
      (do
        (log/error "Subform not declared (or is missing ::form/id) for" qualified-key "on" (comp/component-name FormClass))
        {}))))

(defn default-state
  "Generate a potentially recursive tree of data that represents the tree of initial
  state for the given FormClass. Such generated trees will be rooted with the provided
  `new-id`, and will generate Fulcro tempids for all nested entities. To-one relations
  that have no default will not be included. To-many relations that have no default
  will default to an empty vector.

  The FormClass can have `::form/default-values`, a map from attribute *keyword* to the value
  to give that attribute in new instances of the form. A global default can be set on the
  attribute itself using `::form/default-value`.

  See the doc strings on default-to-one and default-to-many for more information on setting options.

  WARNING: If a rendering field style is given to a ref attribute on a field, then the default value will be
  the *raw* default value declared on the attribute or form, but should generally be nil."
  [FormClass new-id]
  (when-not (tempid/tempid? new-id)
    (throw (ex-info (str "Default state received " new-id " for a new form ID. It MUST be a Fulcro tempid.")
             {})))
  (let [{::keys [id attributes default-values field-styles]} (comp/component-options FormClass)
        {id-key ::attr/qualified-key} id]
    (reduce
      (fn [result {::attr/keys [qualified-key type field-style]
                   ::keys      [default-value] :as attr}]
        (let [field-style   (?! (or (get field-styles qualified-key) field-style))
              default-value (?! (get default-values qualified-key default-value))]
          (cond
            (and (not field-style) (= :ref type) (attr/to-many? attr))
            (assoc result qualified-key (default-to-many FormClass attr))

            (and (not field-style) (= :ref type) (not (attr/to-many? attr)))
            (assoc result qualified-key (default-to-one FormClass attr))

            :otherwise
            (if-not (nil? default-value)
              (assoc result qualified-key default-value)
              result))))
      {id-key new-id}
      attributes)))

(defn route-target-ready
  "Same as dynamic routing target-ready, but works in UISM via env."
  [{::uism/keys [state-map] :as env} target]
  (let [router-id (dr/router-for-pending-target state-map target)]
    (if router-id
      (do
        (log/debug "Router" router-id "notified that pending route is ready.")
        (uism/trigger env router-id :ready!))
      (do
        (log/error "dr/target-ready! was called but there was no router waiting for the target listed: " target
          "This could mean you sent one ident, and indicated ready on another.")
        env))))

(defn mark-fields-complete*
  "Helper function against app state. This function marks `target-keys` as complete on the form given a set of
   keys that you consider initialized. Like form state's mark-complete, but on all of the target-keys that appear
   on the form or subforms recursively."
  [state-map {:keys [entity-ident target-keys]}]
  (let [mark-complete* (fn [entity {::fs/keys [fields complete?] :as form-config}]
                         (let [to-mark (set/union (set complete?) (set/intersection (set fields) (set target-keys)))]
                           [entity (assoc form-config ::fs/complete? to-mark)]))]
    (fs/update-forms state-map mark-complete* entity-ident)))

(defn ^:deprecated mark-filled-fields-complete*
  "Mark fields complete. Use `mark-fields-complete*` instead, but note the signature change."
  [state-map {:keys [entity-ident initialized-keys]}]
  (mark-fields-complete* state-map {:entity-ident entity-ident :target-keys initialized-keys}))

(defn- all-keys [m]
  (reduce-kv
    (fn [result k v]
      (cond-> (conj result k)
        (map? v) (into (all-keys v))))
    #{}
    m))

(defn- start-create [uism-env start-params]
  (let [form-overrides   (:initial-state start-params)
        FormClass        (uism/actor-class uism-env :actor/form)
        routeable?       (boolean (get (comp/component-options FormClass) ::route-prefix))
        form-ident       (uism/actor->ident uism-env :actor/form)
        id               (second form-ident)
        initial-state    (merge (default-state FormClass id) form-overrides)
        entity-to-merge  (fs/add-form-config FormClass initial-state)
        initialized-keys (all-keys initial-state)
        optional-keys    (optional-fields FormClass)]
    (-> uism-env
      (uism/apply-action merge/merge-component FormClass entity-to-merge)
      (uism/apply-action mark-fields-complete* {:entity-ident     form-ident
                                                :target-keys (set/union initialized-keys optional-keys)})
      (cond-> routeable? (route-target-ready form-ident))
      (uism/activate :state/editing))))

(defn leave-form
  "Discard all changes, and attempt to change route."
  [{::uism/keys [fulcro-app] :as uism-env}]
  (let [Form         (uism/actor-class uism-env :actor/form)
        form-ident   (uism/actor->ident uism-env :actor/form)
        state-map    (app/current-state fulcro-app)
        cancel-route (?! (some-> Form comp/component-options ::cancel-route) fulcro-app (fns/ui->props state-map Form form-ident))
        {:keys [on-cancel]} (uism/retrieve uism-env :options)
        error!       (fn [msg] (log/error "The cancel-route option of" (comp/component-name Form) (str "(" cancel-route ")") msg))]
    (cond
      (map? cancel-route) (let [{:keys [route target params]} cancel-route]
                            (cond
                              (comp/component-class? target) (rad-routing/route-to! fulcro-app target (or params {}))
                              (every? string? route) (dr/change-route! fulcro-app route params)
                              :else (do
                                      (error! "did not return a valid route.")
                                      :back)))
      (= :none cancel-route) nil
      (= :back cancel-route) (if (history/history-support? fulcro-app)
                               (history/back! fulcro-app)
                               (error! "Back not supported. No history installed."))
      (and (seq cancel-route) (every? string? cancel-route)) (dr/change-route! fulcro-app cancel-route)
      (comp/component-class? cancel-route) (rad-routing/route-to! fulcro-app cancel-route {})
      (history/history-support? fulcro-app) (history/back! fulcro-app))
    (-> uism-env
      (cond->
        on-cancel (uism/transact on-cancel))
      (uism/store :abandoned? true)
      (uism/apply-action fs/pristine->entity* form-ident))))

(>defn calc-diff
  "Calculates the minimal form diff from the UISM env of the master form's state machine."
  [uism-env]
  [::uism/env => (s/keys :req [::delta])]
  (let [{::uism/keys [state-map]} uism-env
        form-ident (uism/actor->ident uism-env :actor/form)
        Form       (uism/actor-class uism-env :actor/form)
        props      (fns/ui->props state-map Form form-ident)
        delta      (fs/dirty-fields props true)]
    {::delta delta}))

(def global-events
  {:event/exit          {::uism/handler (fn [env] (uism/exit env))}
   :event/mark-complete {::uism/handler (fn [env]
                                          (let [form-ident (uism/actor->ident env :actor/form)]
                                            (uism/apply-action env fs/mark-complete* form-ident)))}
   :event/route-denied  {::uism/handler (fn [env] env)}})

(defn mark-all-complete! [master-form-instance]
  (uism/trigger! master-form-instance (comp/get-ident master-form-instance) :event/mark-complete))

(defn auto-create-to-one
  "Create any to-one referenced entities that did not load, but which are marked as auto-create."
  [{::uism/keys [state-map] :as env}]
  (let [FormClass       (uism/actor-class env :actor/form)
        form-ident      (uism/actor->ident env :actor/form)
        form-value      (get-in state-map form-ident)
        {::keys [subforms attributes]} (comp/component-options FormClass)
        possible-keys   (set (keys subforms))
        attrs-to-create (into []
                          (filter (fn [{::attr/keys [qualified-key type cardinality]}]
                                    (and
                                      (true? (get-in subforms [qualified-key ::autocreate-on-load?]))
                                      (nil? (get form-value qualified-key))
                                      (contains? possible-keys qualified-key)
                                      (= :ref type)
                                      (or (= :one) (nil? cardinality)))))
                          attributes)]
    (reduce
      (fn [env {::attr/keys [qualified-key target] :as attr}]
        (let [{::keys [ui]} (get subforms qualified-key)
              id         (tempid/tempid)
              new-entity (default-state ui id)
              new-ident  [target id]]
          (when-not ui (log/error "::form/ui missing in subforms for autocreate target" qualified-key))
          (when-not target (log/error "Reference attribute is missing ::attr/target" qualified-key))
          (-> env
            (uism/apply-action assoc-in (conj form-ident qualified-key) new-ident)
            (uism/apply-action assoc-in new-ident new-entity))))
      env
      attrs-to-create)))

(defn update-tree*
  "Run the given `(xform ui-props)` against the current ui props of `component-class`'s instance at `component-ident`
  in `state-map`. Returns an updated state map with the transformed ui-props re-normalized and merged back into app state."
  [state-map xform component-class component-ident]
  (p ::update-tree*
    (if (and xform component-class component-ident)
      (let [ui-props      (fns/ui->props state-map component-class component-ident)
            new-ui-props  (xform ui-props)
            new-state-map (merge/merge-component state-map component-class new-ui-props)]
        new-state-map)
      state-map)))

(defn apply-derived-calculations
  "Apply derived calcuations to the form using the UISM env of the master form. Derived calculations are configured on
   the form via `::form/triggers` `:derive-fields` function (a fn of ui props that must return new ui props).

   Derived field calculations are first performed on the (sub)form on which the attribute that changed exists, and then
   via any defined trigger on the master form (assuming it isn't the same form).

   The `:derive-fields` functions should be pure functions."
  [{::uism/keys [event-data] :as env}]
  (p ::apply-derived-calculations
    (let [{:keys [form-key form-ident]} event-data
          form-class        (some-> form-key (comp/registry-key->class))
          master-form-class (uism/actor-class env :actor/form)
          master-form-ident (uism/actor->ident env :actor/form)
          {{master-derive-fields :derive-fields} ::triggers} (comp/component-options master-form-class)
          {{:keys [derive-fields]} ::triggers} (some-> form-class (comp/component-options))]
      (cond-> env
        derive-fields (uism/apply-action update-tree* derive-fields form-class form-ident)
        (and (not= master-form-class form-class) master-derive-fields) (uism/apply-action update-tree* master-derive-fields master-form-class master-form-ident)))))

(defstatemachine form-machine
  {::uism/actors
   #{:actor/form}

   ::uism/aliases
   {:confirmation-message [:actor/form :ui/confirmation-message]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [event-data]} env
                            {::keys [create?]} event-data]
                        (cond-> (uism/store env :options event-data)
                          create? (start-create event-data)
                          (not create?) (start-edit event-data))))}

    :state/loading
    {::uism/events
     (merge global-events
       {:event/loaded
        {::uism/handler
         (fn [env]
           (log/debug "Loaded. Marking the form complete.")
           (let [FormClass  (uism/actor-class env :actor/form)
                 form-ident (uism/actor->ident env :actor/form)]
             (-> env
               (auto-create-to-one)
               (uism/apply-action fs/add-form-config* FormClass form-ident {:destructive? true})
               (uism/apply-action fs/mark-complete* form-ident)
               (route-target-ready form-ident)
               (uism/activate :state/editing))))}
        :event/failed
        {::uism/handler
         (fn [env]
           ;; FIXME: error handling
           #?(:cljs (js/alert "Load failed"))
           env)}})}

    :state/asking-to-discard-changes
    {::uism/events
     (merge
       global-events
       {:event/ok     {::uism/handler leave-form}
        :event/cancel {::uism/handler (fn [env] (uism/activate env :state/editing))}})}

    :state/saving
    {::uism/events
     (merge
       global-events
       {:event/save-failed
        {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}]
                          (let [{:keys [on-save-failed]} (uism/retrieve env :options)]
                            (cond-> (uism/activate env :state/editing)
                              on-save-failed (uism/transact on-save-failed))))}
        :event/saved
        {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}]
                          (let [form-ident (uism/actor->ident env :actor/form)
                                {:keys [on-saved]} (uism/retrieve env :options)]
                            (when (history/history-support? fulcro-app)
                              (let [{:keys [route params]} (history/current-route fulcro-app)
                                    new-route (into (vec (drop-last 2 route)) [edit-action (str (second form-ident))])]
                                (history/replace-route! fulcro-app new-route params)))
                            (-> env
                              (cond-> on-saved (uism/transact on-saved))
                              (uism/apply-action fs/entity->pristine* form-ident)
                              (uism/activate :state/editing))))}})}

    :state/editing
    {::uism/events
     (merge
       global-events
       {:event/attribute-changed
        {::uism/handler
         (fn [{::uism/keys [event-data] :as env}]
           ;; NOTE: value at this layer is ALWAYS typed to the attribute.
           ;; The rendering layer is responsible for converting the value to/from
           ;; the representation needed by the UI component (e.g. string)
           (p :event/attribute-changed
             (let [{:keys       [old-value form-key value form-ident]
                    ::attr/keys [cardinality type qualified-key]} event-data
                   form-class          (some-> form-key (comp/registry-key->class))
                   {{:keys [on-change]} ::triggers} (some-> form-class (comp/component-options))
                   many?               (= :many cardinality)
                   ref?                (= :ref type)
                   missing?            (nil? value)
                   value               (cond
                                         (and ref? many? (nil? value)) []
                                         (and many? (nil? value)) #{}
                                         (and ref? many?) (filterv #(not (nil? (second %))) value)
                                         ref? (if (nil? (second value)) nil value)
                                         :else value)
                   protected-on-change (fn [env]
                                         (let [new-env (on-change env form-ident qualified-key old-value value)]
                                           (if (or (nil? new-env) (contains? new-env ::uism/state-map))
                                             new-env
                                             (do
                                               (log/error "Invalid on-change handler! It MUST return an updated env!")
                                               env))))
                   path                (when (and form-ident qualified-key)
                                         (conj form-ident qualified-key))
                   ;; TODO: Decide when to properly set the field to marked
                   mark-complete?      true]
               (when #?(:clj true :cljs goog.DEBUG)
                 (when-not path
                   (log/error "Unable to record attribute change. Path cannot be calculated."))
                 (when (and ref? many? (not (every? eql/ident? value)))
                   (log/error "Setting a ref-many attribute to incorrect type. Value should be a vector of idents:" qualified-key value))
                 (when (and ref? (not many?) (not missing?) (not (eql/ident? value)))
                   (log/error "Setting a ref-one attribute to incorrect type. Value should an ident:" qualified-key value)))
               (-> env
                 (cond->
                   mark-complete? (uism/apply-action fs/mark-complete* form-ident qualified-key)
                   (and path (nil? value)) (uism/apply-action update-in form-ident dissoc qualified-key)
                   (and path (not (nil? value))) (uism/apply-action assoc-in path value)
                   on-change (protected-on-change))
                 (apply-derived-calculations)))))}

        :event/blur
        {::uism/handler (fn [env] env)}

        :event/add-row
        {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                          (let [{::keys [order parent-relation parent child-class]} event-data
                                target-path          (conj (comp/get-ident parent) parent-relation)
                                new-child            (default-state child-class (tempid/tempid))
                                child-ident          (comp/get-ident child-class new-child)
                                mark-fields-complete (fn [state-map]
                                                       (reduce
                                                         (fn [s k]
                                                           (fs/mark-complete* s child-ident k))
                                                         state-map
                                                         (keys new-child)))]
                            (-> env
                              (uism/apply-action
                                (fn [s]
                                  (-> s
                                    (merge/merge-component child-class new-child (or order :append) target-path)
                                    (fs/add-form-config* child-class child-ident)
                                    (mark-fields-complete))))
                              (apply-derived-calculations))))}

        :event/delete-row
        {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                          (let [{::keys [form-instance parent parent-relation]} event-data
                                child-ident (comp/get-ident form-instance)
                                path        (and parent (conj (comp/get-ident parent) parent-relation))]
                            (when path
                              (-> env
                                (uism/apply-action fns/remove-ident child-ident path)
                                (apply-derived-calculations)))))}

        :event/save
        {::uism/handler (fn [{::uism/keys [state-map event-data] :as env}]
                          (let [form-class          (uism/actor-class env :actor/form)
                                form-ident          (uism/actor->ident env :actor/form)
                                master-pk           (-> form-class comp/component-options (get-in [::id ::attr/qualified-key]))
                                proposed-form-props (fs/completed-form-props state-map form-class form-ident)]
                            (if (valid? form-class proposed-form-props)
                              (let [data-to-save (calc-diff env)
                                    params       (merge event-data data-to-save)]
                                (-> env
                                  (uism/trigger-remote-mutation :actor/form `save-form
                                    (merge params
                                      {::uism/error-event :event/save-failed
                                       ::master-pk        master-pk
                                       ::id               (second form-ident)
                                       ::m/returning      form-class
                                       ::uism/ok-event    :event/saved}))
                                  (uism/activate :state/saving)))
                              (-> env
                                (uism/apply-action fs/mark-complete* form-ident)
                                (uism/activate :state/editing)))))}

        :event/reset
        {::uism/handler (fn [env]
                          (let [form-ident (uism/actor->ident env :actor/form)]
                            (uism/apply-action env fs/pristine->entity* form-ident)))}

        :event/cancel
        {::uism/handler leave-form}})}}})

(defn save!
  "Trigger a save on the given form rendering env."
  [{this ::master-form :as form-rendering-env}]
  (uism/trigger! this (comp/get-ident this) :event/save {}))

(defn undo-all!
  "Trigger an undo of all changes on the given form rendering env."
  [{this ::master-form}]
  (uism/trigger! this (comp/get-ident this) :event/reset {}))

(defn cancel!
  "Trigger a cancel of all changes on the given form rendering env. This is like undo, but attempts to route away from
   the form."
  [{this ::master-form}]
  (uism/trigger! this (comp/get-ident this) :event/cancel {}))

(defn add-child!
  "Add a child. You must pass a form rendering environment that includes additional keys:

  ```
  (form/add-child! (assoc env
                     ::form/order :prepend
                     ::form/parent-relation :person/addresses
                     ::form/parent form-instance
                     ::form/child-class ui))
  ```

  See renderers for usage examples.
  "
  [{::keys [master-form] :as env}]
  (let [asm-id (comp/get-ident master-form)]
    (uism/trigger! master-form asm-id :event/add-row env)))

(defn delete-child!
  "Delete a child of a master form. Only use this on nested forms that are actively being edited. See
   also `delete!`. The rendering env that you pass to this function must be the rendering env passed *to* the
   child that is to be deleted.

   If you are rendering the child yourself via the body of a subform UI (which is a defsc-form), then
   you must set the `env` at `::form/form-instance` to `this`.
   "
  [{::keys [master-form] :as env}]
  (let [asm-id (comp/get-ident master-form)]
    (uism/trigger! master-form asm-id :event/delete-row env)))

(defn read-only?
  "Returns true if the given attribute is meant to show up as read only on the given form instance. Attributes
  configure this by placing a boolean value (or function returning boolean) on the attribute at `::attr/read-only?`.

  The form's options may also include `::form/read-only-fields` as a set (or a function returning a set) of the keys that should
  currently be considered read-only. If it is a function it will only be passed the form instance.

  If the form has a `::form/read-only?` option that is `true` (or a `(fn [form-instance] boolean?)` that returns true) then
  *everything* on the form will be read-only.

  If you use a function for read only detection it will be passed the `form-instance` and the `attribute` being
  checked. You may reach into app state to examine things, but beware that doing so may not dynamically update
  as you'd expect."
  [form-instance {::attr/keys [qualified-key identity? read-only? computed-value] :as attr}]
  [comp/component? ::attr/attribute => boolean?]
  (let [{::keys          [read-only-fields]
         read-only-form? ::read-only?} (comp/component-options form-instance)
        master-form       (comp/get-computed form-instance ::master-form)
        master-read-only? (some-> master-form (comp/component-options ::read-only?))]
    (boolean
      (or
        (?! read-only-form? form-instance)
        (?! master-read-only? master-form)
        identity?
        (?! read-only? form-instance attr)
        computed-value
        (let [read-only-fields (?! read-only-fields form-instance)]
          (and (set? read-only-fields) (contains? read-only-fields qualified-key)))))))

(defn field-visible?
  "Should the `attr` on the given `form-instance` be visible? This is controlled:

  * On the attribute at `::form/field-visible?`. A boolean or `(fn [form-instance attr] boolean?)`
  * On the form via the map `::form/fields-visible?`. A map from attr keyword to boolean or `(fn [form-instance attr] boolean?)`

  A field is visible if the form says it is. If the form has *no opinion*, then it is visible if the attribute
  says it is (as true?). If neither the form nor attribute return a boolean, then the field is visible.
  "
  [form-instance {::keys      [field-visible?]
                  ::attr/keys [qualified-key] :as attr}]
  [comp/component? ::attr/attribute => boolean?]
  (let [form-field-visible? (?! (comp/component-options form-instance ::fields-visible? qualified-key) form-instance attr)
        field-visible?      (?! field-visible? form-instance attr)]
    (boolean
      (or
        (true? form-field-visible?)
        (and (nil? form-field-visible?) (true? field-visible?))
        (and (nil? form-field-visible?) (nil? field-visible?))))))

(defn view!
  "Route to the given form in read-only mode."
  ([this form-class entity-id]
   (rad-routing/route-to! this form-class {:action view-action
                                           :id     entity-id}))
  ([this form-class entity-id extra-params]
   (rad-routing/route-to! this form-class (merge extra-params
                                            {:action view-action
                                             :id     entity-id}))))

(defn edit!
  "Route to the given form for editing the entity with the given ID."
  ([this form-class entity-id]
   (rad-routing/route-to! this form-class {:action edit-action
                                           :id     entity-id}))
  ([this form-class entity-id extra-params]
   (rad-routing/route-to! this form-class (merge extra-params
                                            {:action edit-action
                                             :id     entity-id}))))

(defn create!
  "Create a new instance of the given form-class using the provided `entity-id` and then route
   to that form for editing.

   - `app-ish`: A component instance or the app.
   - `form-class`: The form to create.
   - `options` map will be passed to the form as extra options.

   The `options` in the default form state machine can contain:

   * `:initial-state` - A tree of data to be deep-merged into the new instance of the form before form config
   is added. This can be used to pre-set form fields to specific values.
   "
  ([app-ish form-class]
   ;; This function uses UUIDs for all ID types, since they will end up being tempids
   ;; which are UUID-based.
   (rad-routing/route-to! app-ish form-class {:action create-action
                                              :id     (str (new-uuid))}))
  ([app-ish form-class options]
   (rad-routing/route-to! app-ish form-class (merge options
                                               {:action create-action
                                                :id     (str (new-uuid))}))))

#?(:clj
   (pc/defmutation delete-entity [env params]
     {}
     (if-let [delete-middleware (::delete-middleware env)]
       (let [delete-env (assoc env ::params params)]
         (delete-middleware delete-env))
       (throw (ex-info "form/pathom-plugin in not installed on Pathom parser." {}))))
   :cljs
   (m/defmutation delete-entity [params]
     (ok-action [{:keys [state]}]
       (let [target-ident (first params)]
         (swap! state fns/remove-entity target-ident)))
     (remote [_] true)))

(defn delete!
  "Delete the given entity from local app state and the remote (if present). This method assumes that the
   given entity is *not* currently being edited and can be used from anyplace else in the application."
  [this id-key entity-id]
  #?(:cljs
     (comp/transact! this [(delete-entity {id-key entity-id})])))

(defn input-blur!
  "Helper: Informs the form's state machine that focus has left an input. Requires a form rendering env, attr keyword,
   and the current value."
  [{::keys [form-instance master-form]} k value]
  (let [form-ident (comp/get-ident form-instance)
        asm-id     (comp/get-ident master-form)]
    (uism/trigger! master-form asm-id :event/blur
      {::attr/qualified-key k
       :form-ident          form-ident
       :value               value})))

(defn input-changed!
  "Helper: Informs the form's state machine that an input's value has changed. Requires a form rendering env, attr keyword,
   and the current value.

   Using a value of `nil` will cause the field to become empty in an attribute-aware way:

   - If the cardinality is to-one, will be dissoc'd
   - Scalar to-many will be set to #{} instead.
   - Ref to-many will be set to [] instead.

   Furthermore, idents that contain a nil ID are considered nil."
  [{::keys [form-instance master-form] :as env} k value]
  (let [form-ident (comp/get-ident form-instance)
        old-value  (get (comp/props form-instance) k)
        asm-id     (comp/get-ident master-form)]
    (uism/trigger!! form-instance asm-id :event/attribute-changed
      {::attr/qualified-key k
       :form-ident          form-ident
       :form-key            (comp/class->registry-key (comp/react-type form-instance))
       :old-value           old-value
       :value               value})))

(defn computed-value
  "Returns the computed value of the given attribute on the form from `env` (if it is a computed attribute).

  Computed attributes are regular attributes with no storage (though they may have resolvers) and a `::attr/computed-value`
  function. Such a function will be called with the form rendering env and the attribute definition itself."
  [env {::attr/keys [computed-value] :as attr}]
  (when computed-value
    (computed-value env attr)))

(def ^:deprecated install-ui-controls!
  "Renamed to rad-application/install-ui-controls!"
  rapp/install-ui-controls!)

(defn field-label
  "Returns a human readable label for a given attribute (which can be declared on the attribute, and overridden on the
  specific form). Defaults to the capitalized name of the attribute qualified key. Labels can be configured
  on the form that renders them or on the attribute. The form overrides the attribute.

  * On an attribute `::form/field-label`: A string or function returning a string.
  * On a form `::form/field-labels`: A map from attribute keyword to a string or function returning a string.

  If label functions are used they are passed the form instance that is rendering them. They must not side-effect.
  "
  [form-env attribute]
  (let [{::keys [form-instance]} form-env
        k           (::attr/qualified-key attribute)
        options     (comp/component-options form-instance)
        field-label (?! (or
                          (get-in options [::field-labels k])
                          (::field-label attribute)
                          (some-> k name str/capitalize (str/replace #"-" " "))) form-instance)]
    field-label))

(defn invalid?
  "Returns true if the validator on the form in `env` indicates that some form field(s) are invalid. Note that a
  field does not report valid OR invalid until it is marked complete (usually on blur)."
  ([form-rendering-env]
   (let [{::keys [form-instance]} form-rendering-env
         props (comp/props form-instance)]
     (invalid? form-instance props)))
  ([form-class-or-instance props]
   (let [{::keys [validator]} (comp/component-options form-class-or-instance)]
     (and validator (= :invalid (validator props))))))

(defn valid?
  "Returns true if the validator on the form in `env` indicates that all of the form fields are valid. Note that a
  field does not report valid OR invalid until it is marked complete (usually on blur)."
  ([form-rendering-env]
   (let [{::keys [form-instance]} form-rendering-env
         props (comp/props form-instance)]
     (valid? form-instance props)))
  ([form-class-or-instance props]
   (let [{::keys [attributes validator]} (comp/component-options form-class-or-instance)
         required-attributes   (into #{}
                                 (comp
                                   (filter ::attr/required?)
                                   (map ::attr/qualified-key)) attributes)
         all-required-present? (or
                                 (empty? required-attributes)
                                 (every? #(not (nil? (get props %))) required-attributes))]
     #?(:cljs
        (when goog.DEBUG
          (when (not (empty? required-attributes))
            (doseq [k required-attributes]
              (when (nil? (get props k))
                (log/info "Form is not valid because required attribute is missing:" k))))))
     (and
       all-required-present?
       (or
         (not validator)
         (and validator (= :valid (validator props))))))))

(>defn field-style-config
  "Get the value of an overridable field-style-config option. If both the form and attribute set these
  then the result will be a deep merge of the two (with form winning)."
  [{::keys [form-instance]} attribute config-key]
  [::form-env ::attr/attribute keyword? => any?]
  (let [{::attr/keys [qualified-key field-style-config]} attribute
        form-value      (comp/component-options form-instance ::field-style-configs qualified-key config-key)
        attribute-value (get field-style-config config-key)]
    (if (and (map? form-value) (map? attribute-value))
      (deep-merge attribute-value form-value)
      (or form-value attribute-value))))

(>defn field-autocomplete
  "Returns the proper string (or nil) for a given attribute's autocomplete setting"
  [{::keys [form-instance] :as env} attribute]
  [::form-env ::attr/attribute => any?]
  (let [{::attr/keys [qualified-key]
         ::keys      [autocomplete]} attribute
        override     (comp/component-options form-instance ::auto-completes qualified-key)
        autocomplete (if (nil? override) autocomplete override)
        autocomplete (if (boolean? autocomplete) (if autocomplete "on" "off") autocomplete)]
    autocomplete))

(defn pathom-plugin
  "A pathom plugin that installs general form save/delete support on the pathom parser. Requires
  save and delete middleware, which will accomplish the actual actions.  Calling RAD form save/delete
  without this plugin and both bits of middleware will result in a runtime error."
  [save-middleware delete-middleware]
  (p/env-wrap-plugin
    (fn [env]
      (assoc env
        ::save-middleware save-middleware
        ::delete-middleware delete-middleware))))

#?(:clj (def resolvers
          "Form save and delete mutation resolvers. These must be installed on your pathom parser for saves and deletes to
           work, and you must also install save and delete middleware into your pathom env per the instructions of your
           database adapter."
          [save-form delete-entity save-as-form]))

(comment
  (opts/resolve-key {} `com.fulcrologic.rad.form-options/field-options))

