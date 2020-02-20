(ns com.fulcrologic.rad.form
  #?(:cljs (:require-macros [com.fulcrologic.rad.form]))
  (:require
    [clojure.spec.alpha :as s]
    [clojure.set :as set]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.errors :refer [required!]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.rpl.specter :as sp]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    #?(:clj [cljs.analyzer :as ana])
    #?(:cljs [goog.object])
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]))

(def create-action "create")
(def edit-action "edit")
(declare form-machine valid? invalid?)

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

(defn master-form
  "Return the master form for the given component instance."
  [component]
  (or (some-> component comp/get-computed ::master-form) component))

(def data-type->field-type {:string :text})

(defn attr->renderer [{::keys [form-instance]} {::attr/keys [type qualified-key]
                                                ::keys      [field-style]}]
  (let [{::app/keys [runtime-atom]} (comp/any->app form-instance)
        field-style (or
                      (some-> form-instance comp/component-options ::field-styles qualified-key)
                      field-style
                      :default)
        control-map (some-> runtime-atom deref :com.fulcrologic.rad/controls ::type->style->control)
        control     (or
                      (get-in control-map [type field-style])
                      (do
                        (log/warn "Renderer not found: " type field-style)
                        (get-in control-map [type :default])))]
    control))

(defn render-field [env attr]
  (let [render (attr->renderer env attr)]
    (if render
      (render env attr)
      (do
        (log/error "No renderer installed to support attribute" attr)
        nil))))

(defn rendering-env [form-instance props]
  (let [{::app/keys [runtime-atom]} (comp/any->app form-instance)
        cprops (comp/get-computed props)]
    (merge cprops
      {::master-form    (master-form form-instance)
       ::form-instance  form-instance
       ::props          props
       ::computed-props cprops})))

