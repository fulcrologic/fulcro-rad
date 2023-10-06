(ns com.fulcrologic.rad.form-render
  "An extensible form rendering system that uses styles to allow you to generate rendering for forms that conform to patterns.

   See Form Rendering in the RAD book.

   Features:

   The renv is expected to be a form rendering-env, which contains things like:

   * ::form/master-form - The top-most form in the entire form tree
   * ::form/form-instance - Equivalent to this form
   * ::form/parent - when in a subform, this is the immediate parent
   * ::form/parent-relation - when in a subform, this is the ref key that was followed to get there.

   The primary idea is that the `render-form` multimethod is called by RAD's generated forms, and the default for
   that does whatever the current rendering plugin has configured.

   By placing an `fro/style` on a form, you can cause a dispatch to your own `defmethod` to do that rendering. Several
   other multimethods (render-fields, render-field, render-header, and render-footer) are defined as well (along
   with `fro/header-style` etc.), though these have no defaults other than `render-field` (whose default calls
   through to the underlying UI plugin).

   All of these multimethods use vectors for dispatch, and a custom hierarchy that this sets things up to allow for
   polymorphism among the rendering methods.

   This ns has some methods for working with that hierarchy:

   * `derive!` - Add a parent/child relationship between keywords
   * `allow-defaults!` - Pre-calls `derive!` on every qualified keyword in the given RAD model so that :default
     behaves as the parent of them.
   * `is-a?` - Just like clojure.core/isa?, but works on the render hierarchy

   See the RAD Developer's Guide for more information on leveraging the
   dispatch hierarchy.
  "
  (:refer-clojure :exclude [isa?])
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.form-render-options :as fro]
    [com.fulcrologic.rad.options-util :refer [?!]]))

(defonce render-hierarchy #?(:cljs (atom (make-hierarchy))
                             :clj  (make-hierarchy)))

(defn gen-dispatch
  "Generate a multimethod dispatch function that assumes the attribute is an id-attribute (being called
   in the context of the rendering env's form-instance) and attempts to find the `style-key` in
   the subform options of the parent form (to override any declared on the form itself), then
   on the form-instance, and finally on the attribute.

   If `fallback-to-style?` is true, then if it does not find `style-key` it will repeat the sequence using
   fro/style."
  [style-key fallback-to-style?]
  (fn [{:com.fulcrologic.rad.form/keys [form-instance parent parent-relation] :as renv} id-attr]
    (let [parent-options         (some-> parent (rc/component-options))
          parent-subform-options (some-> parent-options (fo/subform-options parent-relation))
          style                  (or
                                   (?! (style-key parent-subform-options) id-attr renv)
                                   (?! (style-key (rc/component-options form-instance)) id-attr renv)
                                   (?! (style-key id-attr) id-attr renv)
                                   (and fallback-to-style?
                                     (or
                                       (?! (fro/style parent-subform-options) id-attr renv)
                                       (?! (fro/style (rc/component-options form-instance)) id-attr renv)
                                       (?! (fro/style id-attr) id-attr renv)))
                                   :default)
          k                      (ao/qualified-key id-attr)]
      [k style])))

