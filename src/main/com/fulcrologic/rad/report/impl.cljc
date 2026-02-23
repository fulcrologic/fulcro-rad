(ns com.fulcrologic.rad.report.impl
  "Pure implementation functions for RAD reports that have no dependency on the UISM engine.
   Extracted here so that alternative engines (e.g. statecharts) can reuse them without
   duplicating code. See com.fulcrologic.rad.report for the full public API."
  #?(:cljs (:require-macros [com.fulcrologic.rad.report.impl]))
  (:require
    #?@(:clj [[cljs.analyzer :as ana]])
    [clojure.string :as str]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.fulcro.algorithms.lambda :refer [->arity-tolerant]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.form :as-alias form]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.report :as-alias report]
    [com.fulcrologic.rad.report-options :as ro]
    [com.fulcrologic.rad.report-render :as rr]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [taoensso.timbre :as log]))

(defn report-ident
  "Returns the ident of a RAD report. The parameter can be a react instance, a class, or the registry key(word)
   of the report."
  [report-class-or-registry-key]
  (if (keyword? report-class-or-registry-key)
    [::report/id report-class-or-registry-key]
    (comp/get-ident report-class-or-registry-key {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RENDERING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-render-layout [report-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        layout-style (or (some-> report-instance comp/component-options ::report/layout-style) :default)
        layout       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::report/style->layout layout-style)]
    (if layout
      ((->arity-tolerant layout) report-instance)
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

(defn render-layout [report-instance]
  (rr/render-report report-instance (rc/component-options report-instance)))

(defn default-render-row [report-instance row-class row-props]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        layout-style (or (some-> report-instance comp/component-options ::report/row-style) :default)
        render       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::report/row-style->row-layout layout-style)]
    (if render
      ((->arity-tolerant render) report-instance row-class row-props)
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

(defn render-row
  "Render a row of the report. Leverages report-render/render-row, whose default uses whatever UI plugin you have."
  [report-instance row-class row-props]
  (rr/render-row report-instance (rc/component-options report-instance) row-props))

(defn control-renderer
  "Get the report controls renderer for the given report instance. Returns a `(fn [this])`."
  [report-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app report-instance)
        control-style (or (some-> report-instance comp/component-options ::report/control-style) :default)
        control       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::report/control-style->control control-style)]
    (if control
      control
      (do
        (log/error "No layout function found for report control style" control-style)
        nil))))

(defn render-controls
  "Renders just the control section of the report. See also `control-renderer` if you desire rendering the controls in
   more than one place in the UI at once (e.g. top/bottom)."
  [report-instance]
  (rr/render-controls report-instance (rc/component-options report-instance)))

(defn column-heading-descriptors
  "Returns a vector of maps describing what should be shown for column headings. Each
   map may contain:

   :label - The text label
   :help - A string that could be shown as a longer description (e.g. on hover)
   :column - The actual column attribute from the RAD model.
   "
  [report-instance report-options]
  (let [{report-column-headings ::report/column-headings
         report-column-infos    ::report/column-infos} report-options
        columns (ro/columns report-options)]
    (mapv (fn [{::report/keys [column-heading column-info]
                ::attr/keys   [qualified-key] :as attr}]
            {:column attr
             :help   (or
                       (?! (get report-column-infos qualified-key) report-instance)
                       (?! column-info report-instance))
             :label  (or
                       (?! (get report-column-headings qualified-key) report-instance)
                       (?! column-heading report-instance)
                       (?! (ao/label attr) report-instance)
                       (some-> qualified-key name str/capitalize)
                       "")})
      columns)))

(def render-control
  "[report-instance control-key]

   Render a single control, wrapped by minimal chrome. This is just an alias for control/render-control."
  control/render-control)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGIC
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn rotate-result
  "Given a report class that has columns, and a raw result grouped by those columns: returns a vector of rows that
   rotate the grouped result into a normal report shape."
  [report-class grouped-result]
  (when-not (map? grouped-result)
    (log/warn "The incoming result looks like it was normalized. Did you forget `ro/denormalize? true` on your report?"))
  (let [columns  (comp/component-options report-class ::report/columns)
        ks       (map ::attr/qualified-key columns)
        row-data (map (fn [{::attr/keys [qualified-key]}]
                        (get grouped-result qualified-key [])) columns)]
    (apply mapv (fn [& args] (zipmap ks args)) row-data)))

#?(:clj
   (defn req!
     ([env sym options k pred?]
      (when-not (and (contains? options k) (pred? (get options k)))
        (throw (ana/error env (str "defsc-report " sym " is missing or invalid option " k)))))
     ([env sym options k]
      (when-not (contains? options k)
        (throw (ana/error env (str "defsc-report " sym " is missing option " k)))))))

