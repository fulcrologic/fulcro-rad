(ns com.fulcrologic.rad.rendering.semantic-ui.report
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div]]
         [com.fulcrologic.semantic-ui.modules.popup.ui-popup :refer [ui-popup]]
         [com.fulcrologic.semantic-ui.modules.popup.ui-popup-content :refer [ui-popup-content]]
         [com.fulcrologic.semantic-ui.addons.pagination.ui-pagination :as sui-pagination]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div]]])
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr trc]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.rendering.semantic-ui.form :as sui-form]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.semantic-ui-options :as suo]
    [com.fulcrologic.rad.report-options :as ro]
    [taoensso.timbre :as log]))

(defn row-action-buttons [report-instance row-props]
  (let [{::report/keys [row-actions]} (comp/component-options report-instance)
        {::suo/keys [report-row-button-grouping report-row-button-renderer]} (suo/get-rendering-options report-instance)]
    (when (seq row-actions)
      (div {:className (or (?! report-row-button-grouping report-instance) "ui buttons")}
        (map-indexed
          (fn [idx {:keys [label reload? visible? disabled? action] :as control}]
            (let [disabled?     (boolean (?! disabled? report-instance row-props))
                  onClick       (fn [evt]
                                  (evt/stop-propagation! evt)
                                  (when action
                                    (action report-instance row-props)
                                    (when reload?
                                      (control/run! report-instance))))
                  control-props (merge control
                                  {:label     label
                                   :key       idx
                                   :onClick   onClick
                                   :disabled? disabled?})
                  label         (?! label report-instance row-props control-props)]
              (when (or (nil? visible?) (?! visible? report-instance row-props))
                (dom/span {:key idx}
                  (if report-row-button-renderer
                    (report-row-button-renderer report-instance row-props control-props)
                    (if (string? label)
                      (dom/button :.ui.button {:key      idx
                                               :disabled disabled?
                                               :onClick  onClick}
                        label)
                      label))))))
          row-actions)))))

(defn column-alignment-class [report-instance attr]
  (let [alignment (?! (get attr ro/column-alignment) report-instance attr)]
    (case alignment
      :left "left aligned"
      :right "right aligned"
      :center "center aligned"
      "")))

(comp/defsc TableRowLayout [_ {:keys [report-instance props] :as rp}]
  {}
  (let [{::report/keys [columns link links on-select-row]} (comp/component-options report-instance)
        links          (or links link)
        action-buttons (row-action-buttons report-instance props)
        {:keys         [highlighted?]
         ::report/keys [idx]} (comp/get-computed props)
        sui-cell-class (suo/get-rendering-options report-instance suo/report-table-cell-class)]
    (dom/tr {:classes [(when highlighted? "active")]
             :onClick (fn [evt]
                        (evt/stop-propagation! evt)
                        (when-not (false? (suo/get-rendering-options report-instance suo/selectable-table-rows?))
                          (?! on-select-row report-instance props)
                          (report/select-row! report-instance idx)))}
      (map-indexed
        (fn [idx {::attr/keys [qualified-key] :as column}]
          (let [alignment-class (column-alignment-class report-instance column)
                column-classes  (str alignment-class " " (report/column-classes report-instance column))]
            (dom/td {:key     (str "col-" qualified-key)
                     :classes [(?! sui-cell-class report-instance idx) column-classes]}
              (let [{:keys [edit-form entity-id]} (report/form-link report-instance props qualified-key)
                    link-fn (get links qualified-key)
                    label   (report/formatted-column-value report-instance props column)]
                (cond
                  edit-form (dom/a {:onClick (fn [evt]
                                               (evt/stop-propagation! evt)
                                               (form/edit! report-instance edit-form entity-id))} label)
                  (fn? link-fn) (dom/a {:onClick (fn [evt]
                                                   (evt/stop-propagation! evt)
                                                   (link-fn report-instance props))} label)
                  :else label)))))
        columns)
      (when action-buttons
        (dom/td {:key       "actions"
                 :className (or
                              (?! sui-cell-class report-instance (count columns))
                              "collapsing")}
          action-buttons)))))

