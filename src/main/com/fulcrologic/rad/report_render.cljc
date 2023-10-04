(ns com.fulcrologic.rad.report-render
  "Multimethods for creating new styles of report rendering. Currently Uses the form render-hierarchy to specify
   the mm hierarchy for dispatch."
  (:require
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.form-render :as fr]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.report-render-options :as rro]))

(defn gen-dispatch
  "Generate a multimethod dispatch function that assumes the attribute is an id-attribute (being called
   in the context of the rendering env's form-instance) and attempts to find the `style-key` in
   the subform options of the parent form (to override any declared on the form itself), then
   on the form-instance, and finally on the attribute.

   If `fallback-to-style?` is true, then if it does not find `style-key` it will repeat the sequence using
   fro/style."
  [style-key fallback-to-style?]
  (fn [this report-options]
    (let [k     (ao/qualified-key (ro/row-pk report-options))
          style (or
                  (?! (style-key report-options) this)
                  (and fallback-to-style? (?! (rro/style report-options) this))
                  :default)]
      [k style])))

(defmulti render-report
  "[this report-options]

   Render the entire report (controls, body, rows, etc.). Default uses the rendering plugin."
  (gen-dispatch rro/style false)
  :hierarchy #?(:cljs fr/render-hierarchy
                :clj  (var fr/render-hierarchy)))

(defmulti render-body
  "[this report-options]

   Render the body of the report. This method is not used by any of the build-in defaults, but is available
   for when you define your own reports as a way of creating a wrapper for the rows (e.g. dom table)."
  (gen-dispatch rro/body-style true)
  :hierarchy #?(:cljs fr/render-hierarchy
                :clj  (var fr/render-hierarchy)))

(defmulti render-row
  "[this report-options row-props]

   Render a single row of the report. Defaults to the rendering plugin. Intended to be called from `rr/render-body` when
   creating new styles."
  (fn [this report-options row-props]
    (let [k     (ao/qualified-key (ro/row-pk report-options))
          style (or
                  (?! (rro/row-style report-options) this)
                  (?! (rro/style report-options) this)
                  :default)]
      [k style]))

  :hierarchy #?(:cljs fr/render-hierarchy
                :clj  (var fr/render-hierarchy)))

(defmulti render-controls
  "[this report-options]

   Render the controls of the report. Defaults to the UI plugin's rendering of the controls."
  (gen-dispatch rro/control-style true)
  :hierarchy #?(:cljs fr/render-hierarchy
                :clj  (var fr/render-hierarchy)))

(defmulti render-header
  "[this report-options]

   Render a header. Not called by default, but exists so you can compose elements of a style together in your
   customizations. You could render the heading of the rows in render-body, or could leverage this method."
  (gen-dispatch rro/header-style true)
  :hierarchy #?(:cljs fr/render-hierarchy
                :clj  (var fr/render-hierarchy)))

(defmulti render-footer
  "[this report-options]

   Render a footer. Not called by default, but exists so you can compose elements of a style together in your
   customizations. You could render a footer of the rows or report in render-body, or could leverage this method."
  (gen-dispatch rro/footer-style true)
  :hierarchy #?(:cljs fr/render-hierarchy
                :clj  (var fr/render-hierarchy)))