(defmulti render-form
  "[rendering-env id-attr]

   Render a form using the given environment. This is the top-level call from the (default) body of any
   form that switches rendering to these multimethods. Normally it might call `render-header`,
   `render-fields`, and `render-footer`.

   Dispatches to a vector of `[id-keyword-of-entity style]`

   The style is derived as follows:

   * The dispatch function will first look to see if it is rendering as a subform
     * if so will find the subform options on the `parent` for `parent-relation` and look for the fro/style there.
   * If that fails, it will look for the fro/style on the form-instance being rendered
   * finally will look on the attribute.
   * Otherwise it will use a style of `:default`

   See namespace documentation as well.
   "
  (gen-dispatch fro/style false)
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-header
  "[env attr]

   Render something before the given attr. The id attr will be passed when it is the
   entire form *itself*. This MAY be composed into rendering
   any time you want. Typically, it is composed in front of entire forms, and when
   there is nested content (like subforms).

   Dispatch on [attr-key style].

   The style is derived as follows:

   * If `attr` is an `ao/identity?` attribute
   ** dispatch identically to render-form, but looking for `fro/header-style` (preferred) and `fro/style` fallback.
   * If it is NOT an id attribute, then:
   ** Look for fro/header-style on the current form's subform options at the qualified key of attr
   ** Look for fro/header-style on the attr
   ** Look for fro/style on the current form's subform options at the qualified key of attr
   ** Look for fro/style on the attr

   Otherwise style will be `:default`

   See namespace documentation as well.
   "
  (let [id-attr-dispatch (gen-dispatch fro/header-style true)]
    (fn [{:com.fulcrologic.rad.form/keys [form-instance] :as renv} attr]
      (if (ao/identity? attr)
        (id-attr-dispatch renv attr)
        (let [options   (rc/component-options form-instance)
              sfoptions (fo/subform-options options attr)
              style     (or
                          (?! (fro/header-style sfoptions) attr renv)
                          (?! (fro/header-style attr) attr renv)
                          (?! (fro/style sfoptions) attr renv)
                          (?! (fro/style attr) attr renv)
                          :default)]
          [(ao/qualified-key attr) style]))))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-fields
  "[rendering-env id-attribute]

   Render the fields. Dispatch is identical to `render-form` ([id-key style]), and this method is always intended to be
   called in the context of the currently-rendering form-instance with an id-attribute.

   The style will first try to find fro/fields-style, and then fro/style.

   See namespace documentation as well.
   "
  (gen-dispatch fro/fields-style true)
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-footer
  "[renv attr]

   Dispatch works identically to `render-header`, other than it looks for `fro/footer-style` and then `fro/style`.

   See namespace documentation as well.
   "
  (let [id-attr-dispatch (gen-dispatch fro/footer-style true)]
    (fn [{:com.fulcrologic.rad.form/keys [form-instance] :as renv} attr]
      (if (ao/identity? attr)
        (id-attr-dispatch renv attr)
        (let [options   (rc/component-options form-instance)
              sfoptions (fo/subform-options options attr)
              style     (or
                          (?! (fro/footer-style sfoptions) attr renv)
                          (?! (fro/footer-style attr) attr renv)
                          (?! (fro/style sfoptions) attr renv)
                          (?! (fro/style attr) attr renv)
                          :default)]
          [(ao/qualified-key attr) style]))))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-field
  "[env attr]

   Render a field.

   Dispatches on [type style].

   The style is derived as follows:

   * Look for fro/style on the subform options. This is for the case of ref attributes where the field render
     (which might need to wrap a to-many collection) must know the context that the subform will be rendered in,
     and that should be preferred.
   * Then look in form `fo/field-styles` (map from k -> field style)
   * Then look for `fro/style` on the attribute
   * Then look for `fo/field-style` on the attribute
   * Then look for `ao/style` on the attribute
   * Otherwise :default.

   See namespace documentation as well.
   "
  (fn [{:com.fulcrologic.rad.form/keys [form-instance] :as renv} field-attr]
    (let [options   (rc/component-options form-instance)
          k         (ao/qualified-key field-attr)
          sfoptions (fo/subform-options options field-attr)
          style     (or
                      (?! (fro/style sfoptions) field-attr renv)
                      (?! (get-in options [fo/field-styles k]) form-instance)
                      (?! (fro/style field-attr) renv)
                      (?! (fo/field-style field-attr) form-instance)
                      (?! (ao/style field-attr) form-instance)
                      :default)]
      [(ao/type field-attr) style]))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defn derive!
  "Cause the given `child-keyword `to act as-if it were `parent-keyword `in the rendering multimethods. This
  does a `derive `on a custom hierarchy that is used for the rendering multimethods. The relation can be between
  styles, RAD attribute keys, etc.

  If you add your own multimethods you may choose to use `render-hierarchy `from this namespace to get all of these.
  e.g. `(defmulti sym (fn [] ...) :hierarchy gf/render-hierarchy) `. See Clojure documentation on multimethods
  and derive. "
  [child-keyword parent-keyword]
  #?(:cljs (swap! render-hierarchy derive child-keyword parent-keyword)
     :clj  (alter-var-root #'render-hierarchy derive child-keyword parent-keyword)))

(defn allow-defaults!
  " Allow :default to be a fall-through any time an attribute's qualified key is used as a dispatch value on
  rendering multimethods. "
  [all-attrs]
  (doseq [a all-attrs]
    (derive! (ao/qualified-key a) :default)))

(defn isa?
  " Just like clojure.core/isa?, but uses the render-hierarchy. "
  [child parent]
  #?(:cljs (clojure.core/isa? @render-hierarchy child parent)
     :clj  (clojure.core/isa? render-hierarchy child parent)))
