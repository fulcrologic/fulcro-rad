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
   other multimethods (render-fields, render-field, render-header, and render-footer) are defined as well, though
   have no defaults other than `render-field` (whose default calls through to the underlying UI plugin).

   All of these multimethods use vectors for dispatch, and a custom hierarchy that this sets things up to allow for
   polymorphism among the rendering methods.

   This ns has some methods for working with that hierarchy:

   * `derive!` - Add a parent/child relationship between keywords
   * `allow-defaults!` - Pre-calls `derive!` on every qualified keyword in the given RAD model so that :default
     behaves as the parent of them.
   * `is-a?` - Just like clojure.core/isa?, but works on the render hierarchy
  "
  (:refer-clojure :exclude [isa?])
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-render-options :as fro]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]))

(defonce render-hierarchy #?(:cljs (atom (make-hierarchy))
                             :clj  (make-hierarchy)))

(defmulti render-form
  "[rendering-env id-attr]

   Dispatches on [attr-key style].

   Render a form using the given environment. This is the top-level call from the (default) body of any
   form. Normally it might call `render-header`, `render-fields`, and `render-footer`.

   Recommended that you call `allow-defaults!` on your RAD model so that you can
   dispatch just on style and only customize on keys if really necessary."
  (fn [{:com.fulcrologic.rad.form/keys [form-instance parent parent-relation] :as renv} id-attr]
    (let [parent-options (some-> parent (rc/component-options))
          parent-style   (when parent-options (fro/style (fo/subform-options parent-options parent-relation)))
          style          (or
                           (?! parent-style id-attr renv)
                           (?! (-> (rc/component-options form-instance) (fro/style)) id-attr renv)
                           (?! (fro/style id-attr) id-attr renv)
                           :default)
          k              (ao/qualified-key id-attr)]
      ;; NOTE: hierarchy maybe? (derive :sales/invoice :invoice/id). Make the rendering of :invoice/id forms be a
      ;; "kind" of :sales/invoice. Still need the possible dispatch to default style.
      (log/spy :debug (str "dispatch render-form" k) [k style])))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-header
  "[env attr]

   Render something before the given attr. The id attr will be passed when it is the
   entire form *itself*. This MAY be composed into rendering
   any time you want. Typically, it is composed in front of entire forms, and when
   there is nested content (like subforms).

   Dispatch on [attr-key fro/header-style].

   Recommended that you call `allow-defaults!` on your RAD model so that you can
   dispatch just on style and only customize on keys if really necessary."
  (fn [{:com.fulcrologic.rad.form/keys [form-instance] :as renv} attr]
    (let [options   (rc/component-options form-instance)
          sfoptions (fo/subform-options options attr)
          style     (or
                      (?! (fro/header-style sfoptions) attr renv)
                      (?! (fro/header-style attr) attr renv)
                      (?! (fro/style sfoptions) attr renv)
                      (?! (fro/style attr) attr renv)
                      :default)]
      [(ao/qualified-key attr) style]))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-fields
  "[rendering-env id-attribute]

   Render the fields of the current `form-instance `in `renv `.

   Dispatches on [form-id-keyword style]."
  (fn [renv id-attr]
    (let [style (or
                  (?! (fro/style id-attr) id-attr renv)
                  :default)]
      ;; NOTE: hierarchy maybe? (derive :sales/invoice :invoice/id). Make the rendering of :invoice/id forms be a
      ;; "kind" of :sales/invoice. Still need the possible dispatch to default style.
      [(ao/qualified-key id-attr) style]))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-footer
  " [renv attr]

  Render something after the given attr. This MAY be composed into rendering
  any time you want. Typically, it is composed behind entire forms, and when
  there is nested content (like subforms) .

  Dispatches on [qualified-key fro/footer-style] .

  Recommended that you call `allow-defaults! `on your RAD model so that you can
  dispatch just on style and only customize on keys if really necessary. "
  (fn [{:com.fulcrologic.rad.form/keys [form-instance] :as renv} attr]
    (let [options   (rc/component-options form-instance)
          sfoptions (fo/subform-options options attr)
          style     (or
                      (?! (fro/footer-style sfoptions) attr renv)
                      (?! (fro/footer-style attr) attr renv)
                      (?! (fro/style sfoptions) attr renv)
                      (?! (fro/style attr) attr renv)
                      :default)]
      [(ao/qualified-key attr) style]))
  :hierarchy #?(:cljs render-hierarchy
                :clj  (var render-hierarchy)))

(defmulti render-field
  "[env attr]

  Render a field.

  Dispatches on [type style].
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
  [parent-keyword child-keyword]
  #?(:cljs (swap! render-hierarchy derive child-keyword parent-keyword)
     :clj  (alter-var-root #'render-hierarchy derive child-keyword parent-keyword)))

(defn install-as!
  "Install the new form rendering support as a custom layout style `k `. "
  [app k]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [::rad/controls :com.fulcrologic.rad.form/element->style->layout :form-container k]
      (fn [renv]
        (render-form renv (fo/id (rc/component-options (:com.fulcrologic.rad.form/form-instance renv))))))))

(defn allow-defaults!
  " Allow :default to be a fall-through any time an attribute's qualified key is used as a dispatch value on
  rendering multimethods. "
  [all-attrs]
  (doseq [a all-attrs]
    (derive! :default (ao/qualified-key a))))

(defn isa?
  " Just like clojure.core/isa?, but uses the render-hierarchy. "
  [child parent]
  #?(:cljs (clojure.core/isa? @render-hierarchy child parent)
     :clj  (clojure.core/isa? render-hierarchy child parent)))
