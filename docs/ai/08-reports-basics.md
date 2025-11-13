# Reports Basics

## Overview

RAD reports query and display list-based data with parameters for filtering and sorting. Reports use the graph API (
Pathom) as the data source and integrate with RAD's rendering system for tabular or custom layouts. The `defsc-report`
macro generates Fulcro components with auto-generated queries, parameter controls, and data loading.

## Core Concept

From DevelopersGuide.adoc:3014-3015:
> "RAD Reports are based on the generalization that many reports are a query across data that is list-based, and most
> reports have parameters."

**Key Components**:

- **Data source**: EQL query via `ro/source-attribute`
- **Row rendering**: Either auto-rendered from `ro/columns` or custom via `ro/BodyItem` (legacy)
- **Parameters**: User controls for filtering/sorting
- **Rendering**: Pluggable layouts (table, list, grid, custom)

## The defsc-report Macro

### Basic Example

From DevelopersGuide.adoc:3017-3039:

```clojure
(ns com.example.ui.employee-list
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.rad.report :as report :refer [defsc-report]]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.form :as form]
    [com.example.ui.employee-form :as ef]
    [com.example.model.employee :as employee]))

;; Legacy approach: Custom BodyItem component
(defsc EmployeeListItem [this {:employee/keys [id first-name last-name enabled?] :as props}]
  {:query [:employee/id :employee/first-name :employee/last-name :employee/enabled?]
   :ident :employee/id}
  (dom/div :.item {:onClick #(form/edit! this ef/EmployeeForm id)}
    (dom/div :.content
      (dom/span (str first-name " " last-name (when-not enabled? " (disabled)"))))))

(defsc-report EmployeeList [this props]
  {ro/BodyItem          EmployeeListItem
   ro/create-form       ef/EmployeeForm
   ro/layout-style      :default
   ro/source-attribute  :employee/all-employees
   ro/parameters        {:include-disabled? {:type  :boolean
                                              :label "Show Past Employees?"}}
   ro/initial-parameters (fn [report-env] {:include-disabled? false})
   ro/run-on-mount?     true
   :route-segment       ["employee-list"]})
```

### Modern Approach (v1.6+)

From report-options.cljc:6-10:

The modern API uses `ro/row-pk` and `ro/columns` for auto-rendering:

```clojure
(defsc-report EmployeeList [this props]
  {ro/row-pk            employee/id
   ro/columns           [employee/first-name employee/last-name employee/email employee/enabled?]
   ro/source-attribute  :employee/all-employees
   ro/parameters        {:include-disabled? {:type  :boolean
                                              :label "Show Past Employees?"}}
   ro/initial-parameters (fn [report-env] {:include-disabled? false})
   ro/run-on-mount?     true
   :route-segment       ["employee-list"]})
```

## Required Options

From report-options.cljc:6-10:

Reports require at minimum:

- `row-pk` - Attribute serving as primary key for rows
- `columns` - Vector of attributes to display
- `source-attribute` - EQL top-level key for the query

### ro/row-pk

From report-options.cljc:18-22:
> "An *attribute* that will serve as each row's primary key. May be a virtual attribute that is not actually stored in
> the database, but the attribute must have a resolver that will return a stable value. Reports that generate aggregated
> rows, for example, might use a combination of things to generate this value server-side."

```clojure
{ro/row-pk employee/id}  ; Pass the attribute, not the keyword
```

### ro/columns

From report-options.cljc:24-30:
> "A vector of *attributes* that describe the columns of the report (when tabular). In non-tabular reports this is still
> needed, but the renderer can choose to use the per-row data however it wants (points on a graph, etc).
>
> The columns are treated as the authoritative definition of the attributes, meaning that you can assoc things like
`ao/style` on a column to override something like style."

```clojure
{ro/columns [employee/first-name
             employee/last-name
             employee/email
             employee/hire-date]}
```

**Columns as Overrides**: You can override attribute options inline:

