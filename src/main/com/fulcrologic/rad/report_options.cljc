(ns com.fulcrologic.rad.report-options
  "Documented definitions of the standard report options. These provide easy access to documentation for the options,
  along with preventing spelling errors when using the keys in definitions. Plugin authors are encouraged to
  write their own options files to get the same benefits.

  Reports currently require a minimum of two options:

  * `row-pk`
  * `columns`
  * `source-attribute`

  NOTE to maintainers and Plugin authors: These files must be CLJC to make sure the symbols are resolvable
  at *compile* time. No dynamic tricks please. The form and report macros must be able to resolve the option
  symbols during evaluation.")

(def row-pk
  "An *attribute* that will serve as each row's primary key. May be a virtual attribute that is not actually stored
   in the database, but the attribute must have a resolver that will return a stable value. Reports that generate
   aggregated rows, for example, might use a combination of things to generate this value server-side."
  :com.fulcrologic.rad.report/row-pk)

(def columns
  "A vector of *attributes* that describe the columns of the report (when tabular). In non-tabular reports this
  is still needed, but the renderer can choose to use the per-row data however it wants (points on a graph, etc)."
  :com.fulcrologic.rad.report/columns)

(def source-attribute
  "A *qualified keyword* that will be used as the entry-point key for the query. The data source server must
   have a resolver that can start queries at that keyword (global resolver)."
  :com.fulcrologic.rad.report/source-attribute)

(def title
  "A string or `(fn [report-instance] string-or-element?)` that generates the title for this form. Can return a string
   and many rendering plugins also allow a UI element."
  :com.fulcrologic.rad.report/title)

(def form-links
  "A shorthand for turning column values into links to their respective form for editing.  You can get similar
   (but possibly more advanced) results using `field-formatters`, but this is a lot less code.

   This option is a map from *qualified key* to a RAD Form.  The qualified key *must* be something that is persisted
   (not a generated column) and co-exist at the top level of the form given (e.g. you cannot use a nested form key to access a
   top-level form).

   ```
   ro/form-links {:account/name AccountForm}
   ```

   See also `field-formatters`.
   "
  :com.fulcrologic.rad.report/form-links)

(def field-formatters
  "A map from *qualified key* to a `(fn [report-instance value-to-format] string-or-element?)`. The function will
   receive the raw value of the column and should return a string or a UI element *that is acceptable to the current render
   plugin*.

   ```
   ro/field-formatters {:account/name
                         (fn [this v]
                           (dom/a {:onClick #(form/edit! this AccountForm (-> this comp/props :account/id)} (str v)))}
   ```

   Returning an element is particularly useful when the formatting needs something special, like bold, SVG, or some other
   complex format.

   A global default can be specified with `::report/field-formatter` on the attribute, which is just a `fn` (not a map).

   See also `link`, `form-links`, `row-query-inclusion`, and `field-formatter`.
   "
  :com.fulcrologic.rad.report/field-formatters)

(def field-formatter
  "ATTRIBUTE OPTION. A `(fn [report-instance value])` which can be used on an attribute. See `field-formatters`."
  :com.fulcrologic.rad.report/field-formatter)

(def run-on-mount?
  "Boolean. Should this report run when it is first mounted, or wait for the user to explicitly take an action. See
   `controls`. If you set this to false you should make sure the user has some kind of control that allows them to
   run the report."
  :com.fulcrologic.rad.report/run-on-mount?)

(def controls
  "ALIAS to ::control/controls. A map of control definitions, which can be action buttons or inputs.

   Input control values are stored in the report's parameters, and can be used in the filtering/sorting of the
   rows.

   Each control is given a unique key, and a map that describes the control. A typical value of this option will look like:

   ```
   :com.fulcrologic.rad.control/controls {::new-invoice {:label  \"New Invoice\"
                                                         :type   :button
                                                         :action (fn [this] (form/create! this InvoiceForm))}
                                          :show-inactive? {:type          :boolean
                                                           :style         :toggle
                                                           :default-value false
                                                           :onChange      (fn [this _] (report/reload! this))
                                                           :label         \"Show Inactive Accounts?\"}}
                                          ::new-account {:label  \"New Account\"
                                                         :type   :button
                                                         :action (fn [this] (form/create! this AccountForm))}}
   ```

   The types of controls supported will depend on your UI plugin.

   See also `::report/control-layout`, `row-visible?`, `initial-sort-params`, and `compare-rows`.
   "
  :com.fulcrologic.rad.control/controls)

(def control-layout
  "Reports can have actions and input controls. These are normally laid out by simply throwing all of the buttons
   in a sequence, and throwing all of the non-buttons in a form. No layout in particular is guaranteed by RAD
   (though you rendering plugin may provide one).

   This option is a HINT to the rendering plugin as to how you'd *like* the controls to be placed on the screen. The
   content of this option is a map should generally look like:

   ```
   {:action-buttons [::a ::b ::c]
    :inputs [[::d]
             [::e ::f]]
   ```

   Where the keywords are the control keys that you wish to place in those respective positions in the UI. See
   `::control/controls`.
   "
  :com.fulcrologic.rad.report/control-layout)

(def route
  "A string that will be used as this reports path element in the routing tree. Must be unique among siblings."
  :com.fulcrologic.rad.report/route)

(def column-headings
  "A map from *qualified keyword* to a column heading, which can be a simple string or a `(fn [report-instance] string?)`"
  :com.fulcrologic.rad.report/column-headings)

(def row-query-inclusion
  "An EQL query (vector) that will be added to the query of the rows IF you have the report system generate the row
   component.

   Note that reports will always include the `row-pk` in the query, and if a mix of columns are included they
   will also include the PK (if possible) of the source of the columns in question.  You can use `row-query-inclusion`
   and custom server-side resolvers to make any imaginable data available on a row, which can be useful in things
   like `field-formatters` and `row-actions`.
   "
  :com.fulcrologic.rad.report/row-query-inclusion)

(def row-actions
  "A vector of actions that will appear on each row of the report (if supported by rendering plugin).

   An action is a map with keys:

   * `:type`: OPTIONAL. Defaults to :button. Rendering plugins define what types are supported.
   * `:action`: REQUIRED. A side-effecting `(fn [report-instance row-props])`.
   * `:label` : REQUIRED. A string or `(fn [report-instance row-props] string-or-element?)`.
   * `:disabled?` : OPTIONAL. A boolean or `(fn [report-instance row-props] boolean?)`.
   * `:visible?` : OPTIONAL. A boolean or `(fn [report-instance row-props] boolean?)`.

   Rendering plugins can expand this list of options as desired.

   See also `row-query-inclusion`, `form-links`, and `link`."
  :com.fulcrologic.rad.report/row-actions)

(def ^:deprecated link
  "See `links`."
  :com.fulcrologic.rad.report/links)

(def links
  "A map from *qualified key* to a side-effecting `(fn [report-instance row-props]). Wraps the column value
  from field-formatters.

   See also `form-links`, `row-actions`, and `field-formatters`."
  :com.fulcrologic.rad.report/links)

(def denormalize?
  "Boolean. Defaults to false.

   When set to true the auto-generated row component will not be given an ident, causing the rows of the
   report to not be normalized in state. This can be a performance improvement for reports with lots of rows since
   rendering will not have to denormalize them on render, and should be set to true for reports that contain
   derived data.

   Reports that show real entities should probably set this to false, since denormalized values will not update when
   edited on the normalized entities (in forms)."
  :com.fulcrologic.rad.report/denormalize?)

(def row-visible?
  "A `(fn [report-parameters row-props] boolean?).

   When supplied the report will automatically use this predicate to filter the rows that are visible to the user. This
   function is supplied with the current value of the report parameters (defined by `controls`)

   See `controls`."
  :com.fulcrologic.rad.report/row-visible?)

(def compare-rows
  "A comparison function `(fn [sort-parameters row-a row-b] 0, 1, or -1)`.

   ```
   ro/compare-rows        (fn [{:keys [sort-by ascending?] :or {sort-by    :sales/date
                                                                ascending? true}} row-a row-b]
                            (let [a          (get row-a sort-by)
                                  b          (get row-b sort-by)
                                  fwd-result (compare a b)]
                              (cond-> fwd-result
                                (not ascending?) (-))))
   ```

   The sort parameters are set by `initial-sort-params`, the incoming route parameters, or user `controls`.

   Sorting is always done against the filtered rows.

   See `controls`, `initial-sort-params`, and `row-visible?`."
  :com.fulcrologic.rad.report/compare-rows)

(def initial-sort-params
  "A map that describes the reports initial sort order parameters. NOTE: parameters can be
   overridden by route parameters.

   The map contains:

   * `:sort-by` - The qualified key of the column that is the default sort order. This just marks the column heading.
   * `:sortable-columns` - A set (required) of the columns that should offer sort controls.
   * `:ascending?` - A boolean indicating if the order should be ascending, default is true.

   ```
   ro/initial-sort-params {:sort-by :account/name
                           :sortable-columns #{:account/name :account/email}
                           :ascending? true} ; ascending
   ```
   "
  :com.fulcrologic.rad.report/initial-sort-params)

(def paginate?
  "Turn on pagination IF the rendering layout from your UI plugin supports it."
  :com.fulcrologic.rad.report/paginate?)

(def page-size
  "The number of results per page, if your rendering plugin supports pagination and it is turned on."
  :com.fulcrologic.rad.report/page-size)

(def column-heading
  "ATTRIBUTE OPTION.  A string or `(fn [report-instance] string-or-element?)`.

  Specify the default column heading for an attribute. Overridden by `column-headings`.

  Rendering plugins may or may not allow non-string return values from the function version."
  :com.fulcrologic.rad.report/column-heading)