(defn default-compare-rows
  "Default row comparison function used for sorting. Takes a `sort-params` map (with `:sort-by` and `:ascending?` keys)
   and two row maps `a` and `b`; returns a negative, zero, or positive integer per `compare`."
  [{:keys [sort-by ascending?]} a b]
  (try
    (let [av (get a sort-by)
          bv (get b sort-by)]
      (if ascending?
        (compare av bv)
        (compare bv av)))
    (catch #?(:clj Exception :cljs :default) _
      0)))

(defn form-link
  "Get the form link info for a given (column) key.

  Returns nil if there is no link info, otherwise returns:

  ```
  {:edit-form FormClass
   :entity-id id-of-entity-to-edit}
  ```
  "
  [report-instance row-props column-key]
  (let [{::report/keys [form-links]} (comp/component-options report-instance)
        cls    (get form-links column-key)
        id-key (some-> cls (comp/component-options ::form/id ::attr/qualified-key))]
    (when cls
      {:edit-form cls
       :entity-id (get row-props id-key)})))

(defn link
  "Get a regular lambda link for a given (column) key.

  Returns nil if there is no link info, otherwise returns:

  ```
  {:edit-form FormClass
   :entity-id id-of-entity-to-edit}
  ```
  "
  [report-instance row-props column-key]
  (let [{::report/keys [form-links]} (comp/component-options report-instance)
        cls    (get form-links column-key)
        id-key (some-> cls (comp/component-options ::form/id ::attr/qualified-key))]
    (when cls
      {:edit-form cls
       :entity-id (get row-props id-key)})))

(defn built-in-formatter
  "Returns a formatter function for the given `type` (e.g. `:string`, `:instant`, `:decimal`) and
   `style` keyword (e.g. `:default`, `:currency`). Returns nil if no built-in formatter exists."
  [type style]
  (get-in
    {:string  {:default (fn [_ value] value)}
     :instant {:default         (fn [_ value] (dt/inst->human-readable-date value))
               :short-timestamp (fn [_ value] (dt/tformat "MMM d, h:mma" value))
               :timestamp       (fn [_ value] (dt/tformat "MMM d, yyyy h:mma" value))
               :date            (fn [_ value] (dt/tformat "MMM d, yyyy" value))
               :month-day       (fn [_ value] (dt/tformat "MMM d" value))
               :time            (fn [_ value] (dt/tformat "h:mma" value))}
     :keyword {:default (fn [_ value _ column-attribute]
                          (if-let [labels (::attr/enumerated-labels column-attribute)]
                            (labels value)
                            (some-> value (name) str/capitalize)))}
     :enum    {:default (fn [_ value _ column-attribute]
                          (if-let [labels (::attr/enumerated-labels column-attribute)]
                            (labels value) (str value)))}
     :int     {:default (fn [_ value] (str value))}
     :decimal {:default    (fn [_ value] (math/numeric->str value))
               :currency   (fn [_ value] (math/numeric->str (math/round value 2)))
               :percentage (fn [_ value] (math/numeric->percent-str value))
               :USD        (fn [_ value] (math/numeric->currency-str value))}
     :boolean {:default (fn [_ value] (if value (tr "true") (tr "false")))}}
    [type style]))

(defn formatted-column-value
  "Given a report instance, a row of props, and a column attribute for that report:
   returns the formatted value of that column using the field formatter(s) defined
   on the column attribute or report. If no formatter is provided a default formatter
   will be used."
  [report-instance row-props {::report/keys [field-formatter column-formatter]
                              ::attr/keys   [qualified-key type style] :as column-attribute}]
  (let [value                  (get row-props qualified-key)
        report-field-formatter (or
                                 (comp/component-options report-instance ::report/column-formatters qualified-key)
                                 (comp/component-options report-instance ::report/field-formatters qualified-key))
        {::app/keys [runtime-atom]} (comp/any->app report-instance)
        formatter              (cond
                                 report-field-formatter report-field-formatter
                                 column-formatter column-formatter
                                 field-formatter field-formatter
                                 :else (let [style                (or
                                                                    (comp/component-options report-instance ::report/column-styles qualified-key)
                                                                    style
                                                                    :default)
                                             installed-formatters (some-> runtime-atom deref :com.fulcrologic.rad/controls ::report/type->style->formatter)
                                             formatter            (get-in installed-formatters [type style])]
                                         (or
                                           formatter
                                           (built-in-formatter type style)
                                           (fn [_ v _ _] (str v)))))
        formatted-value        ((->arity-tolerant formatter) report-instance value row-props column-attribute)]
    formatted-value))