```clojure
{ro/columns [employee/first-name
             (assoc employee/salary ao/style :currency)]}
```

### ro/source-attribute

From report-options.cljc:96-99 and DevelopersGuide.adoc:3051-3053:
> "A *qualified keyword* that will be used as the entry-point key for the query. The data source server must have a
> resolver that can start queries at that keyword (global resolver)."

```clojure
{ro/source-attribute :employee/all-employees}
```

Combined with the columns' attributes, this generates an EQL query like:

```clojure
[{:employee/all-employees [:employee/id :employee/first-name :employee/last-name ...]}]
```

## Report Parameters

From DevelopersGuide.adoc:3054-3058:

Parameters are user controls that filter or modify the query.

### ro/parameters (Legacy)

From DevelopersGuide.adoc:3034-3035:

```clojure
{ro/parameters {:include-disabled? {:type  :boolean
                                     :label "Show Past Employees?"}
                :search-term        {:type  :string
                                     :label "Search"}
                :department         {:type  :ref
                                     :label "Department"}}}
```

**Parameter Types**:

- `:boolean` - Checkbox
- `:string` - Text input
- `:int`, `:decimal` - Number input
- `:instant` - Date/time picker
- `:ref` - Dropdown/picker (requires options)

### ro/controls (Modern v1.6+)

From report-options.cljc:156-163:

The modern approach uses `ro/controls` (alias to `::control/controls`):

> "A map of control definitions, which can be action buttons or inputs. Input control values are stored in the report's
> parameters, and can be used in the filtering/sorting of the rows."

See the controls namespace for full details.

### ro/initial-parameters

From DevelopersGuide.adoc:3056-3058:

```clojure
{ro/initial-parameters {:include-disabled? false
                        :search-term       ""}}

;; Or as a function
{ro/initial-parameters (fn [report-env] {:include-disabled? false})}
```

Initial values for report parameters.

### ro/run-on-mount?

From report-options.cljc:150-154 and DevelopersGuide.adoc:3058-3059:
> "Boolean. Should this report run when it is first mounted, or wait for the user to explicitly take an action. See
`controls`. If you set this to false you should make sure the user has some kind of control that allows them to run the
> report."

```clojure
{ro/run-on-mount? true}   ; Auto-load when report appears
{ro/run-on-mount? false}  ; User must click "Run Report" button
```

## Server-Side Report Query Resolution

From DevelopersGuide.adoc:3061-3096:

### Pathom Plugin for Query Params

Reports send parameters as EQL query params. Use the `query-params-to-env-plugin` to move them into the parser `env`:

From DevelopersGuide.adoc:3069-3082:

```clojure
(ns com.example.server.parser
  (:require
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.pathom :as rad-pathom]))

(def parser
  (p/parser
    {::p/mutate  pc/mutate
     ::p/env     {::p/reader               [p/map-reader pc/reader2 pc/index-reader
                                             pc/open-ident-reader p/env-placeholder-reader]
                  ::p/placeholder-prefixes #{">"}}
     ::p/plugins [; ... other plugins ...
                  rad-pathom/query-params-to-env-plugin
                  ; ... other plugins ...
                  ]}))
```

### Writing the Resolver

From DevelopersGuide.adoc:3085-3096:

```clojure
(ns com.example.server.resolvers
  (:require
    [com.wsscode.pathom.connect :as pc :refer [defresolver]]))

(defresolver all-employees [{:keys [db query-params] :as env} input]
  {::pc/output [{:employee/all-employees [:employee/id]}]}
  (let [employees      (get-all-employees db)
        employees      (if (:include-disabled? query-params)
                         employees
                         (filterv :employee/enabled? employees))
        filtered       (if-let [term (:search-term query-params)]
                         (filter-by-name term employees)
                         employees)]
    {:employee/all-employees filtered}))
```

**Key Points**:

- Access parameters via `:query-params` in env
- Return map with `source-attribute` as key
- Output spec must include at least the row primary key

