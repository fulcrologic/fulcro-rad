(ns com.fulcrologic.rad.semantic-ui-options
  "Documented option keys for setting rendering-specific customization
  options when using Semantic UI Plugin as your DOM renderer.

  ALL options MUST appear under the rendering options key:

  ```
  (ns ...
    (:require
       [com.fulcrologic.rad.semantic-ui-options :as suo]
       ...))

  (defsc-report Report [this props]
    {suo/rendering-options { ... }}}
  ```

  Most of the options in this file can be given a global default using

  ```
  (set-global-rendering-options! fulcro-app options)
  ```

  where the `options` is a map of option keys/values.
  "
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.components :as comp]))

(def rendering-options
  "Top-level key for specifying rendering options. All
   SUI customization options MUST appear under this key."
  ::rendering-options)

(def report-action-button-grouping
  "A string or `(fn [report-instance] string?)`.
   CSS class(es) to put in the div that surrounds the action buttons.

   Defaults to 'ui right floated buttons'."
  ::report-action-button-grouping)

(def report-row-button-grouping
  "A string or `(fn [report-instance] string?)`.
   CSS class(es) to put in the div that surrounds the action buttons on a table row.

   Defaults to 'ui buttons'."
  ::report-row-button-grouping)

(def report-row-button-renderer
  "A `(fn [instance row-props {:keys [key disabled?]}] dom-element)`.

  * `instance` - the report instance
  * `row-props` - the data props of the row
  * `key` - a unique key that can be used for react on the element.
  * `onClick` - a generated function according to the buton's action setting
  * `disabled?`-  true if the calculation of your disabled? option is true.

  Overrides the rendering of action button controls.

  You must return a DOM element to render for the control. If you return nil then
  the default (button) will be rendered."
  ::report-row-button-renderer)

(def action-button-render
  "A `(fn [instance {:keys [key control disabled? loading?]}] dom-element)`.

  * `key` - the key you used to add it to the controls list.
  * `control` - the map of options you gave for the control.
  * `disabled?`-  true if the calculation of your disabled? option is true.
  * `loading?` - true if the component is loading data.

  Overrides the rendering of action button controls.

  You must return a DOM element to render for the control. If you return nil then
  the default (button) will be rendered."
  ::action-button-render)

(def layout-class
  "The CSS class of the div that holds the top-level layout of the report or form.  Defaults
   to some variant of 'ui segment'.

   A string or `(fn [instance] string?)`."
  ::layout-class)

(def controls-class
  "The CSS class of the div that holds the controls on layouts that have a control section. Defaults
   to some variant of 'ui top attached segment'.

   A string or `(fn [instance] string?)`."
  ::controls-class)

(def body-class
  "The CSS class of the div that holds the actual body of the page (e.g. form or report).
   Defaults to some variant of 'ui attached segment'.

   A string or `(fn [instance] string?)`."
  ::body-class)

(def report-table-class
  "The CSS class of generated report tables. Defaults to 'ui selectable table'.

  A string or `(fn [report-instance] string?)`."
  ::report-table-class)

(def report-rotated-table-class
  "The CSS class of generated report tables that are rotated. Defaults to 'ui compact collapsing definition selectable table'.

  A string or `(fn [report-instance] string?)`."
  ::report-rotated-table-class)

(def report-table-header-class
  "The CSS class of headers in a table-style report. Data cells defaults to nothing.
   Action buttons are have a column index and default to 'collapsing'.

   A `(fn [report-instance zero-based-column-index] string?)`.

   NOTE: Action buttons are add and have a column index. They default to 'collapsing'"
  ::report-table-header-class)

(def report-table-cell-class
  "The CSS class of cells in a table-style report. Defaults to nothing for normal tables, and 'right aligned' for
   rotated ones. Action buttons are have a column index and default to 'collapsing'.

  A `(fn [report-instance zero-based-column-index] string?)`.

  "
  ::report-table-cell-class)

(def selectable-table-rows?
  "A boolean. When true the table will support click on a row to affix a highlight to that row."
  ::selectable-table-rows?)

(def report-controls-row-class
  "A string or `(fn [report-instance zero-based-row-idx] string?)`. A function that returns the CSS class
   for the given row of the report controls input form. Defaults to 'n fields', where n
   is one, two, etc.  The `zero-based-row-idx` is the row being rendered."
  ::report-controls-row-class)

(defn get-rendering-options
  "Get rendering options from a mounted component `c`.

   WARNING: If c is a class, then global overrides will not be honored."
  ([c & ks]
   (let [app            (comp/any->app c)
         global-options (some-> app
                          :com.fulcrologic.fulcro.application/runtime-atom
                          deref
                          ::rendering-options)
         options        (merge
                          global-options
                          (comp/component-options c ::rendering-options))]
     (if (seq ks)
       (get-in options (vec ks))
       options))))

(defn set-global-rendering-options!
  "Set rendering options on the application such that they serve as *defaults*.

  The `options` parameter to this function MUST NOT have the key suo/rendering-options, but
  should instead just have the parameters themselves (e.g. ::suo/action-button-renderer).
  "
  [app options]
  (swap! (:com.fulcrologic.fulcro.application/runtime-atom app)
    assoc
    ::rendering-options
    options))
