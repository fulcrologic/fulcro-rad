(ns com.fulcrologic.rad.form
  #?(:cljs (:require-macros [com.fulcrologic.rad.form]))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.errors :refer [required!]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.controller :as controller]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]
    #?(:clj [cljs.analyzer :as ana])
    #?(:cljs [goog.object])
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [expound.alpha :as expound]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def data-type->field-type {:string :text})

(defmulti render-field (fn [this attr props]
                         (or
                           (::field-type attr)
                           (some-> attr ::attr/type data-type->field-type)
                           (some-> attr ::attr/type))))

(defmethod render-field :default
  [_ attr _]
  (log/debug "NOTE: Attempt to render a field that did not have anything to dispatch to."
    "Did you remember to require the namespace that implements the field type:"
    attr))

(defmulti render-layout (fn [this props] (some-> this comp/component-options ::layout)))

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
                             [id-key :ui/new? :ui/confirmation-message [::uism/asm-id '_] fs/form-config-join]
                             (map ::attr/qualified-key)
                             scalars)
        subforms           (::subforms form-options)
        full-query         (into query-with-scalars
                             (map (fn [{::attr/keys [qualified-key target]}]
                                    (required! (str "Form attribute " qualified-key
                                                 " is a reference type. The ::form/subforms map")
                                      subforms qualified-key #(contains? % ::ui))
                                    (let [subform (get-in subforms [qualified-key ::ui])]
                                      {qualified-key (comp/get-query subform)})))
                             refs)]
    full-query))

