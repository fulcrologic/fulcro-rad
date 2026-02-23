(ns com.fulcrologic.rad.form.impl
  "Pure implementation functions extracted from com.fulcrologic.rad.form.
   This namespace has NO dependency on UISM or statecharts — only Fulcro core,
   RAD attributes/options, and standard Clojure.

   These functions are internal API; prefer the public vars in com.fulcrologic.rad.form."
  #?(:cljs (:require-macros [com.fulcrologic.rad.form.impl]))
  (:refer-clojure :exclude [parse-long])
  (:require
    #?@(:clj [[cljs.analyzer :as ana]])
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.lambda :refer [->arity-tolerant]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.guardrails.core :refer [=> >def >defn]]
    [com.fulcrologic.guardrails.malli.core :as grm]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.errors :refer [warn-once!]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.form-render :as fr]
    [com.fulcrologic.rad.form-render-options :as fro]
    [com.fulcrologic.rad.options-util :as opts :refer [?!]]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [com.fulcrologic.rad.type-support.integer :as int]
    [com.fulcrologic.rad.form :as-alias form]
    [edn-query-language.core :as eql]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

;; ─────────────────────────────────────────────────────────────────────────────
;; Specs (duplicated from form.cljc so they live in the impl namespace too)
;; ─────────────────────────────────────────────────────────────────────────────

(>def ::form/id any?)
(>def ::form/master-pk qualified-keyword?)
(>def ::form/delta (s/map-of eql/ident? map?))
(>def ::form/params (s/keys :req [::form/id ::form/master-pk ::form/delta]))
(>def ::form/save-middleware fn?)
(>def ::form/form-env map?)

(grm/>def ::form/id any?)
(grm/>def ::form/master-pk :qualified-keyword)
(grm/>def ::form/delta [:map-of [:fn eql/ident?] :map])
(grm/>def ::form/params [:map ::form/id ::form/master-pk ::form/delta])
(grm/>def ::form/save-middleware fn?)
(grm/>def ::form/form-env map?)

;; ─────────────────────────────────────────────────────────────────────────────
;; Constants
;; ─────────────────────────────────────────────────────────────────────────────

(def ^:dynamic *default-save-form-mutation*
  "The default mutation used by form save operations. Dynamically re-bindable."
  'com.fulcrologic.rad.form/save-form)

(def view-action "view")
(def create-action "create")
(def edit-action "edit")

(def standard-action-buttons
  "The standard ::form/action-buttons button layout."
  [:com.fulcrologic.rad.form/done
   :com.fulcrologic.rad.form/undo
   :com.fulcrologic.rad.form/save])

(declare rendering-env render-field)

;; ─────────────────────────────────────────────────────────────────────────────
;; Component helpers
;; ─────────────────────────────────────────────────────────────────────────────

(>defn picker-join-key
  "Returns a :ui/picker keyword customized to the qualified keyword"
  [qualified-key]
  [qualified-keyword? => qualified-keyword?]
  (keyword "ui" (str (namespace qualified-key) "-"
                  (name qualified-key)
                  "-picker")))

(defn master-form
  "Return the master form for the given component instance."
  [component]
  (or (some-> component comp/get-computed :com.fulcrologic.rad.form/master-form) component))

(defn master-form?
  "Returns true if the given react element `form-instance` is the master form in the supplied rendering env. You can
   also supply `this` if you have not already created a form rendering env, but that will be less efficient if you
   need the rendering env in other places."
  ([this]
   (let [env (rendering-env this)]
     (master-form? env this)))
  ([rendering-env form-instance]
   (let [mf (:com.fulcrologic.rad.form/master-form rendering-env)]
     (= form-instance mf))))

(defn parent-relation
  "Returns the keyword that was used in the join of the parent form when querying for the data of the current
   `form-instance`. Returns nil if there is no parent relation."
  [this]
  (some-> this comp/get-computed :com.fulcrologic.rad.form/parent-relation))

(defn form-key->attribute
  "Get the RAD attribute definition for the given attribute key, given a class-or-instance that has that attribute
   as a field. Returns a RAD attribute, or nil if that attribute isn't a form field on the form."
  [class-or-instance attribute-key]
  (some-> class-or-instance comp/component-options :com.fulcrologic.rad.form/key->attribute attribute-key))

(def subform-options
  "[form-options]
   [form-options ref-attr-or-keyword]

   Find the subform options for the given form instance's ref-attr-or-keyword."
  fo/subform-options)

(defn subform-ui [form-options ref-key-or-attribute]
  (some-> (subform-options form-options ref-key-or-attribute) fo/ui))

(def get-field-options
  "[form-options]
   [form-options attr-or-key]

   Get the fo/field-options for a form (arity 1) or a particular field (arity 2). Runs lambdas if necessary."
  fo/get-field-options)

;; ─────────────────────────────────────────────────────────────────────────────
;; Rendering helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn render-fn
  "Find the correct UI renderer for the given form layout `element`."
  [{:com.fulcrologic.rad.form/keys [form-instance] :as form-env} element]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} (comp/any->app form-instance)
        style-path             [:com.fulcrologic.rad.form/layout-styles element]
        copts                  (comp/component-options form-instance)
        id-attr                (fo/id copts)
        layout-style           (or
                                 (get-in copts style-path)
                                 (?! (fro/style copts) id-attr form-env)
                                 :default)
        element->style->layout (some-> runtime-atom deref ::rad/controls ::element->style->layout)
        render-fn              (some-> element->style->layout (get element) (get layout-style))
        default-render-fn      (some-> element->style->layout (get element) :default)]
    (cond
      (not runtime-atom) (log/error "Form instance was not in the rendering environment. This means the form did not mount properly")
      (not render-fn) (log/error "No renderer was installed for layout style" layout-style "for UI element" element))
    (or render-fn default-render-fn)))