(defn install-formatter!
  "Install a formatter for the given data type and style. The data type must match a supported data type
   of attributes, and the style can either be `:default` or a user-defined keyword the represents the
   style you want to support. Some common styles have predefined support, such as `:USD` for US Dollars.

   This should be called before mounting your app.

   Ex.:

   ```clojure
   (install-formatter! app :boolean :default (fn [report-instance value] (if value \"yes\" \"no\")))
   ```"
  [app type style formatter]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls ::report/type->style->formatter type style] formatter)))

(defn install-layout!
  "Install a report layout renderer for the given `report-style`. `render` is a `(fn [report-instance])`.

  See other support functions in this ns for help rendering, such as `formatted-column-value`, `form-link`.

   This should be called before mounting your app."
  [app report-style render]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls ::report/style->layout report-style] render)))

(defn install-row-layout!
  "Install a row layout renderer for the given `row-style`. `render-row-fn` is a `(fn [report-instance row-class row-props])`.

  See other support functions in this ns for help rendering, such as `formatted-column-value`, `form-link`.

   This should be called before mounting your app."
  [app row-style render-row-fn]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc-in [:com.fulcrologic.rad/controls ::report/row-style->row-layout row-style] render-row-fn)))

(defn current-rows
  "Get a vector of the current rows that should be shown by the renderer (sorted/paginated/filtered). `report-instance`
   is available in the rendering `env`."
  [report-instance]
  (let [props (comp/props report-instance)]
    (get props :ui/current-rows [])))

(defn loading?
  "Returns true if the given report instance has an active network load in progress."
  [report-instance]
  (when report-instance
    (df/loading? (get-in (comp/props report-instance) [df/marker-table (comp/get-ident report-instance)]))))

(defn current-page
  "Returns the current page number displayed on the report."
  ([report-instance]
   (get-in (comp/props report-instance) [:ui/parameters ::report/current-page] 1))
  ([state-map report-class-or-registry-key]
   (get-in state-map (conj (report-ident report-class-or-registry-key) :ui/parameters ::report/current-page) 1)))

(defn page-count
  "Returns how many pages the current report has."
  ([report-instance]
   (get-in (comp/props report-instance) [:ui/page-count] 1))
  ([state-map report-class-or-registry-key]
   (get-in state-map (conj (report-ident report-class-or-registry-key) :ui/page-count) 1)))

(defn currently-selected-row
  "Returns the currently-selected row index, if any (-1 if nothing is selected)."
  ([report-instance]
   (get-in (comp/props report-instance) [:ui/parameters ::report/selected-row] -1))
  ([state-map report-class-or-registry-key]
   (get-in state-map (conj (report-ident report-class-or-registry-key) :ui/parameters ::report/selected-row) -1)))

(defn column-classes
  "Returns a string of column classes that can be defined on the attribute at `::report/column-class` or on the
   report in the `::report/column-classes` map. The report-level map entry overrides the attribute."
  [report-instance-or-class {::report/keys [column-class]
                             ::attr/keys   [qualified-key] :as attr}]
  (let [rpt-column-class (comp/component-options report-instance-or-class ::report/column-classes qualified-key)]
    (or rpt-column-class column-class)))

(defn genrow
  "Generates a row class for reports. Mainly meant for internal use, but might be useful in custom report generation code.

  `registry-key` - The unique keyword to register the generated class under.
  `options` - The top-level report options map."
  [registry-key options]
  (let [{::report/keys [columns row-pk form-links
                        row-query-inclusion denormalize? row-actions]} options
        normalize?   (not denormalize?)
        row-query    (let [id-attrs (keep #(comp/component-options % ::form/id) (vals form-links))]
                       (vec
                         (into (set row-query-inclusion)
                           (map (fn [attr] (or
                                             (::report/column-EQL attr)
                                             (::attr/qualified-key attr))) (conj (set (concat id-attrs columns)) row-pk)))))
        row-key      (::attr/qualified-key row-pk)
        row-ident    (fn [this props] [row-key (get props row-key)])
        row-actions  (or row-actions [])
        row-render   (fn [this]
                       (comp/wrapped-render this
                         (fn []
                           (let [props (comp/props this)]
                             (render-row this (rc/registry-key->class registry-key) props)))))
        body-options (cond-> {:query               (fn [this] row-query)
                              ::report/row-actions row-actions
                              ::report/columns     columns}
                       normalize? (assoc :ident row-ident)
                       form-links (assoc ::report/form-links form-links))]
    (comp/sc registry-key body-options row-render)))