(defn render-layout [form-instance props]
  (let [{::app/keys [runtime-atom]} (comp/any->app form-instance)
        layout-style (or (some-> form-instance comp/component-options ::layout-style) :default)
        layout       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::style->layout layout-style)]
    (if layout
      (layout (rendering-env form-instance props))
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

#?(:clj
   (s/def ::defsc-form-args (s/cat
                              :sym symbol?
                              :doc (s/? string?)
                              :arglist (s/and vector? #(<= 2 (count %) 5))
                              :options map?
                              :body (s/* any?))))

#?(:clj
   (s/def ::defsc-form-options (s/keys :req [::attr/attributes])))

;; NOTE: This MUST be used within a lambda in the component, not as a static bit of query at compile time.
(defn form-options->form-query
  "Converts form options to a proper EQL query."
  [form-options]
  (let [attr               (::attributes form-options)
        id-attr            (::id form-options)
        id-key             (::attr/qualified-key id-attr)
        {refs true scalars false} (group-by #(= :ref (::attr/type %)) attr)
        query-with-scalars (into
                             [id-key
                              :ui/confirmation-message
                              [::uism/asm-id '_]
                              {::blob/blobs (comp/get-query blob/Blob)}
                              fs/form-config-join]
                             (map ::attr/qualified-key)
                             scalars)
        subforms           (::subforms form-options)
        full-query         (into query-with-scalars
                             (mapcat (fn [{::attr/keys [qualified-key]}]
                                       (required! (str "Form attribute " qualified-key
                                                    " is a reference type. The ::form/subforms map")
                                         subforms qualified-key #(contains? % ::ui))
                                       (let [subform (get-in subforms [qualified-key ::ui])
                                             picker? (boolean (get-in subforms [qualified-key ::pick-one]))]
                                         (if picker?
                                           (let [picker-key (picker-join-key qualified-key)]
                                             [qualified-key {picker-key (comp/get-query subform)}])
                                           [{qualified-key (comp/get-query subform)}]))))
                             refs)]
    full-query))

(defn- valid-uuid-string? [s]
  (boolean (and
             (string? s)
             (re-matches #"^........-....-....-....-............$" s))))

(defn form-will-enter
  "Used as the implementation and return value of a form target's will-enter."
  [app {:keys [action id]} form-class]
  (let [new?       (= create-action action)
        uuid       (if new?
                     (tempid/tempid (new-uuid id))
                     (new-uuid id))
        id-prop    (comp/component-options form-class ::id ::attr/qualified-key)
        form-ident [id-prop uuid]]
    (when-not (keyword? id-prop)
      (log/error "Form " (comp/component-name form-class) " does not have a ::form/id that is an attr/attribute."))
    (when (and new? (not (valid-uuid-string? id)))
      (log/error (comp/component-name form-class) "Invalid UUID string " id "used in route. The form may misbehave."))
    (dr/route-deferred form-ident
      (fn []
        (uism/begin! app form-machine
          form-ident
          {:actor/form (uism/with-actor-class form-ident form-class)}
          {::create? new?})))))

(defn form-will-leave
  "Used as a form route target's will-enter."
  [this form-props]
  (let [id         (comp/get-ident this)
        abandoned? (= :state/abandoned (uism/get-active-state this id))
        dirty?     (and (not abandoned?) (fs/dirty? form-props))]
    (when dirty? (uism/trigger! this id :event/route-denied))
    (not dirty?)))

(defn convert-options
  "Runtime conversion of form options to what comp/configure-component! needs."
  [get-class location options]
  (required! location options ::attributes vector?)
  (required! location options ::id attr/attribute?)
  (let [{::keys [id attributes route-prefix query-inclusion]} options
        id-key       (::attr/qualified-key id)
        form-field?  (fn [{::attr/keys [identity?]}] (not identity?))
        base-options (merge
                       {::validator (attr/make-attribute-validator attributes)}
                       options
                       (cond->
                         {:ident       (fn [_ props] [id-key (get props id-key)])
                          :form-fields (into #{::blob/blobs}
                                         (comp
                                           (filter form-field?)
                                           (map ::attr/qualified-key))
                                         attributes)}
                         route-prefix (merge {:route-segment [route-prefix :action :id]
                                              :will-leave    form-will-leave
                                              :will-enter    (fn [app route-params] (form-will-enter app route-params (get-class)))})))
        query        (cond-> (form-options->form-query base-options)
                       (vector? query-inclusion) (into query-inclusion))]
    (when (and #?(:cljs goog.DEBUG :clj true) (not (string? route-prefix)))
      (log/info "NOTE: " location " does not have a route prefix and will only be usable as a sub-form."))
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
           nspc         (if (comp/cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
           fqkw         (keyword (str nspc) (name sym))
           body         (form-body arglist body)
           [thissym propsym computedsym extra-args] arglist
           location     (str nspc "." sym)
           render-form  (#'comp/build-render sym thissym propsym computedsym extra-args body)
           options-expr `(let [get-class# (fn [] ~sym)]
                           (assoc (convert-options get-class# ~location ~options) :render ~render-form))]
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

;; do-saves! params/env => return value
;; -> params/env -> middleware-in -> do-saves -> middleware-out
#?(:clj
   (pc/defmutation save-form [env params]
     {::pc/params #{::master-pk ::diff ::delta}}
     (log/debug "Save invoked from client with " params)
     (let [save-middleware (::save-middleware env)
           save-env        {::pathom-env env ::params params}
           result          (if save-middleware
                             (save-middleware save-env)
                             (throw (ex-info "form/pathom-plugin is not installed on the parser." {})))
           {::keys [master-pk delta]} params
           idents          (keys delta)
           pk              (sp/select-first [sp/ALL #(= master-pk (first %)) sp/LAST] idents)]
       (merge result {master-pk pk})))
   :cljs
   (m/defmutation save-form [_]
     (action [_] :noop)))

;; TODO: Support for a generalized focus mechanism to show the first field that has a problem

;; TODO: Allow form to override validation on a field, with fallback to what is declared on the attribute

(defn config [env] (uism/retrieve env :config))

(defn attr-value
  "When interpreting an event from a form field, this function will extract the pair of:
  [attribute value] from the `env`."
  [env]
  [(-> env ::uism/event-data ::attr/qualified-key)
   (-> env ::uism/event-data :value)])

(defn- start-edit [env _]
  (let [FormClass  (uism/actor-class env :actor/form)
        form-ident (uism/actor->ident env :actor/form)]
    (log/debug "Issuing load of pre-existing form entity" form-ident)
    (-> env
      (uism/load form-ident FormClass {::uism/ok-event    :event/loaded
                                       ::uism/error-event :event/failed})
      (uism/activate :state/loading))))

(declare default-state)

(defn- default-to-many [FormClass attribute]
  (let [{::keys [subforms default]} (comp/component-options FormClass)
        {::attr/keys [qualified-key default-value]} attribute
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

(defn- default-to-one [FormClass attribute]
  (let [{::keys [subforms default]} (comp/component-options FormClass)
        {::attr/keys [qualified-key default-value]} attribute
        default-value (?! (get default qualified-key default-value))
        SubClass      (get-in subforms [qualified-key ::ui])
        picker?       (boolean (get-in subforms [qualified-key ::pick-one]))
        new-id        (tempid/tempid)
        id-key        (comp/component-options SubClass ::id ::attr/qualified-key)]
    (when-not SubClass
      (log/error "Subforms for class" (comp/component-name FormClass)
        "must include a ::form/ui entry for" qualified-key))
    (when-not (or picker? (keyword? id-key))
      (log/error "Subform class" (comp/component-name SubClass)
        "must include a ::form/id that is an attr/attribute"))
    (cond
      ;; to-one picker can start out pointing at nothing
      (and SubClass picker?)
      nil

      picker?
      (do
        (log/error "Picker does not have a ::ui in ::subforms")
        nil)

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
  will default to an empty vector."
  [FormClass new-id]
  (when-not (tempid/tempid? new-id)
    (throw (ex-info (str "Default state received " new-id " for a new form ID. It MUST be a Fulcro tempid.")
             {})))
  (let [{::keys [id attributes default subforms]} (comp/component-options FormClass)
        {id-key ::attr/qualified-key} id]
    (reduce
      (fn [result {::attr/keys [qualified-key type default-value] :as attr}]
        (let [default-value (?! (get default qualified-key default-value))
              picker?       (some-> subforms (get-in [qualified-key ::pick-one]) (boolean))
              picker-state  (some-> subforms (get-in [qualified-key ::ui]) (comp/get-initial-state {:id (new-uuid)}))]
          (cond
            (and (= :ref type) (attr/to-many? qualified-key))
            (assoc result qualified-key (default-to-many FormClass attr))

            (and (= :ref type) (not (attr/to-many? qualified-key)))
            (cond-> (assoc result qualified-key (default-to-one FormClass attr))
              picker? (assoc (picker-join-key qualified-key) picker-state))

            :otherwise
            (if default-value
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

(defn mark-filled-fields-complete* [state-map {:keys [entity-ident initialized-keys]}]
  (let [mark-complete* (fn [entity {::fs/keys [fields complete?] :as form-config}]
                         (let [to-mark (set/union (set complete?) (set/intersection (set fields) (set initialized-keys)))
                               to-mark (into #{}
                                         (filter (fn [k] (not (nil? (get entity k)))))
                                         to-mark)]
                           [entity (assoc form-config ::fs/complete? to-mark)]))]
    (fs/update-forms state-map mark-complete* entity-ident)))

(defn- start-create [env _]
  (let [FormClass        (uism/actor-class env :actor/form)
        form-ident       (uism/actor->ident env :actor/form)
        id               (second form-ident)
        initial-state    (default-state FormClass id)
        entity-to-merge  (fs/add-form-config FormClass initial-state)
        initialized-keys (set (sp/select (sp/walker keyword?) initial-state))]
    (-> env
      (uism/apply-action merge/merge-component FormClass entity-to-merge)
      (uism/apply-action mark-filled-fields-complete* {:entity-ident     form-ident
                                                       :initialized-keys initialized-keys})
      (route-target-ready form-ident)
      (uism/activate :state/editing))))

(defn confirm-exit? [env]
  (boolean (some-> env (uism/actor-class :actor/form) comp/component-options ::confirm-exit?)))

(defn exit-form
  "Discard all changes and change route."
  [env]
  (let [Form         (uism/actor-class env :actor/form)
        cancel-route (some-> Form comp/component-options ::cancel-route)]
    (when-not cancel-route
      (log/error "Don't know where to route on cancel. Add ::form/cancel-route to your form."))
    ;; TODO: Should allow the store of an override to this declared route.
    (-> env
      (uism/activate :state/abandoned)
      (uism/set-timeout :cleanup :event/exit {::new-route cancel-route} 1))))

(defn ask-before-leaving [env]
  (if (confirm-exit? env)
    (uism/activate env :state/asking-to-discard-changes)
    (exit-form env)))

(>defn calc-diff
  [env]
  [::uism/env => (s/keys :req [::delta])]
  (let [{::uism/keys [state-map]} env
        form-ident (uism/actor->ident env :actor/form)
        Form       (uism/actor-class env :actor/form)
        props      (fns/ui->props state-map Form form-ident)
        delta      (fs/dirty-fields props true)]
    {::delta delta}))

(def global-events
  {:event/exit
   {::uism/handler (fn [{::uism/keys [event-data fulcro-app] :as env}]
                     (let [route (::new-route event-data)]
                       (when route
                         (dr/change-route fulcro-app route))
                       (uism/exit env)))}

   :event/route-denied
   {::uism/handler (fn [env]
                     #?(:cljs (js/alert "Editing in progress"))
                     env)}})

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
                        (cond-> env
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
               (uism/apply-action fs/add-form-config* FormClass form-ident)
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
       {:event/ok     {::uism/handler exit-form}
        :event/cancel {::uism/handler (fn [env] (uism/activate env :state/editing))}})}

    :state/saving
    {::uism/events
     (merge
       global-events
       {:event/save-failed
        {::uism/handler (fn [env]
                          ;; TODO: Handle failures
                          (uism/activate env :state/editing))}
        :event/saved
        {::uism/handler (fn [env]
                          (let [form-ident (uism/actor->ident env :actor/form)]
                            (-> env
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
           (let [{:keys       [value form-ident]
                  ::attr/keys [qualified-key]} event-data
                 ;form-ident     (uism/actor->ident env :actor/form)
                 path           (when (and form-ident qualified-key)
                                  (conj form-ident qualified-key))
                 ;; TODO: Decide when to properly set the field to marked
                 mark-complete? true]
             (when-not path
               (log/error "Unable to record attribute change. Path cannot be calculated."))
             (cond-> env
               mark-complete? (uism/apply-action fs/mark-complete* form-ident qualified-key)
               ;; FIXME: Data coercion needs to happen at UI and db layer, but must
               ;; be extensible. You should be able to select a variant of a form
               ;; control for a given db-supported type. This allows the types
               ;; to be fully extensible since the db adapter can isolate that
               ;; coercion, and the UI control variant can do coercion at the UI
               ;; layer.
               ;; FIXME: One catch with coercion: sometimes the value has transient
               ;; values during input that will not properly coerce. This means UI
               ;; controls will need to buffer the user-interaction value and only
               ;; do the commit/coercion at the end.
               path (uism/apply-action assoc-in path value))))}

        :event/blur
        {::uism/handler (fn [env] env)}

        :event/add-row
        {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                          (let [{::keys [parent-relation parent child-class]} event-data
                                id-key      (some-> child-class comp/component-options ::id ::attr/qualified-key)
                                target-path (conj (comp/get-ident parent) parent-relation)
                                ;; TODO: initialize all fields...use get-initial-state perhaps?
                                new-child   (default-state child-class (tempid/tempid))
                                child-ident (comp/get-ident child-class new-child)]
                            (uism/apply-action env
                              (fn [s]
                                (-> s
                                  (merge/merge-component child-class new-child
                                    :append target-path)
                                  ;; TODO: mark default fields complete...
                                  (fs/add-form-config* child-class child-ident))))))}

        :event/delete-row
        {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                          (let [{::keys [form-instance]} event-data
                                child-ident (comp/get-ident form-instance)]
                            (uism/apply-action env fns/remove-entity child-ident)))}

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
                                       ;; TODO: Make return optional?
                                       ;::m/returning      form-class
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
        {::uism/handler exit-form}})}

    :state/abandoned
    {::uism/events global-events}}})

(defn desired-attributes
  "Returns the list of recursive attributes desired by the query of `c`"
  [c]
  (let [{::keys [subforms attributes]} (comp/component-options c)
        all-attributes (into attributes (mapcat
                                          #(-> % comp/component-options ::attributes)
                                          (sp/select [sp/MAP-VALS (sp/keypath ::ui)] subforms)))]
    all-attributes))

(defn save! [{this ::master-form}]
  (uism/trigger! this (comp/get-ident this) :event/save {}))

(defn undo-all! [{this ::master-form}]
  (uism/trigger! this (comp/get-ident this) :event/reset {}))

(defn cancel! [{this ::master-form}]
  (uism/trigger! this (comp/get-ident this) :event/cancel {}))

(defn add-child! [{::keys [master-form] :as env}]
  (let [asm-id (comp/get-ident master-form)]
    (uism/trigger! master-form asm-id :event/add-row env)))

(defn delete-child!
  "Delete a child of a master form. Only use this on nested forms that are actively being edited. See
   also `delete!`."
  [{::keys [master-form] :as env}]
  (let [asm-id (comp/get-ident master-form)]
    (uism/trigger! master-form asm-id :event/delete-row env)))

(>defn read-only?
  [form-instance {::attr/keys [qualified-key identity? read-only?] :as attr}]
  [comp/component? ::attr/attribute => boolean?]
  (let [read-only-fields (comp/component-options form-instance ::read-only-fields)]
    (boolean
      (or
        identity?
        read-only?
        (and (set? read-only-fields) (contains? read-only-fields qualified-key))))))

(defn edit!
  "Route to the given form for editing the entity with the given ID."
  ([this form-class entity-id]
   (dr/change-route this (dr/path-to form-class {:action edit-action
                                                 :id     entity-id})
     {:deferred-timeout 16}))
  ([this form-class entity-id {:keys [router]}]
   (if router
     (dr/change-route-relative this router (dr/path-to form-class {:action edit-action
                                                                   :id     entity-id}
                                             {:deferred-timeout 16}))
     (edit! this form-class entity-id))))

(defn create!
  "Create a new instance of the given form-class using the provided `entity-id` and then route
   to that form for editing.

   - `app-ish`: A component instance or the app.
   - `form-class`: The form to create.
   - options map:
   -- `:router` The router that contains the form, if not root."
  ([app-ish form-class]
   (dr/change-route app-ish (dr/path-to form-class {:action create-action
                                                    :id     (str (new-uuid))})))
  ([app-ish form-class {:keys [router] :as options}]
   (if router
     (dr/change-route-relative app-ish router
       (dr/path-to form-class {:action create-action
                               :id     (str (new-uuid))}))
     (create! app-ish form-class))))

#?(:clj
   (pc/defmutation delete-entity [env params]
     {}
     (if-let [delete-middleware (::delete-middleware env)]
       (let [delete-env {::pathom-env env ::params params}]
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

(defn input-blur! [{::keys [form-instance master-form]} k value]
  (let [form-ident (comp/get-ident form-instance)
        asm-id     (comp/get-ident master-form)]
    (uism/trigger! master-form asm-id :event/blur
      {::attr/qualified-key k
       :form-ident          form-ident
       :value               value})))

(defn input-changed! [{::keys [form-instance master-form]} k value]
  (let [form-ident (comp/get-ident form-instance)
        asm-id     (comp/get-ident master-form)]
    (uism/trigger! form-instance asm-id :event/attribute-changed
      {::attr/qualified-key k
       :form-ident          form-ident
       :value               value})))

(defn install-ui-controls!
  "Install the given control set as the RAD UI controls used for rendering forms. This should be called before mounting
  your app. The `controls` is just a map from data type to a sub-map that contains a :default key, with optional
  alternate renderings for that data type that can be selected with `::form/field-style {attr-key style-key}`."
  [app controls]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc :com.fulcrologic.rad/controls controls)))

(defmutation transform-options
  "INTERNAL MUTATION. Do not use."
  [{:keys  [ref]
    ::keys [pick-one]}]
  (action [{:keys [state] :as env}]
    (let [result    (get-in @state (conj ref :ui/query-result))
          transform (get pick-one :options/transform)
          options   (if transform
                      (mapv transform result)
                      result)]
      (swap! state assoc-in (conj ref :ui/options) options))))

(defn- load-options!
  "Internal implementation detail of entity picker. Loads the options."
  [this]
  (let [{::keys [pick-one] :as picker-options} (comp/get-computed this)
        {:options/keys [query-key subquery]} pick-one
        fake-component (comp/configure-component! (fn []) ::fake {:query (fn [] subquery)
                                                                  ;; not sure these should be normalized...but could be
                                                                  ;;:ident (fn [this props] [id-key (get props id-key)])
                                                                  })
        target-path    (conj (comp/get-ident this) :ui/query-result)]
    (when (or (not query-key) (not subquery))
      (log/error "Options for picker are missing query-key or subquery"))
    (when query-key
      (df/load! this query-key fake-component {:target               target-path
                                               :post-mutation        `transform-options
                                               :post-mutation-params (merge picker-options
                                                                       {:ref (comp/get-ident this)})}))))

(defsc ToOneEntityPicker [this _ {::keys      [env]
                                      ::attr/keys [attribute]}]
  {:query             [:picker/id
                       :ui/options
                       :ui/query-result]
   :initial-state     (fn [{:keys [id]}] {:picker/id  id
                                          :ui/options []})
   :componentDidMount (fn [this] (load-options! this))
   :ident             :picker/id}
  (let [{::app/keys [runtime-atom]} (comp/any->app this)
        control-map (some-> runtime-atom deref :com.fulcrologic.rad/controls ::type->style->control)
        control     (get-in control-map [:entity-picker :default])]
    (when control
      (control (assoc env ::picker-instance this) attribute))))

(defn field-label
  "Returns a human readable label for a given attribute (which can be declared on the attribute, and overridden on the
  specific form). Defaults to the capitalized name of the attribute qualified key."
  [form-env attribute]
  (let [{::keys [form-instance]} form-env
        k           (::attr/qualified-key attribute)
        options     (comp/component-options form-instance)
        field-label (or
                      (get-in options [::field-labels k])
                      (::field-label attribute)
                      (some-> k name str/capitalize))]
    field-label))

(defn invalid?
  "Returns true if the validator on the form in `env` indicates that some form field(s) are invalid."
  ([form-rendering-env]
   (let [{::keys [form-instance]} form-rendering-env
         props (comp/props form-instance)]
     (invalid? form-instance props)))
  ([form-class-or-instance props]
   (let [{::keys [validator]} (comp/component-options form-class-or-instance)]
     (and validator (= :invalid (validator props))))))

(defn valid?
  "Returns true if the validator on the form in `env` indicates that all of the form fields are valid."
  ([form-rendering-env]
   (let [{::keys [form-instance]} form-rendering-env
         props (comp/props form-instance)]
     (valid? form-instance props)))
  ([form-class-or-instance props]
   (let [validator (comp/component-options form-class-or-instance ::validator)]
     (or
       (not validator)
       (and validator (= :valid (validator props)))))))

(>defn field-style-config
  "Get the value of an overridable field-style-config option. If both the form and attribute set these
  then the result will be a deep merge of the two (with form winning)."
  [{::keys [form-instance]} attribute config-key]
  [::form-env ::attr/attribute keyword? => any?]
  (let [{::attr/keys [qualified-key]
         ::keys      [field-style-config]} attribute
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

#?(:clj (def resolvers [save-form delete-entity]))