(defn form-container-renderer
  "The top-level container for the entire on-screen form"
  [form-env] (render-fn form-env :form-container))

(defn form-layout-renderer
  "The container for the form fields."
  [form-env] (render-fn form-env :form-body-container))

(defn ref-container-renderer
  "Returns the renderer that wraps and lays out elements of refs."
  [{:com.fulcrologic.rad.form/keys [form-instance] :as _form-env}
   {:com.fulcrologic.rad.form/keys [field-style]
    ::attr/keys                    [qualified-key] :as attr}]
  (let [{:com.fulcrologic.rad.form/keys [field-styles] :as form-options} (comp/component-options form-instance)
        field-style (or (get field-styles qualified-key) field-style)]
    (if field-style
      (fn [env attr _] (render-field env attr))
      (let [{:com.fulcrologic.rad.form/keys [ui layout-styles]} (subform-options form-options attr)
            {target-styles :com.fulcrologic.rad.form/layout-styles} (comp/component-options ui)
            {:com.fulcrologic.fulcro.application/keys [runtime-atom]} (comp/any->app form-instance)
            element      :ref-container
            layout-style (or
                           (get layout-styles element)
                           (get target-styles element)
                           :default)
            render-fn    (some-> runtime-atom deref ::rad/controls ::element->style->layout
                           (get-in [element layout-style]))]
        render-fn))))

(defn attr->renderer
  "Given a form rendering environment and an attribute: returns the renderer that can render the given attribute."
  [{:com.fulcrologic.rad.form/keys [form-instance master-form]}
   {::attr/keys                    [type qualified-key style]
    :com.fulcrologic.rad.form/keys [field-style] :as attr}]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} (comp/any->app form-instance)
        field-style (?! (or
                          (some-> master-form comp/component-options :com.fulcrologic.rad.form/field-styles qualified-key)
                          (some-> form-instance comp/component-options :com.fulcrologic.rad.form/field-styles qualified-key)
                          field-style
                          style
                          :default)
                      form-instance)
        control-map (some-> runtime-atom deref ::rad/controls ::type->style->control)
        control     (or
                      (get-in control-map [type field-style])
                      (do
                        (warn-once! "Renderer not found: " type field-style)
                        (get-in control-map [type :default])))]
    (if control
      control
      (log/error "Unable to find control (no default) for attribute " attr))))

(defn render-field
  "Given a form rendering environment and an attribute: renders that attribute as a form field."
  [env attr]
  (fr/render-field env attr))

(defn render-input
  "Renders an attribute as a form input (hints to the rendering layer to omit the label)."
  [env attr]
  (render-field env (assoc attr fo/omit-label? true)))

(defn default-render-field [env attr]
  (let [render (attr->renderer env attr)]
    (if render
      ((->arity-tolerant render) env attr)
      (do
        (log/error "No renderer installed to support attribute" attr)
        nil))))