(let [ui-table-row-layout (comp/factory TableRowLayout)]
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
              (dom/a :.header {:onClick (fn [evt]
                                          (evt/stop-propagation! evt)
                                          (form/edit! report-instance edit-form entity-id))} header-label)
              (div :.header header-label)))
          (when description-label
            (div :.description description-label)))))))

(let [ui-list-row-layout (comp/factory ListRowLayout {:keyfn ::report/idx})]
  (defn render-list-row [report-instance row-class row-props]
    (ui-list-row-layout {:report-instance report-instance
                         :row-class       row-class
                         :props           row-props})))

(comp/defsc StandardReportControls [this {:keys [report-instance] :as env}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [controls (control/component-controls report-instance)
        {:keys [::report/paginate?]} (comp/component-options report-instance)
        {::suo/keys [report-action-button-grouping]} (suo/get-rendering-options report-instance)
        {:keys [input-layout action-layout]} (control/standard-control-layout report-instance)
        {:com.fulcrologic.rad.container/keys [controlled?]} (comp/get-computed report-instance)]
    (comp/fragment
      (div {:className (or
                         (?! (suo/get-rendering-options report-instance suo/controls-class))
                         "ui top attached compact segment")}
        (dom/h3 :.ui.header
          (or (some-> report-instance comp/component-options ::report/title (?! report-instance)) (trc "a table that shows a list of rows" "Report"))
          (div {:className (or (?! report-action-button-grouping report-instance)
                             "ui right floated buttons")}
            (keep (fn [k]
                    (let [control (get controls k)]
                      (when (and (or (not controlled?) (:local? control))
                              (-> (get control :visible? true)
                                (?! report-instance)))
                        (control/render-control report-instance k control))))
              action-layout)))
        (div :.ui.form
          (map-indexed
            (fn [idx row]
              (let [nfields (count (filter #(or (not controlled?) (:local? (get controls %))) row))]
                (div {:key       idx
                      :className (or
                                   (?! (suo/get-rendering-options report-instance suo/report-controls-row-class) report-instance idx)
                                   (sui-form/n-fields-string nfields))}
                  (keep #(let [control (get controls %)]
                           (when (or (not controlled?) (:local? control))
                             (control/render-control report-instance % control))) row))))
            input-layout))
        (when paginate?
          (let [page-count (report/page-count report-instance)]
            (when (> page-count 1)
              (div :.ui.two.column.centered.grid
                (div :.two.wide.column
                  (div {:style {:paddingTop "4px"}}
                    #?(:cljs
                       (sui-pagination/ui-pagination {:activePage   (report/current-page report-instance)
                                                      :onPageChange (fn [_ data]
                                                                      (report/goto-page! report-instance (comp/isoget data "activePage")))
                                                      :totalPages   page-count
                                                      :size         "tiny"}))))))))))))

(let [ui-standard-report-controls (comp/factory StandardReportControls)]
  (defn render-standard-controls [report-instance]
    (ui-standard-report-controls {:report-instance report-instance})))

(comp/defsc ListReportLayout [this {:keys [report-instance] :as env}]
  {:shouldComponentUpdate (fn [_ _ _] true)
   :initLocalState        (fn [_] {:row-factory (memoize
                                                  (fn [cls]
                                                    (comp/computed-factory cls
                                                      {:keyfn (fn [props] (some-> props (comp/get-computed ::report/idx)))})))})}
  (let [{::report/keys [BodyItem]} (comp/component-options report-instance)
        render-report-body-item ((comp/get-state this :row-factory) BodyItem)
        render-controls         (report/control-renderer this)
        extra-parent-query      (comp/component-options report-instance ::report/query-inclusions)
        query-inclusion-props   (select-keys (comp/props report-instance) extra-parent-query)
        rows                    (report/current-rows report-instance)
        loading?                (report/loading? report-instance)]
    (div
      (when render-controls
        (render-controls report-instance))
      (div :.ui.attached.segment
        (div :.ui.loader {:classes [(when loading? "active")]})
        (when (seq rows)
          (div :.ui.relaxed.divided.list
            (map-indexed (fn [idx row]
                           (render-report-body-item row (merge query-inclusion-props
                                                          {:report-instance report-instance
                                                           :row-class       BodyItem
                                                           ::report/idx     idx}))) rows)))))))

(let [ui-list-report-layout (comp/factory ListReportLayout {:keyfn ::report/idx})]
  (defn render-list-report-layout [report-instance]
    (ui-list-report-layout {:report-instance report-instance})))

(defn render-standard-table [this {:keys [report-instance]}]
  (let [{report-column-headings ::report/column-headings
         report-column-infos    ::report/column-infos
         ::report/keys          [columns row-actions BodyItem compare-rows table-class]} (comp/component-options report-instance)
        render-report-body-item ((comp/get-state this :row-factory) BodyItem)
        column-headings         (mapv (fn [{::report/keys [column-heading column-info]
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
                                  columns)
        rows                    (report/current-rows report-instance)
        extra-parent-query      (comp/component-options report-instance ::report/query-inclusions)
        query-inclusion-props   (select-keys (comp/props report-instance) extra-parent-query)
        props                   (comp/props report-instance)
        sort-params             (-> props :ui/parameters ::report/sort)
        sortable?               (if-not (boolean compare-rows)
                                  (constantly false)
                                  (if-let [sortable-columns (some-> sort-params :sortable-columns set)]
                                    (fn [{::attr/keys [qualified-key]}] (contains? sortable-columns qualified-key))
                                    (constantly true)))
        ascending?              (and sortable? (:ascending? sort-params))
        sorting-by              (and sortable? (:sort-by sort-params))
        has-row-actions?        (seq row-actions)
        sui-header-class        (suo/get-rendering-options report-instance suo/report-table-header-class)
        sui-table-class         (?! (suo/get-rendering-options report-instance suo/report-table-class) report-instance)]
    (dom/table {:className (or sui-table-class "ui selectable table") :classes [table-class]}
      (dom/thead
        (dom/tr
          (map-indexed (fn [idx {:keys [label help column]}]
                         (let [alignment-class (column-alignment-class report-instance column)]
                           (dom/th {:key     idx
                                    :classes [alignment-class (?! sui-header-class report-instance idx)]}
                             (if (sortable? column)
                               (dom/a {:onClick (fn [evt]
                                                  (evt/stop-propagation! evt)
                                                  (report/sort-rows! report-instance column))}
                                 label
                                 (when (= sorting-by (::attr/qualified-key column))
                                   (if ascending?
                                     (dom/i :.angle.down.icon)
                                     (dom/i :.angle.up.icon))))
                               label)
                             #?(:cljs
                                (when help
                                  (ui-popup {:trigger (dom/i :.ui.circle.info.icon)}
                                    (ui-popup-content {}
                                      help)))))))
            column-headings)
          (when has-row-actions? (dom/th {:classes [(or
                                                      (?! sui-header-class report-instance (count column-headings))
                                                      "collapsing")]} ""))))
      (when (seq rows)
        (dom/tbody
          (map-indexed
            (fn [idx row]
              (let [highlighted-row-idx (report/currently-selected-row report-instance)]
                (render-report-body-item row (merge query-inclusion-props
                                               {:report-instance report-instance
                                                :row-class       BodyItem
                                                :highlighted?    (= idx highlighted-row-idx)
                                                ::report/idx     idx}))))
            rows))))))

(defn render-rotated-table [_ {:keys [report-instance] :as env}]
  (let [{report-column-headings ::report/column-headings
         ::report/keys          [columns row-actions compare-rows table-class]} (comp/component-options report-instance)
        props            (comp/props report-instance)
        sort-params      (-> props :ui/parameters ::report/sort)
        sortable?        (if-not (boolean compare-rows)
                           (constantly false)
                           (if-let [sortable-columns (some-> sort-params :sortable-columns set)]
                             (fn [{::attr/keys [qualified-key]}] (contains? sortable-columns qualified-key))
                             (constantly true)))
        ascending?       (and sortable? (:ascending? sort-params))
        sorting-by       (and sortable? (:sort-by sort-params))
        row-headings     (mapv (fn [{::report/keys [column-heading]
                                     ::attr/keys   [qualified-key] :as attr}]
                                 (let [label (or
                                               (?! (get report-column-headings qualified-key) report-instance)
                                               (?! column-heading report-instance)
                                               (some-> qualified-key name str/capitalize)
                                               "")]
                                   (if (sortable? attr)
                                     (dom/a {:onClick (fn [evt]
                                                        (evt/stop-propagation! evt)
                                                        (report/sort-rows! report-instance attr))}
                                       label
                                       (when (= sorting-by (::attr/qualified-key attr))
                                         (if ascending?
                                           (dom/i :.angle.down.icon)
                                           (dom/i :.angle.up.icon))))
                                     label)))
                           columns)
        rows             (report/current-rows report-instance)
        has-row-actions? (seq row-actions)
        sui-table-class  (?! (suo/get-rendering-options report-instance suo/report-rotated-table-class) report-instance)
        sui-cell-class   (suo/get-rendering-options report-instance suo/report-table-cell-class)]
    (dom/table {:className (or sui-table-class "ui compact collapsing definition selectable table")
                :classes   [table-class]}
      (when (seq rows)
        (comp/fragment
          (dom/thead
            (let [col (first columns)]
              (dom/tr {:key "hrow"}
                (dom/th
                  (get row-headings 0))
                (map-indexed
                  (fn [idx row]
                    (dom/th {:key idx}
                      (report/formatted-column-value report-instance row col))) rows)
                (when has-row-actions?
                  (dom/td {:key "actions"}
                    (row-action-buttons report-instance col))))))
          (dom/tbody
            (map-indexed
              (fn [idx col]
                (dom/tr {:key idx}
                  (dom/td (get row-headings (inc idx)))
                  (map-indexed
                    (fn [idx row]
                      (let [user-defined-cell-class (?! sui-cell-class report-instance idx)]
                        (dom/td {:key idx :className (or user-defined-cell-class "right aligned")}
                          (report/formatted-column-value report-instance row col)))) rows)
                  (when has-row-actions?
                    (let [user-defined-cell-class (when sui-cell-class (sui-cell-class report-instance (count rows)))]
                      (dom/td {:key "actions" :classes [user-defined-cell-class]}
                        (row-action-buttons report-instance col))))))
              (rest columns))))))))

(comp/defsc TableReportLayout [this {:keys [report-instance] :as env}]
  {:initLocalState        (fn [this] {:row-factory (memoize (fn [cls] (comp/computed-factory cls
                                                                        {:keyfn (fn [props]
                                                                                  (some-> props
                                                                                    (comp/get-computed ::report/idx)
                                                                                    (str "-" (comp/get-ident cls props))))})))})
   :shouldComponentUpdate (fn [_ _ _] true)}
  (let [{::report/keys [rotate?]} (comp/component-options report-instance)
        rotate?         (?! rotate? report-instance)
        render-controls (report/control-renderer report-instance)
        loading?        (report/loading? report-instance)
        props           (comp/props report-instance)
        busy?           (:ui/busy? props)
        layout-class    (?! (suo/get-rendering-options report-instance suo/layout-class) report-instance)
        body-class      (or
                          (?! (suo/get-rendering-options report-instance suo/body-class) report-instance)
                          "ui attached segment")]
    (div {:className layout-class}
      (when render-controls
        (render-controls report-instance))
      (div {:className body-class}
        (when (or busy? loading?)
          (div :.ui.active.loader {}))
        (if rotate?
          (render-rotated-table this env)
          (render-standard-table this env))))))

(let [ui-table-report-layout (comp/factory TableReportLayout {:keyfn ::report/idx})]
  (defn render-table-report-layout [this]
    (ui-table-report-layout {:report-instance this})))