### Resolving Row Attributes

From DevelopersGuide.adoc:3098-3107:

Pathom auto-connects resolvers. If your report displays `:employee/hours-this-period`, just add a resolver:

```clojure
(defresolver pay-period-hours-resolver [env {:employee/keys [id]}]
  {::pc/input  #{:employee/id}
   ::pc/output [:employee/hours-this-period]}
  {:employee/hours-this-period (calculate-hours id)})
```

Pathom will automatically batch/resolve these for all rows.

## Row Actions and Navigation

### Clicking Rows to Edit

From DevelopersGuide.adoc:3024-3025 (in BodyItem example):

```clojure
(dom/div {:onClick #(form/edit! this EmployeeForm [:employee/id id])}
  ...)
```

Use `form/edit!` to navigate to a form for editing.

### Create Button

From DevelopersGuide.adoc:3047-3049:

```clojure
{ro/create-form EmployeeForm}
```

> "A Form that should be used to create new instances of items in the report. Optional. If supplied then the toolbar of
> the report will have an add button."

## Filtering and Sorting

### Client-Side Row Filtering

From report-options.cljc:294-301:

```clojure
{ro/row-visible? (fn [report-parameters row-props]
                   (let [{:keys [include-disabled?]} report-parameters]
                     (or include-disabled?
                         (:employee/enabled? row-props))))}
```

**`ro/row-visible?` Signature**: `(fn [report-parameters row-props] boolean?)`

When supplied, the report automatically filters rows using this predicate.

**Note**: Ignored by server-paginated reports (filtering happens server-side).

### Client-Side Row Sorting

From report-options.cljc:305-309:

```clojure
{ro/compare-rows (fn [{:keys [sort-by ascending?] :or {sort-by    :employee/last-name
                                                        ascending? true}}
                      row-a row-b]
                   (let [a (get row-a sort-by)
                         b (get row-b sort-by)]
                     (cond
                       (= a b) 0
                       ascending? (if (< a b) -1 1)
                       :else (if (> a b) -1 1))))}
```

**`ro/compare-rows` Signature**: `(fn [sort-parameters row-a row-b] -1|0|1)`

Standard comparison function for sorting.

## Layout and Rendering

### ro/layout-style

From DevelopersGuide.adoc:3050-3051:
> "An alternate style (plugged into the app) for rendering the report."

```clojure
{ro/layout-style :default}   ; Use default table layout
{ro/layout-style :list}      ; Use list layout
{ro/layout-style :my-custom} ; Use custom layout (must be registered)
```

### Custom Report Layout

From DevelopersGuide.adoc:3120-3141:

```clojure
(ns com.example.ui.custom-layouts
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.report-options :as ro]))

(defn custom-report-layout [this]
  (let [props       (comp/props this)
        {::report/keys [source-attribute BodyItem parameters create-form]}
        (comp/component-options this)
        id-key      (some-> BodyItem (comp/get-ident {}) first)
        row-factory (comp/factory BodyItem {:keyfn id-key})
        rows        (get props source-attribute [])
        loading?    (df/loading? (get-in props [df/marker-table (comp/get-ident this)]))]
    ; ... custom rendering ...
    ))

;; Register layout
(report/install-formatter! app
  (assoc-in base-controls [ro/style->layout :custom-layout] custom-report-layout))

;; Use in report
{ro/layout-style :custom-layout}
```

## Common Patterns

### Pattern 1: Search + Filters

```clojure
(defsc-report ProductList [this props]
  {ro/row-pk            product/id
   ro/columns           [product/name product/price product/category product/in-stock?]
   ro/source-attribute  :product/all-products
   ro/parameters        {:search         {:type  :string
                                           :label "Search Products"}
                         :category       {:type  :ref
                                           :label "Category"}
                         :in-stock-only? {:type  :boolean
                                           :label "In Stock Only"}}
   ro/initial-parameters {:search         ""
                          :in-stock-only? false}
   ro/run-on-mount?     true})
```