(defn convert-options
  "Runtime conversion of form options to what comp/configure-component! needs."
  [location options]
  (required! location options ::attributes vector?)
  (required! location options ::id attr/attribute?)
  (let [{::keys [id attributes route-prefix]} options
        id-key      (::attr/qualified-key id)
        form-field? (fn [{::attr/keys [identity?]}] (not identity?))]
    (merge options
      {:query         (fn [this] (form-options->form-query (comp/component-options this)))
       :ident         (fn [_ props] [id-key (get props id-key)])
       :form-fields   (->> attributes
                        (filter form-field?)
                        (map ::attr/qualified-key)
                        (into #{}))
       :route-segment [route-prefix :action :id]
       ::rad/type     ::rad/form
       ::rad/io?      true
       :will-enter    (fn [_ params]
                        (let [id (get params :id)]
                          (dr/route-immediate [id-key (new-uuid id)])))})))

#?(:clj
   (defn form-body [argslist body]
     (if (empty? body)
       `[(render-layout ~(first argslist) ~(second argslist))]
       body)))

#?(:clj
   (defn defsc-form*
     [env args]
     (let [{:keys [sym doc arglist options body]} (s/conform ::defsc-form-args args)
           nspc        (if (comp/cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
           fqkw        (keyword (str nspc) (name sym))
           body        (form-body arglist body)
           [thissym propsym computedsym extra-args] arglist
           location    (str nspc "." sym)
           render-form (#'comp/build-render sym thissym propsym computedsym extra-args body)]
       (if (comp/cljs? env)
         `(do
            (declare ~sym)
            (let [options# (assoc (convert-options ~location ~options) :render ~render-form)]
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
            (let [options# (assoc (convert-options ~location ~options) :render ~render-form)]
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
   (pc/defmutation save-form [env params]
     {::pc/params #{::diff ::delta}}
     ;; TODO: Write across all plugins
     )
   :cljs
   (m/defmutation save-form [params]
     (action [env] :noop)))
;; TODO: Support for a generalized focus mechanism to show the first field that has a problem

;; TODO: Allow form to override validation on a field, with fallback to what is declared on the attribute

(defn config [env] (uism/retrieve env :config))

(defn attr-value
  "When interpreting an event from a form field, this function will extract the pair of:
  [attribute value] from the `env`."
  [env]
  [(-> env ::uism/event-data ::attr/qualified-key)
   (-> env ::uism/event-data :value)])

(defn set-attribute*
  "Mutation helper: Set the given attribute's value in app state."
  [state-map form attribute value])

(defn- start-edit! [app TargetClass {machine-id ::id
                                     ::rad/keys [id target-route] :as options}]
  (when-not machine-id
    (log/error "Missing form machine id route on start-edit!"))
  (when-not target-route
    (log/error "Missing target route on start-edit!"))
  (when-not id
    (log/error "Missing ID on start-edit!"))
  (log/debug "START EDIT" (comp/component-name TargetClass))
  (let [id-key (some-> TargetClass (comp/ident {}) first)
        ;; TODO: Coercion from string IDs to type of ID field
        id     (new-uuid id)]
    (df/load! app [id-key id] TargetClass
      {:post-action (fn [{:keys [state]}]
                      (log/debug "Marking the form complete")
                      (fns/swap!-> state
                        (assoc-in [id-key id :ui/new?] false)
                        (fs/add-form-config* TargetClass [id-key id])
                        (fs/mark-complete* [id-key id]))
                      (controller/io-complete! app options))})))

;; TODO: ID generation pluggable? Use tempids?  NOTE: The controller has to generate the ID because the incoming
;; route is already determined
(defn- start-create! [app TargetClass {machine-id ::id
                                       ::rad/keys [target-route tempid]}]
  (when-not machine-id
    (log/error "Controller failed to pass machine id"))
  (when-not tempid
    (log/error "Creating an entity, but initial ID missing"))
  (log/debug "START CREATE" (comp/component-name TargetClass))
  (let [id-key         (some-> TargetClass (comp/ident {}) first)
        ident          [id-key tempid]
        fields         (map attr/key->attribute (comp/component-options TargetClass ::attr/attributes))
        default-values (comp/component-options TargetClass ::default-values)
        ;; TODO: Make sure there is one and only one unique identity key on the form
        initial-value  (into {:ui/new? true}
                         (keep (fn [{::keys      [default-value]
                                     ::attr/keys [qualified-key unique?]}]
                                 ;; NOTE: default value can come from attribute or be set/overridden on form itself
                                 (let [default-value (or (get default-values qualified-key) default-value)]
                                   (cond
                                     (= unique? true) [qualified-key tempid]
                                     default-value [qualified-key default-value]))))
                         fields)
        filled-fields  (keys initial-value)
        tx             (into []
                         (map (fn [k]
                                (fs/mark-complete! {:entity-ident ident
                                                    :field        k})))
                         filled-fields)]
    ;; NOTE: pre-merge of form does add of form config...this is probably not right. Should probably trigger a self
    ;; event for that
    (merge/merge-component! app TargetClass initial-value)
    (when (seq tx)
      (log/debug "Marking fields with default values complete")
      (comp/transact! app tx))
    (controller/io-complete! app {::controller/id    machine-id
                                  ::rad/target-route target-route})))

(defn confirm-exit? [env]
  (boolean (some-> env (uism/actor-class :actor/form) comp/component-options ::confirm-exit?)))

(defn exit-form [{::uism/keys [fulcro-app] :as env}]
  (let [Form         (uism/actor-class env :actor/form)
        id           (uism/retrieve env ::controller/id)
        cancel-route (some-> Form comp/component-options ::cancel-route)]
    (when-not cancel-route
      (log/error "Don't know where to route on cancel. Add ::form/cancel-route to your form."))
    ;; TODO: probably return to original route instead
    (controller/route-to! fulcro-app id (or cancel-route []))
    (uism/exit env)))

(defn ask-before-leaving [{::uism/keys [fulcro-app] :as env}]
  (if (confirm-exit? env)
    (uism/activate env :state/asking-to-discard-changes)
    (exit-form env)))

(>defn calc-diff
  [env]
  [::uism/env => (s/keys :req [::delta])]
  (let [{::uism/keys [state-map event-data]} env
        form-ident (uism/actor->ident env :actor/form)
        Form       (uism/actor-class env :actor/form)
        props      (fns/ui->props state-map Form form-ident)
        new?       (uism/alias-value env :new?)
        delta      (fs/dirty-fields props true {:new-entity? new?})
        diff       (fs/dirty-fields props false {:new-entity? new?})]
    {::delta delta}))

(def global-events
  {:event/will-leave     {::uism/handler (fn [env]
                                           ;; TODO: Handle the controller asking if it is OK to abort this edit
                                           env)}
   ;; TODO: hook this up in controller
   :event/form-abandoned {::uism/handler (fn [env]
                                           (uism/exit env))}})

(defstatemachine form-machine
  {::uism/actors
   #{:actor/form}

   ::uism/aliases
   {:new?                 [:actor/form :ui/new?]
    :confirmation-message [:actor/form :ui/confirmation-message]}

   ::uism/states
   {:initial
    {::uism/handler (fn [env]
                      (let [{::uism/keys [fulcro-app event-data]} env
                            {::controller/keys [id]
                             ::keys            [action]} event-data
                            Form (uism/actor-class env :actor/form)]
                        (when-not id
                          (log/error "Controller ID not sent to form SM."))
                        (when-not (#{:create :edit} action)
                          (log/error "Unexpected action" action))
                        (if (= :create action)
                          (start-create! fulcro-app Form event-data)
                          (start-edit! fulcro-app Form event-data))
                        (-> env
                          (uism/store ::action action)
                          (uism/store ::controller/id id)
                          (uism/activate :state/editing))))}

    :state/asking-to-discard-changes
    {::uism/events
     {:event/ok     {::uism/handler exit-form}
      :event/cancel {::uism/handler (fn [env] (uism/activate env :state/editing))}}}

    :state/saving
    (merge global-events
      {::uism/events
       {:event/save-failed {::uism/handler (fn [env]
                                             ;; TODO: Handle failures
                                             (uism/activate env :state/editing))}
        :event/saved       {::uism/handler (fn [env]
                                             (let [form-ident (uism/actor->ident env :actor/form)]
                                               (-> env
                                                 (uism/apply-action fs/entity->pristine* (log/spy :info form-ident))
                                                 (uism/activate :state/editing))))}}})

    :state/editing
    (merge global-events
      {::uism/events
       {:event/attribute-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   ;; NOTE: value at this layer is ALWAYS typed to the attribute.
                                                   ;; The rendering layer is responsible for converting the value to/from
                                                   ;; the representation needed by the UI component (e.g. string)
                                                   (let [{:keys       [value]
                                                          ::attr/keys [qualified-key]} event-data
                                                         form-ident     (uism/actor->ident env :actor/form)
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
        :event/blur              {::uism/handler (fn [env] env)}
        :event/save              {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                   (let [form-class   (uism/actor-class env :actor/form)
                                                         data-to-save (calc-diff env)
                                                         params       (merge event-data data-to-save)]
                                                     (-> env
                                                       (uism/trigger-remote-mutation :actor/form `save-form
                                                         (merge params
                                                           {::uism/error-event :event/save-failed
                                                            ;; TODO: Make return optional?
                                                            ::m/returning      form-class
                                                            ::uism/ok-event    :event/saved}))
                                                       (uism/activate :state/saving))))}
        :event/reset             {::uism/handler (fn [env]
                                                   (let [form-ident (uism/actor->ident env :actor/form)]
                                                     (uism/apply-action env fs/pristine->entity* form-ident)))}
        :event/cancel            {::uism/handler (fn [env] (exit-form env))}}})}})

(defmethod controller/-start-io! ::rad/form
  [{::uism/keys [fulcro-app] :as env} TargetClass {::controller/keys [id]
                                                   ::rad/keys        [target-route] :as options}]
  (log/info "Starting I/O processing for RAD Form" (comp/component-name TargetClass))
  (let [[_ action id] target-route
        target-id       (new-uuid id)
        form-machine-id [(first (comp/ident TargetClass {})) target-id]
        event-data      (assoc options
                          ::id form-machine-id
                          ::rad/id target-id
                          ::rad/tempid target-id
                          ::action (some-> action str keyword))]
    (uism/begin! fulcro-app form-machine form-machine-id
      {:actor/form (uism/with-actor-class form-machine-id TargetClass)}
      event-data)
    (if (= action "create")
      (start-create! fulcro-app TargetClass event-data)
      (start-edit! fulcro-app TargetClass event-data))
    (uism/activate env :state/routing)))

(defn save! [this]
  (uism/trigger! this (comp/get-ident this) :event/save {}))
(defn undo-all! [this]
  (uism/trigger! this (comp/get-ident this) :event/reset {}))
(defn cancel! [this]
  (uism/trigger! this (comp/get-ident this) :event/cancel {}))

(>defn read-only?
  [this attr]
  [comp/component? ::attr/attribute => boolean?]
  (boolean (or (::attr/identity? attr) (::pc/resolve attr))))

(defn edit!
  "Route to the given form for editing the entity with the given ID."
  [this form-class entity-id]
  (let [[root & _] (-> form-class comp/component-options :route-segment)]
    (controller/route-to! this :main-controller [root "edit" (str entity-id)])))

(defn input-blur! [this k value]
  (let [asm-id (comp/get-ident this)]
    (uism/trigger! this asm-id :event/blur
      {::attr/qualified-key k
       :form-ident          asm-id
       :value               value})))

(defn input-changed! [this k value]
  (let [asm-id (comp/get-ident this)]
    (uism/trigger! this asm-id :event/attribute-changed
      {::attr/qualified-key k
       :form-ident          asm-id
       :value               value})))
