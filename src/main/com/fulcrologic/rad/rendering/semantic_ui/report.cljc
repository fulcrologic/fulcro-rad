(ns com.fulcrologic.rad.rendering.semantic-ui.report
  (:require
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.data-fetch :as df]
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.local-date :as ld]
    [cljc.java-time.local-time :as lt]
    [cljc.java-time.zoned-date-time :as zdt]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [cljc.java-time.format.date-time-formatter :as dtf]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.type-support.decimal :as math]
    [com.fulcrologic.rad.form :as form]))

(defn inst->human-readable-date
  "Converts a UTC Instant into the correctly-offset and human-readable (e.g. America/Los_Angeles) date string."
  ([inst]
   #?(:cljs
      (when (inst? inst)
        (.toLocaleDateString ^js inst js/undefined #js {:weekday "short" :year "numeric" :month "short" :day "numeric"})))))

(defn format-column [v]
  (cond
    (string? v) v
    (inst? v) (str (inst->human-readable-date v))
    (math/numeric? v) (math/numeric->currency-str v)
    :else (str v)))

(comp/defsc TableRowLayout [this {:keys [row-class report-instance props]}]
  {}
  (let [{::report/keys [columns edit-form field-formatters]} (comp/component-options row-class)
        {report-field-formatters ::report/field-formatters
         report-edit-form        ::report/edit-form
         ::report/keys           [row-pk]} (comp/component-options report-instance)]
    (let [edit-form (or report-edit-form edit-form)
          id-key    (::attr/qualified-key row-pk)]
      (dom/tr {}
        (map-indexed
          (fn [idx {::report/keys [field-formatter]
                    ::attr/keys   [qualified-key]}]
            (dom/td {:key (str "col-" idx)}
              (let [value           (get props qualified-key)
                    formatted-value (or
                                      (?! (get field-formatters qualified-key) value)
                                      (?! (get report-field-formatters qualified-key) value)
                                      (?! field-formatter value)
                                      (format-column value))
                    label           (or formatted-value (str value))]
                (if (and edit-form (= 0 idx))
                  (dom/a {:onClick (fn [] (form/edit! report-instance edit-form (get props id-key)))} label)
                  label))))
          columns)))))

(let [ui-table-row-layout (comp/factory TableRowLayout {:keyfn ::report/idx})]
  (defn render-table-row [report-instance row-class row-props]
    (ui-table-row-layout {:report-instance report-instance
                          :row-class       row-class
                          :props           row-props})))

(comp/defsc ListRowLayout [this {:keys [row-class report-instance props]}]
  {}
  (let [{::attr/keys   [key->attribute]
         ::report/keys [columns edit-form field-formatters]} (comp/component-options row-class)
        {report-field-formatters ::report/field-formatters
         report-edit-form        ::report/edit-form
         ::report/keys           [row-pk]} (comp/component-options report-instance)]
    (log/spy :info props)
    (let [edit-form (or report-edit-form edit-form)
          id-key    (::attr/qualified-key row-pk)]
      (dom/div :.item
        (dom/div :.content
          (map-indexed
            (fn [idx {::report/keys [field-formatter]
                      ::attr/keys   [qualified-key]}]
              (dom/div {:key (str "col-" idx)}
                (let [{::report/keys [field-formatter]} (?! key->attribute qualified-key)
                      value           (get props qualified-key)
                      formatted-value (or
                                        (?! (get field-formatters qualified-key) value)
                                        (?! (get report-field-formatters qualified-key) value)
                                        (?! field-formatter value)
                                        (format-column value))
                      label           (or formatted-value (str value))]
                  (if (and edit-form (= 0 idx))
                    (dom/a :.header {:onClick (fn [] (form/edit! report-instance edit-form (get props id-key)))} label)
                    (dom/div label)))))
            columns))))))

(let [ui-list-row-layout (comp/factory ListRowLayout {:keyfn ::report/idx})]
  (defn render-list-row [report-instance row-class row-props]
    (ui-list-row-layout {:report-instance report-instance
                         :row-class       row-class
                         :props           row-props})))


(comp/defsc StandardReportControls [this {:keys [report-instance]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [props    (comp/props report-instance)
        {::report/keys [parameters run-on-mount? create-form]} (comp/component-options report-instance)
        loading? (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))]
    (dom/div :.ui.top.attached.segment
      (dom/h3 :.ui.header
        (or (some-> report-instance comp/component-options ::report/title) "Report")
        (when create-form
          (dom/button :.ui.tiny.right.floated.primary.button
            {:onClick (fn [] (form/create! report-instance create-form))} "New"))
        (dom/button :.ui.tiny.right.floated.primary.button
          {:classes [(when loading? "loading")]
           :onClick (fn [] (report/run-report! report-instance))} (if run-on-mount? "Refresh" "Run")))
      (dom/div :.ui.form
        (map-indexed
          (fn [idx k]
            (report/render-parameter-input report-instance k))
          (keys parameters))))))

(let [ui-standard-report-controls (comp/factory StandardReportControls)]
  (defn render-standard-controls [report-instance]
    (ui-standard-report-controls {:report-instance report-instance})))

(comp/defsc ListReportLayout [this {:keys [report-instance]}]
  {:initLocalState (fn [this] {:row-factory (memoize (fn [cls] (comp/computed-factory cls {:keyfn
                                                                                           (fn [props] (some-> props (comp/get-computed ::report/idx)))})))})}
  (let [props           (comp/props report-instance)
        {::report/keys [source-attribute BodyItem]} (comp/component-options report-instance)
        render-row      ((comp/get-state this :row-factory) BodyItem)
        render-controls (report/control-renderer this)
        rows            (get props source-attribute [])
        loading?        (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))]
    (dom/div
      (when render-controls
        (render-controls report-instance))
      (dom/div :.ui.attached.segment
        (when (seq rows)
          (dom/div :.ui.relaxed.divided.list
            (map-indexed (fn [idx row] (render-row row {:report-instance report-instance
                                                        :row-class       BodyItem
                                                        ::report/idx     idx})) rows)))))))

(let [ui-list-report-layout (comp/factory ListReportLayout {:keyfn ::report/idx})]
  (defn render-list-report-layout [report-instance]
    (ui-list-report-layout {:report-instance report-instance})))

(comp/defsc TableReportLayout [this {:keys [report-instance]}]
  {:initLocalState        (fn [this] {:row-factory (memoize (fn [cls] (comp/computed-factory cls
                                                                        {:keyfn (fn [props]
                                                                                  (some-> props (comp/get-computed ::report/idx)))})))})
   :shouldComponentUpdate (fn [_ _ _] true)}
  (let [props           (comp/props report-instance)
        {report-column-headings ::report/column-headings
         ::report/keys          [columns source-attribute BodyItem]} (comp/component-options report-instance)
        {item-column-headings ::report/column-headings} (comp/component-options BodyItem)
        render-row      ((comp/get-state this :row-factory) BodyItem)
        column-headings (mapv (fn [{::report/keys [column-heading]
                                    ::attr/keys   [qualified-key] :as attr}]
                                (or
                                  (?! (get report-column-headings qualified-key))
                                  (?! (get item-column-headings qualified-key))
                                  (?! column-heading)
                                  (some-> qualified-key name str/capitalize)
                                  ""))
                          columns)
        render-controls (report/control-renderer report-instance)
        rows            (get props source-attribute [])
        loading?        (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))]
    (dom/div
      (when render-controls
        (render-controls report-instance))
      (dom/div :.ui.attached.segment
        (dom/div :.ui.loader {:classes [(when loading? "active")]})
        (dom/table :.ui.table
          (dom/thead
            (dom/tr (map-indexed (fn [idx h] (dom/th {:key idx} (str h))) column-headings)))
          (when (seq rows)
            (dom/tbody
              (map-indexed
                (fn [idx row]
                  (render-row row {:report-instance report-instance
                                   :row-class       BodyItem
                                   ::report/idx     idx}))
                rows))))))))

(let [ui-table-report-layout (comp/factory TableReportLayout {:keyfn ::report/idx})]
  (defn render-table-report-layout [this]
    (ui-table-report-layout {:report-instance this})))