(defn rendering-env
  "Create a form rendering environment. `form-instance` is the react element instance of the form (typically a master
   form), but this function can be called using an active sub-form. `props` should be the props of the `form-instance`,
   and are allowed to be passed as an optimization when you've already got them.

   NOTE: This function will automatically extract the master form from the computed props of form-instance in cases
   where you are in the context of a sub-form."
  ([form-instance]
   (let [props  (comp/props form-instance)
         cprops (comp/get-computed props)]
     (merge cprops
       {:com.fulcrologic.rad.form/master-form    (master-form form-instance)
        :com.fulcrologic.rad.form/form-instance  form-instance
        :com.fulcrologic.rad.form/props          props
        :com.fulcrologic.rad.form/computed-props cprops})))
  ([form-instance props]
   (let [cprops (comp/get-computed props)]
     (merge cprops
       {:com.fulcrologic.rad.form/master-form    (master-form form-instance)
        :com.fulcrologic.rad.form/form-instance  form-instance
        :com.fulcrologic.rad.form/props          props
        :com.fulcrologic.rad.form/computed-props cprops}))))

(defn render-form-fields
  "Render JUST the form fields (and subforms). Skips the header/controls on the top-level form."
  [form-instance props]
  (when-not (comp/component? form-instance)
    (throw (ex-info "Invalid form instance." {:form-instance form-instance})))
  (let [env    (rendering-env form-instance props)
        render (form-layout-renderer env)]
    (if render
      ((->arity-tolerant render) env)
      nil)))

(defn default-render-layout [form-instance props]
  (when-not (comp/component? form-instance)
    (throw (ex-info "Invalid form instance propagated to render layout." {:form-instance form-instance})))
  (let [env    (rendering-env form-instance props)
        render (form-container-renderer env)]
    (if render
      ((->arity-tolerant render) env)
      nil)))

(defn render-layout
  "Render the complete layout of a form."
  [form-instance props]
  (when-not (comp/component? form-instance)
    (throw (ex-info "Invalid form instance propagated to render layout." {:form-instance form-instance})))
  (let [env (rendering-env form-instance props)]
    (fr/render-form env (comp/component-options form-instance fo/id))))

(defn subform-rendering-env [parent-form-instance relation-key]
  (let [renv (rendering-env parent-form-instance)]
    (assoc renv
      :com.fulcrologic.rad.form/parent parent-form-instance
      :com.fulcrologic.rad.form/parent-relation relation-key)))

