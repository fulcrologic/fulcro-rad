(ns com.fulcrologic.rad.rendering.semantic-ui.report
  (:require
    [clojure.string :as str]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.fulcro.components :as comp]
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div]]
         [com.fulcrologic.semantic-ui.addons.pagination.ui-pagination :as sui-pagination]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div]]])
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.rad.rendering.semantic-ui.form :as sui-form]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.dom.events :as evt]))

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
                 :onClick  (fn [evt]
                             (evt/stop-propagation! evt)
                             (when action
                               (action report-instance row-props)
                               (when reload?
                                 (report/reload! report-instance))))}
                (?! label report-instance row-props))))
          row-actions)))))

(comp/defsc TableRowLayout [_ {:keys [report-instance props] :as rp}]
  {}
  (let [{::report/keys [columns link]} (comp/component-options report-instance)
        action-buttons (row-action-buttons report-instance props)
        {:keys         [highlighted?]
         ::report/keys [idx]} (comp/get-computed props)]
    (dom/tr {:classes [(when highlighted? "active")]
             :onClick (fn [evt]
                        (evt/stop-propagation! evt)
                        (report/select-row! report-instance idx))}
      (map
        (fn [{::attr/keys [qualified-key] :as column}]
          (let [column-classes (report/column-classes report-instance column)]
            (dom/td {:key     (str "col-" qualified-key)
                     :classes [column-classes]}
              (let [{:keys [edit-form entity-id]} (report/form-link report-instance props qualified-key)
                    link-fn (get link qualified-key)
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
        (dom/td :.collapsing {:key "actions"}
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
  (let [{:keys [:com.fulcrologic.rad.control/controls ::report/control-layout ::report/paginate?]} (comp/component-options report-instance)
        {:keys [action-buttons inputs]} control-layout]
    (let [action-buttons (or action-buttons
                           (keep (fn [[k v]] (when (= :button (:type v)) k)) controls))
          inputs         (or inputs
                           (vector (into [] (keep
                                              (fn [[k v]] (when-not (= :button (:type v)) k))
                                              controls))))]
      (comp/fragment
        (div :.ui.top.attached.compact.segment
          (dom/h3 :.ui.header
            (or (some-> report-instance comp/component-options ::report/title (?! report-instance)) "Report")
            (div :.ui.right.floated.buttons
              (keep (fn [k] (report/render-control report-instance k))
                action-buttons)))
          (div :.ui.form
            (map-indexed
              (fn [idx row]
                (div {:key idx :className (sui-form/n-fields-string (count row))}
                  (keep #(when (get controls %)
                           (report/render-control report-instance %)) row)))
              inputs))
          (when paginate?
            (let [page-count (report/page-count report-instance)]
              (when (> page-count 1)
                (div :.ui.two.column.centered.grid
                  (div :.column
                    (div {:style {:paddingTop "4px"}}
                      #?(:cljs
                         (sui-pagination/ui-pagination {:activePage   (report/current-page report-instance)
                                                        :onPageChange (fn [_ data]
                                                                        (report/goto-page! report-instance (comp/isoget data "activePage")))
                                                        :totalPages   page-count
                                                        :size         "tiny"})))))))))))))

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
        render-row      ((comp/get-state this :row-factory) BodyItem)
        render-controls (report/control-renderer this)
        rows            (report/current-rows report-instance)
        loading?        (report/loading? report-instance)]
    (div
      (when render-controls
        (render-controls report-instance))
      (div :.ui.attached.segment
        (div :.ui.loader {:classes [(when loading? "active")]})
        (when (seq rows)
          (div :.ui.relaxed.divided.list
            (map-indexed (fn [idx row] (render-row row {:report-instance report-instance
                                                        :row-class       BodyItem
                                                        ::report/idx     idx})) rows)))))))

(let [ui-list-report-layout (comp/factory ListReportLayout {:keyfn ::report/idx})]
  (defn render-list-report-layout [report-instance]
    (ui-list-report-layout {:report-instance report-instance})))

(comp/defsc TableReportLayout [this {:keys [report-instance] :as env}]
  {:initLocalState        (fn [this] {:row-factory (memoize (fn [cls] (comp/computed-factory cls
                                                                        {:keyfn (fn [props]
                                                                                  (some-> props (comp/get-computed ::report/idx)))})))})
   :shouldComponentUpdate (fn [_ _ _] true)}
  (let [{report-column-headings ::report/column-headings
         ::report/keys          [columns row-actions BodyItem compare-rows table-class]} (comp/component-options report-instance)
        render-row       ((comp/get-state this :row-factory) BodyItem)
        column-headings  (mapv (fn [{::report/keys [column-heading]
                                     ::attr/keys   [qualified-key] :as attr}]
                                 {:column attr
                                  :label  (or
                                            (?! (get report-column-headings qualified-key))
                                            (?! column-heading)
                                            (some-> qualified-key name str/capitalize)
                                            "")})
                           columns)
        render-controls  (report/control-renderer report-instance)
        rows             (report/current-rows report-instance)
        loading?         (report/loading? report-instance)
        props            (comp/props report-instance)
        sort-params      (-> props :ui/parameters ::report/sort)
        sortable?        (if-not (boolean compare-rows)
                           (constantly false)
                           (if-let [sortable-columns (some-> sort-params :sortable-columns set)]
                             (fn [{::attr/keys [qualified-key]}] (contains? sortable-columns qualified-key))
                             (constantly true)))
        busy?            (:ui/busy? props)
        forward?         (and sortable? (:forward? sort-params))
        sorting-by       (and sortable? (:sort-by sort-params))
        has-row-actions? (seq row-actions)]
    (div
      (when render-controls
        (render-controls report-instance))
      (div :.ui.attached.segment
        (div :.ui.orange.loader {:classes [(when (or busy? loading?) "active")]})
        (dom/table :.ui.selectable.table {:classes [table-class]}
          (dom/thead
            (dom/tr
              (map-indexed (fn [idx {:keys [label column]}]
                             (dom/th {:key idx}
                               (if (sortable? column)
                                 (dom/a {:onClick (fn [evt]
                                                    (evt/stop-propagation! evt)
                                                    (report/sort-rows! report-instance column))} (str label)
                                   (when (= sorting-by (::attr/qualified-key column))
                                     (if forward?
                                       (dom/i :.angle.down.icon)
                                       (dom/i :.angle.up.icon))))
                                 (str label))))
                column-headings)
              (when has-row-actions? (dom/th :.collapsing ""))))
          (when (seq rows)
            (dom/tbody
              (map-indexed
                (fn [idx row]
                  (let [highlighted-row-idx (report/currently-selected-row report-instance)]
                    (render-row row {:report-instance report-instance
                                     :row-class       BodyItem
                                     :highlighted?    (= idx highlighted-row-idx)
                                     ::report/idx     idx})))
                rows))))))))

(let [ui-table-report-layout (comp/factory TableReportLayout {:keyfn ::report/idx})]
  (defn render-table-report-layout [this]
    (ui-table-report-layout {:report-instance this})))


