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

(def title :com.fulcrologic.rad.report/title)
(def form-links :com.fulcrologic.rad.report/form-links)
(def field-formatters :com.fulcrologic.rad.report/field-formatters)
(def run-on-mount? :com.fulcrologic.rad.report/run-on-mount?)
(def control-layout :com.fulcrologic.rad.report/control-layout)
(def route :com.fulcrologic.rad.report/route)
(def column-headings :com.fulcrologic.rad.report/column-headings)
(def row-query-inclusion :com.fulcrologic.rad.report/row-query-inclusion)
(def row-actions :com.fulcrologic.rad.report/row-actions)
(def link :com.fulcrologic.rad.report/link)
