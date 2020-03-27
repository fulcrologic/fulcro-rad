(ns com.fulcrologic.rad.rendering.semantic-ui.report
  (:require
    [clojure.string :as str]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div]])
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.form :as form]))

(defn row-action-buttons [report-instance row-props]
  (let [{::report/keys [row-actions]} (comp/component-options report-instance)]
    (when (seq row-actions)
      (div :.ui.buttons
        (map-indexed
          (fn [idx {:keys [label reload? visible? disabled? action]}]
            (when (or (nil? visible?) (?! visible? report-instance row-props))
              (dom/button :.ui.button
                {:key      idx
                 :disabled (boolean (?! disabled? report-instance row-props))
                 :onClick  (fn [] (when action
                                    (action report-instance row-props)
                                    (when reload?
                                      (report/reload! report-instance))))}
                (?! label report-instance row-props))))
          row-actions)))))

(comp/defsc TableRowLayout [_ {:keys [report-instance props]}]
  {}
  (let [{::report/keys [columns]} (comp/component-options report-instance)
        action-buttons (row-action-buttons report-instance props)]
    (dom/tr {}
      (map
        (fn [{::report/keys [field-formatter]
              ::attr/keys   [qualified-key] :as column}]
          (dom/td {:key (str "col-" qualified-key)}
            (let [{:keys [edit-form entity-id]} (report/form-link report-instance props qualified-key)
                  label (report/formatted-column-value report-instance props column)]
              (if edit-form
                (dom/a {:onClick (fn [] (form/edit! report-instance edit-form entity-id))} label)
                label))))
        columns)
      (when action-buttons
        (dom/td :.collapsing {:key "actions"}
          action-buttons)))))

(let [ui-table-row-layout (comp/factory TableRowLayout {:keyfn ::report/idx})]
  (defn render-table-row [report-instance row-class row-props]
    (ui-table-row-layout {:report-instance report-instance
                          :row-class       row-class
                          :props           row-props})))

(comp/defsc ListRowLayout [this {:keys [report-instance props]}]
  {}
  (let [{::report/keys [columns]} (comp/component-options report-instance)]
    (let [header-column      (first columns)
          description-column (second columns)
          {:keys [edit-form entity-id]} (some->> header-column (::attr/qualified-key) (report/form-link report-instance props))
          header-label       (some->> header-column (report/formatted-column-value report-instance props))
          description-label  (some->> description-column (report/formatted-column-value report-instance props))
          action-buttons     (row-action-buttons report-instance props)]
      (div :.item
        (div :.content
          (when action-buttons
            (div :.right.floated.content
              action-buttons))
          (when header-label
            (if edit-form
              (dom/a :.header {:onClick (fn [] (form/edit! report-instance edit-form entity-id))} header-label)
              (div :.header header-label)))
          (when description-label
            (div :.description description-label)))))))

(let [ui-list-row-layout (comp/factory ListRowLayout {:keyfn ::report/idx})]
  (defn render-list-row [report-instance row-class row-props]
    (ui-list-row-layout {:report-instance report-instance
                         :row-class       row-class
                         :props           row-props})))


(comp/defsc StandardReportControls [this {:keys [report-instance]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [props    (comp/props report-instance)
        {::report/keys [parameters run-on-mount? actions]} (comp/component-options report-instance)
        loading? (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))]
    (div :.ui.top.attached.segment
      (dom/h3 :.ui.header
        (or (some-> report-instance comp/component-options ::report/title) "Report")
        (div :.ui.right.floated.buttons
          (keep
            (fn [{:keys [label action disabled? visible?]}]
              (let [label     (or (?! label report-instance) "Missing Label")
                    disabled? (?! disabled? report-instance)
                    visible?  (or (nil? visible?) (?! visible? report-instance))]
                (when visible?
                  (dom/button :.ui.tiny.primary.button
                    {:key      (str label)
                     :disabled (boolean disabled?)
                     :onClick  (fn [] (when action (action report-instance)))} label))))
            actions)
          (dom/button :.ui.tiny.primary.button
            {:classes [(when loading? "loading")]
             :onClick (fn [] (report/run-report! report-instance))} (if run-on-mount? "Refresh" "Run"))))
      (div :.ui.form
        (map-indexed
          (fn [idx k]
            (report/render-parameter-input report-instance k))
          (keys parameters))))))

(let [ui-standard-report-controls (comp/factory StandardReportControls)]
  (defn render-standard-controls [report-instance]
    (ui-standard-report-controls {:report-instance report-instance})))

(comp/defsc ListReportLayout [this {:keys [report-instance]}]
  {:shouldComponentUpdate (fn [_ _ _] true)
   :initLocalState        (fn [_] {:row-factory (memoize
                                                  (fn [cls]
                                                    (comp/computed-factory cls
                                                      {:keyfn (fn [props] (some-> props (comp/get-computed ::report/idx)))})))})}
  (let [props           (comp/props report-instance)
        {::report/keys [source-attribute BodyItem]} (comp/component-options report-instance)
        render-row      ((comp/get-state this :row-factory) BodyItem)
        render-controls (report/control-renderer this)
        rows            (get props source-attribute [])
        loading?        (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))]
    (div
      (when render-controls
        (render-controls report-instance))
      (div :.ui.attached.segment
        (when (seq rows)
          (div :.ui.relaxed.divided.list
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
  (let [props            (comp/props report-instance)
        {report-column-headings ::report/column-headings
         ::report/keys          [columns source-attribute row-actions BodyItem]} (comp/component-options report-instance)
        render-row       ((comp/get-state this :row-factory) BodyItem)
        column-headings  (mapv (fn [{::report/keys [column-heading]
                                     ::attr/keys   [qualified-key] :as attr}]
                                 (or
                                   (?! (get report-column-headings qualified-key))
                                   (?! column-heading)
                                   (some-> qualified-key name str/capitalize)
                                   ""))
                           columns)
        render-controls  (report/control-renderer report-instance)
        rows             (get props source-attribute [])
        has-row-actions? (seq row-actions)
        loading?         (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))]
    (div
      (when render-controls
        (render-controls report-instance))
      (div :.ui.attached.segment
        (div :.ui.loader {:classes [(when loading? "active")]})
        (dom/table :.ui.table
          (dom/thead
            (dom/tr
              (map-indexed (fn [idx h] (dom/th {:key idx} (str h))) column-headings)
              (when has-row-actions? (dom/th :.collapsing ""))))
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