### Pattern 2: Report with Actions

```clojure
{ro/row-actions [{:label  "Edit"
                  :action (fn [report-instance row-props]
                            (form/edit! report-instance ProductForm
                              [:product/id (:product/id row-props)]))}
                 {:label  "Delete"
                  :action (fn [report-instance row-props]
                            (when (js/confirm "Delete?")
                              (comp/transact! report-instance
                                [(delete-product {:id (:product/id row-props)})])  ))
                  :visible? (fn [report-instance row-props]
                              (:product/can-delete? row-props))}]}
```

### Pattern 3: Computed Columns

Use a resolver to add computed data:

```clojure
;; Server-side
(defresolver full-name-resolver [env {:employee/keys [first-name last-name]}]
  {::pc/input  #{:employee/first-name :employee/last-name}
   ::pc/output [:employee/full-name]}
  {:employee/full-name (str first-name " " last-name)})

;; Define virtual attribute
(defattr full-name :employee/full-name :string {})

;; Use in report columns
{ro/columns [employee/full-name employee/email employee/hire-date]}
```

### Pattern 4: Conditional Column Styling

```clojure
{ro/column-styles {:product/price (fn [report-instance]
                                     (if (some-condition? report-instance)
                                       :currency-large
                                       :currency))}}
```

## Important Notes

### 1. Reports vs. Forms

**Reports**:

- Read-only (typically)
- Display lists/tables
- Query many entities at once
- Use `defsc-report`

**Forms**:

- Editable
- Display/edit single entity (with nested subforms)
- Use `defsc-form`

### 2. Row Rendering is Manual (for BodyItem)

From DevelopersGuide.adoc:3143-3147:
> "NOTE: At present you must write the row rendering yourself. The design of the recursive pluggable report rendering is
> still in progress."

When using the legacy `ro/BodyItem` approach, you write the row component manually. The modern `ro/columns` approach
auto-renders.

### 3. Query Generation

Reports auto-generate their EQL query:

```clojure
;; From this:
{ro/source-attribute :employee/all-employees
 ro/columns          [employee/id employee/name employee/email]}

;; Generates this query:
[{:employee/all-employees [:employee/id :employee/name :employee/email]}]
```

### 4. Parameters Are Optional

You can have reports without parameters:

```clojure
{ro/source-attribute :employee/all-employees
 ro/row-pk           employee/id
 ro/columns          [employee/name employee/email]
 ro/run-on-mount?    true}
```

### 5. Server-Side vs. Client-Side Filtering

- **Server-side**: Use `:query-params` in resolver to filter data before returning
- **Client-side**: Use `ro/row-visible?` to hide rows after loading

For large datasets, always filter server-side. Client-side filtering is for small result sets or additional UI-only
filtering.

### 6. Route Integration

Reports are normal Fulcro components and work with dynamic routing:

```clojure
{:route-segment ["employees"]
 :will-enter    (fn [app route-params]
                  (dr/route-immediate [:component/id ::EmployeeList]))}
```

## Related Topics

- **Report Rendering** (09-report-rendering.md): Custom formatters, multimethods, performance optimization
- **Attributes and Data Model** (01-attributes-data-model.md): Understanding attributes used in columns
- **Server Setup** (10-server-setup.md): Configuring Pathom parser with RAD plugins
- **Forms Basics** (04-forms-basics.md): Using `form/edit!` for row actions

## Source References

- DevelopersGuide.adoc:3012-3148 (Reports section)
- report-options.cljc:1-100 (Core options: row-pk, columns, source-attribute)
- report-options.cljc:150-154 (run-on-mount?)
- report-options.cljc:156-163 (controls)
- report-options.cljc:294-301 (row-visible?)
- report-options.cljc:305-309 (compare-rows)
- DevelopersGuide.adoc:3069-3082 (Pathom query-params-to-env-plugin)
- DevelopersGuide.adoc:3085-3107 (Writing resolvers)