(defn render-subform
  "Render a RAD subform from a parent form."
  ([parent-form-instance relation-key ChildForm child-props]
   (render-subform parent-form-instance relation-key ChildForm child-props {}))
  ([parent-form-instance relation-key ChildForm child-props extra-computed-props]
   (let [id-key     (-> ChildForm comp/component-options fo/id ao/qualified-key)
         ui-factory (comp/computed-factory ChildForm {:keyfn id-key})
         renv       (subform-rendering-env parent-form-instance relation-key)]
     (ui-factory child-props (merge extra-computed-props renv)))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Form creation helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn find-fields
  "Recursively walks the definition of a RAD form (form and all subforms), and returns the attribute qualified keys
   that match `(pred attribute)`"
  [form-class pred]
  (let [attributes        (or
                            (comp/component-options form-class ::attr/attributes)
                            [])
        local-optional    (into #{} (comp (filter pred) (map ::attr/qualified-key)) attributes)
        children          (some->> form-class comp/get-query eql/query->ast :children (keep :component))
        children-optional (map #(find-fields % pred) children)]
    (apply set/union local-optional children-optional)))

(defn optional-fields
  "Returns all of the form fields from a form (recursively) that are not marked ao/required?"
  [form-class]
  (find-fields form-class #(not (true? (get % ::attr/required?)))))

(defn- sc [registry-key options]
  (let [cls (fn [])]
    (comp/configure-component! cls registry-key options)))

(defn form-options->form-query
  "Converts form options to the necessary EQL query for a form class. The optional `extra-query-elements`
   is a sequence of additional EQL elements to include (e.g. `[[::uism/asm-id '_]]` for UISM engine,
   or `[]` for the statecharts engine). Defaults to no extra elements."
  ([form-options]
   (form-options->form-query form-options []))
  ([{id-attr                        :com.fulcrologic.rad.form/id
     :com.fulcrologic.rad.form/keys [attributes] :as form-options}
    extra-query-elements]
   (let [id-key             (::attr/qualified-key id-attr)
         {refs true scalars false} (group-by #(= :ref (::attr/type %)) attributes)
         query-with-scalars (into
                              [id-key
                               :ui/confirmation-message
                               :ui/route-denied?
                               :com.fulcrologic.rad.form/errors
                               [::picker-options/options-cache '_]
                               [:com.fulcrologic.fulcro.application/active-remotes '_]
                               fs/form-config-join]
                              (concat
                                extra-query-elements
                                (map ::attr/qualified-key
                                  (remove #{id-attr} scalars))))
         full-query         (into query-with-scalars
                              (mapcat (fn [{::attr/keys [qualified-key] :as attr}]
                                        (if-let [subform (subform-ui form-options attr)]
                                          [{qualified-key (comp/get-query subform)}]
                                          (let [k->attr        (into {} (map (fn [{::attr/keys [qualified-key] :as attr}] [qualified-key attr])) attributes)
                                                target-id-key  (::attr/target (k->attr qualified-key))
                                                fake-component (sc qualified-key {:query (fn [_] [target-id-key])
                                                                                  :ident (fn [_ props] [target-id-key (get props target-id-key)])})]
                                            (when-not target-id-key
                                              (log/warn "Reference attribute" qualified-key "in form has no subform ::form/ui, and no ::attr/target."))
                                            [{qualified-key (comp/get-query fake-component)}]))))
                              refs)]
     full-query)))

(def ^:deprecated parse-long "moved to integer.cljs" int/parse-long)

;; ─────────────────────────────────────────────────────────────────────────────
;; State / defaults
;; ─────────────────────────────────────────────────────────────────────────────

(defn form-pre-merge
  "Generate a pre-merge for a component that has the given form attribute map. Returns a proper
  pre-merge fn, or `nil` if none is needed."
  [component-options key->attribute]
  (let [sorters-by-k (into {}
                       (keep (fn [k]
                               (when-let [sorter (:com.fulcrologic.rad.form/sort-children (subform-options component-options (key->attribute k)))]
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

(defn form-and-subform-attributes
  "Find all attributes that are referenced by a form and all of its subforms, recursively."
  [cls]
  (let [options         (some-> cls (comp/component-options))
        base-attributes (fo/attributes options)
        subforms        (keep (fn [a] (fo/ui (subform-options options a))) base-attributes)]
    (into (set base-attributes)
      (mapcat form-and-subform-attributes subforms))))

(declare default-state)

(defn default-to-many
  "Use `default-state` on the top level form. This is part of the recursive implementation.

   Calculate a default value for any to-many attributes on the form."
  [FormClass attribute]
  (let [form-options  (comp/component-options FormClass)
        {::attr/keys [qualified-key]} attribute
        default-value (fo/get-default-value form-options attribute)]
    (enc/if-let [SubClass (subform-ui form-options attribute)]
      (do
        (when-not SubClass
          (log/error "Subforms for class" (comp/component-name FormClass)
            "must include a ::form/ui entry for" qualified-key))
        (if (or (nil? default-value) (vector? default-value))
          (mapv (fn [v]
                  (let [id          (tempid/tempid)
                        base-entity (?! v id)
                        [k iid :as ident] (comp/get-ident SubClass base-entity)
                        ChildForm   (if (comp/union-component? SubClass)
                                      (some-> SubClass comp/get-query (get k) comp/query->component)
                                      SubClass)
                        id-key      (some-> ChildForm comp/component-options :com.fulcrologic.rad.form/id ::attr/qualified-key)]
                    (when-not ChildForm
                      (log/error "Union subform's default-value function failed to assign the ID. Cannot determine which kind of thing we are creating"))
                    (merge
                      (default-state ChildForm id)
                      base-entity
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

  Generates the default value for a to-one ref in a new instance of a form set."
  [FormClass attribute]
  (let [form-options  (comp/component-options FormClass)
        {::attr/keys [qualified-key]} attribute
        default-value (fo/get-default-value form-options attribute)
        SubClass      (subform-ui form-options attribute)
        new-id        (tempid/tempid)
        id-key        (some-> SubClass (comp/component-options :com.fulcrologic.rad.form/id ::attr/qualified-key))]
    (when-not (comp/union-component? SubClass)
      (when-not SubClass
        (log/error "Subforms for class" (comp/component-name FormClass)
          "must include a ::form/ui entry for" qualified-key))
      (when-not (keyword? id-key)
        (log/error "Subform class" (comp/component-name SubClass)
          "must include a ::form/id that is an attr/attribute"))
      (if id-key
        (merge
          (default-state SubClass new-id)
          (when (map? default-value) default-value)
          {id-key new-id})
        {}))))

(defn default-state
  "Generate a potentially recursive tree of data that represents the tree of initial
  state for the given FormClass. Such generated trees will be rooted with the provided `new-id`."
  [FormClass new-id]
  (when-not (tempid/tempid? new-id)
    (throw (ex-info (str "Default state received " new-id " for a new form ID. It MUST be a Fulcro tempid.")
             {})))
  (if (comp/union-component? FormClass)
    {}
    (let [{:com.fulcrologic.rad.form/keys [id attributes default-values initialize-ui-props field-styles]}
          (comp/component-options FormClass)
          {id-key ::attr/qualified-key} id
          entity (reduce
                   (fn [result {::attr/keys                    [qualified-key type field-style]
                                :com.fulcrologic.rad.form/keys [default-value] :as attr}]
                     (let [field-style   (?! (or (get field-styles qualified-key) field-style))
                           default-value (?! (get default-values qualified-key default-value))]
                       (cond
                         (and (not field-style) (= :ref type) (attr/to-many? attr))
                         (assoc result qualified-key (default-to-many FormClass attr))

                         (and default-value (not field-style) (= :ref type) (not (attr/to-many? attr)))
                         (assoc result qualified-key (default-to-one FormClass attr))

                         :otherwise
                         (if-not (nil? default-value)
                           (assoc result qualified-key default-value)
                           result))))
                   {id-key new-id}
                   attributes)]
      (merge (?! initialize-ui-props FormClass entity) entity))))

(defn mark-fields-complete*
  "Helper function against app state. Marks `target-keys` as complete on the form given a set of
   keys that you consider initialized."
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

(defn update-tree*
  "Run the given `(xform ui-props)` against the current ui props of `component-class`'s instance at
   `component-ident` in `state-map`. Returns an updated state map."
  [state-map xform component-class component-ident]
  (if (and xform component-class component-ident)
    (let [ui-props      (fns/ui->props state-map component-class component-ident)
          new-ui-props  (xform ui-props)
          new-state-map (merge/merge-component state-map component-class new-ui-props)]
      new-state-map)
    state-map))

;; ─────────────────────────────────────────────────────────────────────────────
;; Field helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn computed-value
  "Returns the computed value of the given attribute on the form from `env` (if it is a computed attribute)."
  [env {::attr/keys [computed-value] :as attr}]
  (when computed-value
    (computed-value env attr)))

(defn field-label
  "Returns a human readable label for a given attribute."
  [form-env attribute]
  (let [{:com.fulcrologic.rad.form/keys [form-instance]} form-env
        k           (::attr/qualified-key attribute)
        options     (comp/component-options form-instance)
        field-label (?! (or
                          (get-in options [:com.fulcrologic.rad.form/field-labels k])
                          (:com.fulcrologic.rad.form/field-label attribute)
                          (ao/label attribute)
                          (some-> k name str/capitalize (str/replace #"-" " "))) form-instance)]
    field-label))

(>defn field-visible?
  "Should the `attr` on the given `form-instance` be visible?"
  [form-instance {:com.fulcrologic.rad.form/keys [field-visible?]
                  ::attr/keys                    [qualified-key] :as attr}]
  [comp/component? ::attr/attribute => boolean?]
  (let [form-field-visible? (?! (comp/component-options form-instance :com.fulcrologic.rad.form/fields-visible? qualified-key) form-instance attr)
        field-visible?      (?! field-visible? form-instance attr)]
    (boolean
      (or
        (true? form-field-visible?)
        (and (nil? form-field-visible?) (true? field-visible?))
        (and (nil? form-field-visible?) (nil? field-visible?))))))

(>defn omit-label?
  "Should the `attr` on the given `form-instance` refrain from including a field label?"
  [form-instance {:com.fulcrologic.rad.form/keys [omit-label?]
                  ::attr/keys                    [qualified-key] :as attr}]
  [comp/component? ::attr/attribute => boolean?]
  (let [form-omit?  (?! (comp/component-options form-instance :com.fulcrologic.rad.form/omit-label? qualified-key) form-instance attr)
        field-omit? (?! omit-label? form-instance attr)]
    (cond
      (boolean? form-omit?) form-omit?
      (boolean? field-omit?) field-omit?
      :else false)))

(>defn field-style-config
  "Get the value of an overridable field-style-config option."
  [{:com.fulcrologic.rad.form/keys [form-instance]} attribute config-key]
  [:com.fulcrologic.rad.form/form-env ::attr/attribute keyword? => any?]
  (let [{::attr/keys [qualified-key field-style-config]} attribute
        form-value      (comp/component-options form-instance :com.fulcrologic.rad.form/field-style-configs qualified-key config-key)
        attribute-value (get field-style-config config-key)]
    (if (and (map? form-value) (map? attribute-value))
      (deep-merge attribute-value form-value)
      (or form-value attribute-value))))

(>defn field-autocomplete
  "Returns the proper string (or nil) for a given attribute's autocomplete setting."
  [{:com.fulcrologic.rad.form/keys [form-instance] :as _env} attribute]
  [:com.fulcrologic.rad.form/form-env ::attr/attribute => any?]
  (let [{::attr/keys                    [qualified-key]
         :com.fulcrologic.rad.form/keys [autocomplete]} attribute
        override     (comp/component-options form-instance :com.fulcrologic.rad.form/auto-completes qualified-key)
        autocomplete (if (nil? override) autocomplete override)
        autocomplete (if (boolean? autocomplete) (if autocomplete "on" "off") autocomplete)]
    autocomplete))

;; ─────────────────────────────────────────────────────────────────────────────
;; Validation
;; ─────────────────────────────────────────────────────────────────────────────

(defn invalid?
  "Returns true if the validator on the form in `env` indicates that some form field(s) are invalid."
  ([form-rendering-env]
   (let [{:com.fulcrologic.rad.form/keys [form-instance]} form-rendering-env
         props (comp/props form-instance)]
     (invalid? form-instance props)))
  ([form-class-or-instance props]
   (let [{:com.fulcrologic.rad.form/keys [validator]} (comp/component-options form-class-or-instance)]
     (and validator (= :invalid ((->arity-tolerant validator) props))))))

(defn valid?
  "Returns true if the validator on the form in `env` indicates that all of the form fields are valid."
  ([form-rendering-env]
   (let [{:com.fulcrologic.rad.form/keys [form-instance]} form-rendering-env
         props (comp/props form-instance)]
     (valid? form-instance props)))
  ([form-class-or-instance props]
   (let [{:com.fulcrologic.rad.form/keys [attributes validator]} (comp/component-options form-class-or-instance)
         required-attributes   (filter ::attr/required? attributes)
         all-required-present? (or
                                 (empty? required-attributes)
                                 (every?
                                   (fn [attr]
                                     (let [k   (ao/qualified-key attr)
                                           v   (get props k)
                                           ok? (if (= :ref (ao/type attr))
                                                 (not (empty? v))
                                                 (some? v))]
                                       #?(:cljs
                                          (when (and goog.DEBUG (not ok?))
                                            (log/debug "Form is not valid because required attribute is missing:" k)))
                                       ok?))
                                   required-attributes))]
     (and
       all-required-present?
       (or
         (not validator)
         (and validator (= :valid ((->arity-tolerant validator) props))))))))

(defn invalid-attribute-value?
  "Returns true if the given `attribute` is invalid in the given form `env` context."
  [{:com.fulcrologic.rad.form/keys [form-instance master-form] :as _env} attribute]
  (let [k              (::attr/qualified-key attribute)
        props          (comp/props form-instance)
        value          (and attribute (get props k))
        checked?       (fs/checked? props k)
        required?      (get attribute ao/required? false)
        form-validator (comp/component-options master-form :com.fulcrologic.rad.form/validator)
        invalid?       (or
                         (and checked? required? (or (nil? value) (and (string? value) (empty? value))))
                         (and checked? (not form-validator) (not (attr/valid-value? attribute value props k)))
                         (and form-validator (= :invalid ((->arity-tolerant form-validator) props k))))]
    invalid?))

(defn validation-error-message
  "Get the string that should be shown for the error message on a given attribute in the given form context."
  [{:com.fulcrologic.rad.form/keys [form-instance master-form] :as _env}
   {:keys [:com.fulcrologic.rad.form/validation-message ::attr/qualified-key] :as attribute}]
  (let [props          (comp/props form-instance)
        value          (and attribute (get props qualified-key))
        master-message (comp/component-options master-form :com.fulcrologic.rad.form/validation-messages qualified-key)
        local-message  (comp/component-options form-instance :com.fulcrologic.rad.form/validation-messages qualified-key)
        message        (or
                         (?! master-message props qualified-key)
                         (?! local-message props qualified-key)
                         (?! validation-message value)
                         (tr "Invalid value"))]
    message))

;; ─────────────────────────────────────────────────────────────────────────────
;; Server-side
;; ─────────────────────────────────────────────────────────────────────────────

(defn save-form*
  "Internal implementation of clj-side form save. Can be used in your own mutations to accomplish writes through
   the save middleware.

   params MUST contain:

   * `::form/delta` - The data to save.
   * `::form/id` - The actual ID of the entity being changed.
   * `::form/master-pk` - The keyword representing the form's ID.

   Returns:

   {:tempid {} ; tempid remaps
    master-pk id} ; the k/id of the entity saved."
  [env params]
  (let [save-middleware (:com.fulcrologic.rad.form/save-middleware env)
        save-env        (assoc env :com.fulcrologic.rad.form/params params)
        result          (if save-middleware
                          (save-middleware save-env)
                          (throw (ex-info "form/pathom-plugin is not installed on the parser." {})))
        {:com.fulcrologic.rad.form/keys [id master-pk]} params
        {:keys [tempids]} result
        id              (get tempids id id)]
    (merge result {master-pk id})))

(def pathom2-server-save-form-mutation
  {:com.wsscode.pathom.connect/mutate (fn [env params] (save-form* env params))
   :com.wsscode.pathom.connect/sym    'com.fulcrologic.rad.form/save-form
   :com.wsscode.pathom.connect/params [:com.fulcrologic.rad.form/id
                                       :com.fulcrologic.rad.form/master-pk
                                       :com.fulcrologic.rad.form/delta]})

(def pathom2-server-save-as-form-mutation
  (assoc pathom2-server-save-form-mutation
    :com.wsscode.pathom.connect/sym 'com.fulcrologic.rad.form/save-as-form))

(def pathom2-server-delete-entity-mutation
  {:com.wsscode.pathom.connect/sym    'com.fulcrologic.rad.form/delete-entity
   :com.wsscode.pathom.connect/mutate (fn [env params]
                                        (if-let [delete-middleware (:com.fulcrologic.rad.form/delete-middleware env)]
                                          (let [delete-env (assoc env :com.fulcrologic.rad.form/params params)]
                                            (delete-middleware delete-env))
                                          (throw (ex-info "form/pathom-plugin in not installed on Pathom parser." {}))))})

(defn wrap-env
  "Build a (fn [env] env') that adds RAD form-related data to an env."
  ([save-middleware delete-middleware] (wrap-env nil save-middleware delete-middleware))
  ([base-wrapper save-middleware delete-middleware]
   (fn [env]
     (cond-> (assoc env
               :com.fulcrologic.rad.form/save-middleware save-middleware
               :com.fulcrologic.rad.form/delete-middleware delete-middleware)
       base-wrapper (base-wrapper)))))

(defn pathom-plugin
  "A pathom 2 plugin that installs general form save/delete support on the pathom parser."
  [save-middleware delete-middleware]
  (let [augment (wrap-env save-middleware delete-middleware)]
    {:com.wsscode.pathom.core/wrap-parser
     (fn env-wrap-wrap-parser [parser]
       (fn env-wrap-wrap-internal [env tx]
         (parser (augment env) tx)))}))

;; ─────────────────────────────────────────────────────────────────────────────
;; Misc
;; ─────────────────────────────────────────────────────────────────────────────

(defn server-errors
  "Given the top-level form instance (this), returns a vector of maps with server error information."
  [top-form-instance]
  (get (comp/props top-form-instance) :com.fulcrologic.rad.form/errors))

(defn install-field-renderer!
  "Install a `renderer` for the given attribute `type`, to be known as field `style`."
  [app type style render]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls
                                  :com.fulcrologic.rad.form/type->style->control
                                  type
                                  style] render)))

(defn install-form-container-renderer!
  "Install a renderer for a given `style` of form container."
  [app style render]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls
                                  :com.fulcrologic.rad.form/element->style->layout
                                  :form-container
                                  style] render)))

(defn install-form-body-renderer!
  "Install a renderer for a given `style` of form body."
  [app style render]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls
                                  :com.fulcrologic.rad.form/element->style->layout
                                  :form-body-container
                                  style] render)))

(defn install-form-ref-renderer!
  "Install a renderer for a given `style` of subform reference container."
  [app style render]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls
                                  :com.fulcrologic.rad.form/element->style->layout
                                  :ref-container
                                  style] render)))

;; ─────────────────────────────────────────────────────────────────────────────
;; Union helpers
;; ─────────────────────────────────────────────────────────────────────────────

(defn ident-from-props* [id-keys props]
  "Using known id-keys, construct an ident from props"
  (some
    (fn [k]
      (let [id (get props k)]
        (when (or (uuid? id) (int? id) (tempid/tempid? id))
          [k id])))
    id-keys))

(defn active-rad-form-in-union*
  "Finds the RAD form that has ident-k as the qualified form id. Returns [ident-k form-class]."
  [RADForms ident-k]
  (some
    (fn [c]
      (let [ck (-> c comp/component-options fo/id ao/qualified-key)]
        (when (= ck ident-k)
          [ident-k c])))
    RADForms))

(defn rad-form-of-union-from-props
  "Finds the RAD form in union-class that matches props. Returns a vector: [ident-k form-class]."
  [union-class props]
  (when-let [lookup-fn (:RADFormLookup (comp/component-options union-class))]
    (lookup-fn props)))

#?(:clj
   (defmacro defunion
     "Create a union component out of two or more RAD forms."
     [sym & RADForms]
     (let [id-keys     `(mapv (comp ao/qualified-key fo/id comp/component-options) [~@RADForms])
           nspc        (if (comp/cljs? &env) (-> &env :ns :name str) (name (ns-name *ns*)))
           union-key   (keyword (str nspc) (name sym))
           ident-fn    `(fn [_# props#] (ident-from-props* ~id-keys props#))
           options-map {:query         `(fn [_#] (zipmap ~id-keys (map comp/get-query [~@RADForms])))
                        :ident         ident-fn
                        :componentName sym
                        :RADFormLookup `(fn [props#]
                                          (let [[k# _id#] (ident-from-props* ~id-keys props#)]
                                            (active-rad-form-in-union* [~@RADForms] k#)))
                        :render        `(fn [this#]
                                          (comp/wrapped-render this#
                                            (fn []
                                              (enc/when-let [props#   (comp/props this#)
                                                             [k#] (comp/get-ident this#)
                                                             [_ck# c#] (active-rad-form-in-union* [~@RADForms] k#)
                                                             factory# (comp/computed-factory c# {:keyfn k#})]
                                                (factory# props#)))))}]
       (if (comp/cljs? &env)
         `(do
            (declare ~sym)
            (let [options# ~options-map]
              (defonce ~(vary-meta sym assoc :jsdoc ["@constructor"])
                (comp/react-constructor nil))
              (com.fulcrologic.fulcro.components/configure-component! ~sym ~union-key options#)))
         `(do
            (declare ~sym)
            (let [options# ~options-map]
              (def ~(vary-meta sym assoc :once true)
                (com.fulcrologic.fulcro.components/configure-component! ~(str sym) ~union-key options#))))))))

;; ─────────────────────────────────────────────────────────────────────────────
;; Macro helpers
;; ─────────────────────────────────────────────────────────────────────────────

#?(:clj
   (s/def ::defsc-form-args (s/cat
                              :sym symbol?
                              :doc (s/? string?)
                              :arglist (s/and vector? #(<= 2 (count %) 5))
                              :options map?
                              :body (s/* any?))))

#?(:clj
   (s/def ::defsc-form-options (s/keys :req [::attr/attributes])))

#?(:clj
   (defn form-body [argslist body]
     (if (empty? body)
       `[(render-layout ~(first argslist) ~(second argslist))]
       body)))

#?(:clj
   (defn defsc-form*
     "Helper function for the defsc-form macro. `convert-options-sym` must be a fully-qualified
      symbol naming the function that converts form options to Fulcro component options for the
      engine in use. The UISM engine passes `'com.fulcrologic.rad.form/convert-options`."
     [env args convert-options-sym]
     (let [{:keys [sym doc arglist options body]} (s/conform ::defsc-form-args args)
           options      (if (map? options)
                          (opts/macro-optimize-options env options
                            #{:com.fulcrologic.rad.form/subforms
                              :com.fulcrologic.rad.form/validation-messages
                              :com.fulcrologic.rad.form/field-styles} {})
                          options)
           hooks?       (and (comp/cljs? env) (:use-hooks? options))
           nspc         (if (comp/cljs? env) (-> env :ns :name str) (name (ns-name *ns*)))
           fqkw         (keyword (str nspc) (name sym))
           body         (form-body arglist body)
           [thissym propsym computedsym extra-args] arglist
           location     (str nspc "." sym)
           render-form  (if hooks?
                          (#'comp/build-hooks-render sym thissym propsym computedsym extra-args body)
                          (#'comp/build-render sym thissym propsym computedsym extra-args body))
           options-expr `(let [get-class# (fn [] ~sym)]
                           (assoc (~convert-options-sym get-class# ~location ~options)
                             :render ~render-form
                             :componentName ~fqkw))]
       (when (some #(= '_ %) arglist)
         (throw (ana/error env "The arguments of defsc-form must be unique symbols other than _.")))
       (cond
         hooks?
         `(do
            (declare ~sym)
            (let [options# ~options-expr]
              (defonce ~sym
                (fn [js-props#]
                  (let [render# (:render (comp/component-options ~sym))
                        [this# props#] (comp/use-fulcro js-props# ~sym)]
                    (render# this# props#))))
              (comp/add-hook-options! ~sym options#)))

         (comp/cljs? env)
         `(do
            (declare ~sym)
            (let [options# ~options-expr]
              (defonce ~(vary-meta sym assoc :doc doc :jsdoc ["@constructor"])
                (comp/react-constructor (:initLocalState options#)))
              (com.fulcrologic.fulcro.components/configure-component! ~sym ~fqkw options#)))

         :else
         `(do
            (declare ~sym)
            (let [options# ~options-expr]
              (def ~(vary-meta sym assoc :doc doc :once true)
                (com.fulcrologic.fulcro.components/configure-component! ~(str sym) ~fqkw options#))))))))
