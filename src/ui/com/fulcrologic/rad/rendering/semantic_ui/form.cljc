(ns com.fulcrologic.rad.rendering.semantic-ui.form
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom :refer [div h3 button i span]]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div h3 button i span]])
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr trf trc]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.debugging :as debug]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.form-render-options :as fro]
    [com.fulcrologic.rad.options-util :refer [?! narrow-keyword]]
    [com.fulcrologic.rad.rendering.semantic-ui.form-options :as sufo]
    [com.fulcrologic.rad.semantic-ui-options :as suo]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal :refer [ui-modal]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-actions :refer [ui-modal-actions]]
    [com.fulcrologic.semantic-ui.modules.modal.ui-modal-content :refer [ui-modal-content]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn render-to-many [{::form/keys [form-instance] :as env} {k ::attr/qualified-key :as attr} options]
  (let [{:semantic-ui/keys [add-position]
         ::form/keys       [ui title can-delete? can-add? added-via-upload?]
         ::keys            [ref-container-class]} (fo/subform-options (comp/component-options form-instance) attr)
        form-instance-props (comp/props form-instance)
        read-only?          (form/read-only? form-instance attr)
        add?                (if read-only? false (?! can-add? form-instance attr))
        delete?             (fn [item] (and (not read-only?) (?! can-delete? form-instance item)))
        items               (get form-instance-props k)
        title               (?! (or title (some-> ui (comp/component-options ::form/title)) "") form-instance form-instance-props)
        invalid?            (form/invalid-attribute-value? env attr)
        visible?            (form/field-visible? form-instance attr)
        validation-message  (form/validation-error-message env attr)
        add                 (when (or (nil? add?) add?)
                              (let [order (if (keyword? add?) add? :append)]
                                (if (?! added-via-upload? env)
                                  (dom/input {:type     "file"
                                              :onChange (fn [evt]
                                                          (let [new-id     (tempid/tempid)
                                                                js-file    (-> evt blob/evt->js-files first)
                                                                attributes (comp/component-options ui ::form/attributes)
                                                                id-attr    (comp/component-options ui ::form/id)
                                                                id-key     (::attr/qualified-key id-attr)
                                                                {::attr/keys [qualified-key] :as sha-attr} (first (filter ::blob/store
                                                                                                                    attributes))
                                                                target     (conj (comp/get-ident form-instance) k)
                                                                new-entity (fs/add-form-config ui
                                                                             {id-key        new-id
                                                                              qualified-key ""})]
                                                            (merge/merge-component! form-instance ui new-entity order target)
                                                            (blob/upload-file! form-instance sha-attr js-file {:file-ident [id-key new-id]})))})
                                  (let [possible-types (if (comp/union-component? ui)
                                                         (mapv comp/query->component (vals (comp/get-query ui)))
                                                         [ui])]
                                    (map-indexed
                                      (fn [idx c]
                                        (let [add-child! (fn [_] (form/add-child! form-instance k c {::form/order order}))
                                              add-label  (or
                                                           (?! (comp/component-options c fo/add-label) c add-child!)
                                                           "")]
                                          (comp/fragment {:key (str idx)}
                                            (if (string? add-label)
                                              (button :.ui.tiny.icon.button
                                                {:classes [(when (seq add-label) "labeled")]
                                                 :key     (str idx)
                                                 :onClick add-child!}
                                                (i :.plus.icon)
                                                add-label)
                                              add-label))))
                                      possible-types)))))
        ui-factory          (comp/computed-factory ui {:keyfn (fn [item] (-> ui (comp/get-ident item) second str))})
        top-class           (sufo/top-class form-instance attr)
        body-class          (or top-class "ui container")]
    (when visible?
      (div {:className body-class :key (str k)}
        (h3 title (span ent/nbsp ent/nbsp) (when (or (nil? add-position) (= :top add-position)) add))
        (when invalid?
          (div :.ui.red.message
            validation-message))
        (if (seq items)
          (div {:className (or (?! ref-container-class env) "ui segments")}
            (mapv
              (fn [props]
                (ui-factory props
                  (merge
                    env
                    {::form/parent          form-instance
                     ::form/parent-relation k
                     ::form/can-delete?     (if delete? (delete? props) false)})))
              items))
          (div :.ui.message (tr "None.")))
        (when (= :bottom add-position) add)))))

(defn render-to-one [{::form/keys [master-form
                                   form-instance] :as env} {k ::attr/qualified-key :as attr} options]
  (let [{::form/keys [ui can-add? can-delete? title ref-container-class]} (fo/subform-options options attr)
        form-props (comp/props form-instance)
        props      (get form-props k)
        top-class  (or (sufo/top-class form-instance attr) "")]
    (cond
      props
      (let [ui-factory         (comp/computed-factory ui)
            ChildForm          (if (comp/union-component? ui)
                                 (comp/union-child-for-props ui props)
                                 ui)
            title              (?! (or title (some-> ChildForm (comp/component-options ::form/title)) "") form-instance form-props)
            visible?           (form/field-visible? form-instance attr)
            invalid?           (form/invalid-attribute-value? env attr)
            validation-message (form/validation-error-message env attr)
            std-props          {::form/nested?         true
                                ::form/parent          form-instance
                                ::form/parent-relation k
                                ::form/can-delete?     (or
                                                         (?! can-delete? form-instance form-props)
                                                         false)}]
        (when visible?
          (div {:key       (str k)
                :className top-class
                :classes   [(?! ref-container-class env)]}
            (h3 :.ui.header title)
            (when invalid?
              (div :.ui.red.message validation-message))
            (ui-factory props (merge env std-props)))))

      (or (nil? can-add?) (?! can-add? form-instance attr))
      (let [possible-forms (if (comp/union-component? ui)
                             (mapv comp/query->component (vals (comp/get-query ui)))
                             [ui])]
        (div {:key       (str k)
              :className top-class
              :classes   [(?! ref-container-class env)]}
          (h3 :.ui.header title)
          (map-indexed
            (fn [idx ui]
              (let [add-child! (fn [] (form/add-child! form-instance k ui))
                    add-label  (or
                                 (?! (comp/component-options ui fo/add-label) ui add-child!)
                                 "")]
                (comp/fragment {:key (str idx)}
                  (if (string? add-label)
                    (button :.ui.icon.button {:onClick add-child!
                                              :classes [(when (seq add-label) "labeled")]}
                      (dom/i :.plus.icon)
                      add-label)
                    add-label))))
            possible-forms))))))

(defn standard-ref-container [env {::attr/keys [cardinality] :as attr} options]
  (if (= :many cardinality)
    (render-to-many env attr options)
    (render-to-one env attr options)))

(defsc SingleFile [this {{::form/keys [form-instance master-form] :as env} :env
                         {k ::attr/qualified-key :as attr}                 :attribute
                         options                                           :options}]
  (let [{::form/keys [ui title can-delete? can-add?]} (fo/subform-options options attr)
        parent     (comp/props form-instance)
        form-props (comp/props form-instance)
        read-only? (or
                     (form/read-only? master-form attr)
                     (form/read-only? form-instance attr))
        add?       (if read-only? false (?! can-add? form-instance attr))
        props      (get form-props k)
        ui-factory (comp/computed-factory ui)
        title      (?! (or title (some-> ui (comp/component-options ::form/title)) "") form-instance form-props)
        label      (form/field-label env attr)
        visible?   (form/field-visible? form-instance attr)
        top-class  (sufo/top-class form-instance attr)
        std-props  {::form/nested?         true
                    ::form/parent          form-instance
                    ::form/parent-relation k
                    ::form/can-delete?     (if can-delete?
                                             (can-delete? parent props)
                                             false)}
        upload-id   (str k "-file-upload")
        add         (when (or (nil? add?) add?)
                      (dom/div {}
                        (dom/label :.ui.labeled.green.icon.button {:htmlFor upload-id}
                          (dom/i :.ui.plus.icon)
                          (tr "Add File"))
                        (dom/input {:type     "file"
                                    ;; trick: changing the key on change clears the input, so a failed upload can be retried
                                    :key      (comp/get-state this :input-key)
                                    :id       upload-id
                                    :style    {:zIndex  -1
                                               :width   "1px"
                                               :height  "1px"
                                               :opacity 0}
                                    :onChange (fn [evt]
                                                (let [new-id     (tempid/tempid)
                                                      js-file    (-> evt blob/evt->js-files first)
                                                      attributes (comp/component-options ui ::form/attributes)
                                                      id-attr    (comp/component-options ui ::form/id)
                                                      id-key     (::attr/qualified-key id-attr)
                                                      {::attr/keys [qualified-key] :as sha-attr} (first (filter ::blob/store
                                                                                                          attributes))
                                                      target     (conj (comp/get-ident form-instance) k)
                                                      new-entity (fs/add-form-config ui
                                                                   {id-key        new-id
                                                                    qualified-key ""})]
                                                  (merge/merge-component! form-instance ui new-entity :replace target)
                                                  (blob/upload-file! form-instance sha-attr js-file {:file-ident [id-key new-id]})
                                                  (comp/set-state! this {:input-key (str (rand-int 1000000))})))})))]
    (when visible?
      (div {:className (or top-class "field")
            :key       (str k)}
        (dom/h2 :.ui.header title)
        (dom/label label)
        (if props
          (ui-factory props (merge env std-props))
          add)))))

(def ui-single-file (comp/factory SingleFile))

(defsc ManyFiles [this {{::form/keys [form-instance master-form] :as env} :env
                        {k ::attr/qualified-key :as attr}                 :attribute
                        options                                           :options}]
  {:initLocalState (fn [this] {:input-key (str (rand-int 1000000))})}
  (let [{:semantic-ui/keys [add-position]
         ::form/keys       [ui title can-delete? can-add? sort-children]} (fo/subform-options options attr)
        form-instance-props (comp/props form-instance)
        read-only?          (or
                              (form/read-only? master-form attr)
                              (form/read-only? form-instance attr))
        add?                (if read-only? false (?! can-add? form-instance attr))
        delete?             (if read-only? false (fn [item] (?! can-delete? form-instance item)))
        items               (-> form-instance comp/props k
                              (cond->
                                sort-children sort-children))
        title               (?! (or title (some-> ui (comp/component-options ::form/title)) "") form-instance form-instance-props)
        upload-id           (str k "-file-upload")
        add                 (when (or (nil? add?) add?)
                              (dom/div
                                (dom/label :.ui.labeled.green.icon.button {:htmlFor upload-id}
                                  (dom/i :.ui.plus.icon)
                                  (tr "Add File"))
                                (dom/input {:type     "file"
                                            ;; trick: changing the key on change clears the input, so a failed upload can be retried
                                            :key      (comp/get-state this :input-key)
                                            :id       upload-id
                                            :style    {:zIndex  -1
                                                       :width   "1px"
                                                       :height  "1px"
                                                       :opacity 0}
                                            :onChange (fn [evt]
                                                        (let [new-id     (tempid/tempid)
                                                              js-file    (-> evt blob/evt->js-files first)
                                                              attributes (comp/component-options ui ::form/attributes)
                                                              id-attr    (comp/component-options ui ::form/id)
                                                              id-key     (::attr/qualified-key id-attr)
                                                              {::attr/keys [qualified-key] :as sha-attr} (first (filter ::blob/store
                                                                                                                  attributes))
                                                              target     (conj (comp/get-ident form-instance) k)
                                                              new-entity (fs/add-form-config ui
                                                                           {id-key        new-id
                                                                            qualified-key ""})]
                                                          (merge/merge-component! form-instance ui new-entity :append target)
                                                          (blob/upload-file! form-instance sha-attr js-file {:file-ident [id-key new-id]})
                                                          (comp/set-state! this {:input-key (str (rand-int 1000000))})))})))
        visible?            (form/field-visible? form-instance attr)
        top-class           (sufo/top-class form-instance attr)
        ui-factory          (comp/computed-factory ui {:keyfn (fn [item] (-> ui (comp/get-ident item) second str))})]
    (when visible?
      (div {:className (or top-class "ui basic segment")
            :key       (str k)}
        (dom/h2 :.ui.header title)
        (when (or (nil? add-position) (= :top add-position)) add)
        (if (seq items)
          (div :.ui.very.relaxed.items
            (mapv
              (fn [props]
                (ui-factory props
                  (merge
                    env
                    {::form/parent          form-instance
                     ::form/parent-relation k
                     ::form/can-delete?     (if delete? (?! delete? props) false)})))
              items))
          (div :.ui.message
            (trc "there are no files in a list of uploads" "No files.")))

        (when (= :bottom add-position) add)))))

(def ui-many-files (comp/factory ManyFiles {:keyfn (fn [{:keys [attribute]}] (::attr/qualified-key attribute))}))

(defn file-ref-container
  [env {::attr/keys [cardinality] :as attr} options]
  (if (= :many cardinality)
    (ui-many-files {:env env :attribute attr :options options})
    (ui-single-file {:env env :attribute attr :options options})))

(defn render-attribute [env attr options]
  (cond
    (or
      (fro/fields-style attr)
      (fro/style attr)) (form/render-field env attr)
    (fo/subform-options options attr) (let [render-ref (or (form/ref-container-renderer env attr) standard-ref-container)]
                                        (render-ref env attr options))
    :else (form/render-field env attr)))

(def n-fields-string {1 "one field"
                      2 "two fields"
                      3 "three fields"
                      4 "four fields"
                      5 "five fields"
                      6 "six fields"
                      7 "seven fields"})

(def attribute-map (memoize
                     (fn [attributes]
                       (reduce
                         (fn [m {::attr/keys [qualified-key] :as attr}]
                           (assoc m qualified-key attr))
                         {}
                         attributes))))

(defn- render-layout* [env options k->attribute layout]
  (when #?(:clj true :cljs goog.DEBUG)
    (when-not (and (vector? layout) (every? vector? layout))
      (log/error "::form/layout must be a vector of vectors!")))
  (try
    (into []
      (map-indexed
        (fn [idx row]
          (div {:key idx :className (n-fields-string (count row))}
            (mapv (fn [col]
                    (enc/if-let [_    k->attribute
                                 attr (k->attribute col)]
                      (render-attribute env attr options)
                      (if (some-> options ::control/controls (get col))
                        (control/render-control (::form/form-instance env) col)
                        (log/error "Missing attribute (or lookup) for" col))))
              row)))
        layout))
    (catch #?(:clj Exception :cljs :default) _)))

(defn render-layout [env {::form/keys [attributes layout] :as options}]
  (let [k->attribute (attribute-map attributes)]
    (render-layout* env options k->attribute layout)))

(defsc TabbedLayout [this env {::form/keys [attributes tabbed-layout] :as options}]
  {:initLocalState (fn [this]
                     (try
                       {:current-tab 0
                        :tab-details (memoize
                                       (fn [attributes tabbed-layout]
                                         (let [k->attr           (attribute-map attributes)
                                               tab-labels        (filterv string? tabbed-layout)
                                               tab-label->layout (into {}
                                                                   (map vec)
                                                                   (partition 2 (mapv first (partition-by string? tabbed-layout))))]
                                           {:k->attr           k->attr
                                            :tab-labels        tab-labels
                                            :tab-label->layout tab-label->layout})))}
                       (catch #?(:clj Exception :cljs :default) _
                         (log/error "Cannot build tabs for tabbed layout. Check your tabbed-layout options for" (comp/component-name this)))))}
  (let [{:keys [tab-details current-tab]} (comp/get-state this)
        {:keys [k->attr tab-labels tab-label->layout]} (tab-details attributes tabbed-layout)
        active-layout (some->> current-tab
                        (get tab-labels)
                        (get tab-label->layout))]
    (div {:key (str current-tab)}
      (div :.ui.pointing.menu {}
        (map-indexed
          (fn [idx title]
            (dom/a :.item
              {:key     (str idx)
               :onClick #(comp/set-state! this {:current-tab idx})
               :classes [(when (= current-tab idx) "active")]}
              title)) tab-labels))
      (div :.ui.segment
        (render-layout* env options k->attr active-layout)))))

(def ui-tabbed-layout (comp/computed-factory TabbedLayout))

(declare standard-form-layout-renderer)

(defn standard-abandon-modal [{::form/keys [form-instance] :as env} open?]
  (ui-modal {:open open?}
    (ui-modal-content {}
      (tr "The form has unsaved changes. Do you wish to abandon the changes or return to editing?"))
    (ui-modal-actions {}
      (dom/button :.ui.button
        {:onClick (fn [] (form/clear-route-denied! form-instance))}
        (tr "Return to Editing"))
      (dom/button :.ui.button
        {:onClick (fn [] (form/continue-abandoned-route! form-instance))}
        (tr "Abandon Changes")))))

(defsc StandardFormContainer [this {::form/keys [props computed-props form-instance master-form] :as env}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [{::form/keys [can-delete?]} computed-props
        nested?         (not= master-form form-instance)
        read-only-form? (or
                          (?! (comp/component-options form-instance ::form/read-only?) form-instance)
                          (?! (comp/component-options master-form ::form/read-only?) master-form))
        {:ui/keys    [new?]
         ::form/keys [errors]} props
        invalid?        (if read-only-form? false (form/invalid? env))
        errors?         (or invalid? (seq errors))
        render-fields   (or (form/form-layout-renderer env) standard-form-layout-renderer)]
    (when #?(:cljs goog.DEBUG :clj true)
      (let [valid? (if read-only-form? true (form/valid? env))
            dirty? (if read-only-form? false (or new? (fs/dirty? props)))]
        (log/debug "Form " (comp/component-name form-instance) " valid? " valid?)
        (log/debug "Form " (comp/component-name form-instance) " dirty? " dirty?)))
    (if nested?
      (div {:className (or (?! (comp/component-options form-instance ::ref-element-class) env) "ui segment")}
        (div :.ui.form {:classes [(when errors? "error")]
                        :key     (str (comp/get-ident form-instance))}
          (when can-delete?
            (button :.ui.icon.primary.right.floated.button {:disabled (not can-delete?)
                                                            :onClick  (fn [] (form/delete-child! env))}
              (i :.times.icon)))
          (render-fields env)))
      (let [{::form/keys [title action-buttons show-header?]} (comp/component-options form-instance)
            {:ui/keys [route-denied?]} (comp/props form-instance)
            title          (?! title form-instance props)
            action-buttons (if action-buttons action-buttons form/standard-action-buttons)
            show-header?   (cond
                             (some? show-header?) (?! show-header? master-form)
                             (some? (fo/show-header? computed-props)) (?! (fo/show-header? computed-props) master-form)
                             :else true)
            abandon-modal  (form/render-fn env :async-abandon-modal)]
        (comp/fragment
          (when (fn? abandon-modal)
            (abandon-modal env route-denied?))
          (div {:key       (str (comp/get-ident form-instance))
                :className (or
                             (?! (suo/get-rendering-options form-instance suo/layout-class) env)
                             (?! (comp/component-options form-instance suo/layout-class) env)
                             (?! (comp/component-options form-instance ::top-level-class) env)
                             "ui container")}
            (when show-header?
              (div {:className (or
                                 (?! (suo/get-rendering-options form-instance suo/controls-class) env)
                                 (?! (comp/component-options form-instance ::controls-class) env)
                                 "ui top attached segment")}
                (div {:style {:display        "flex"
                              :justifyContent "space-between"
                              :flexWrap       "wrap"}}
                  (dom/h3 :.ui.header {:style {:wordWrap "break-word" :maxWidth "100%"}}
                    title)
                  (div :.ui.buttons {:style {:textAlign "right" :display "inline" :flexGrow "1"}}
                    (keep #(control/render-control master-form %) action-buttons)))))
            (div {:classes [(or (?! (comp/component-options form-instance ::form-class) env) "ui attached form")
                            (when errors? "error")]}
              (when invalid?
                (div :.ui.red.message (tr "The form has errors and cannot be saved.")))
              (when (seq errors)
                (div :.ui.red.message
                  (div :.content
                    (dom/div :.ui.list
                      (map-indexed
                        (fn [idx {:keys [message]}]
                          (dom/div :.item {:key (str idx)}
                            (dom/i :.triangle.exclamation.icon)
                            (div :.content (str message))))
                        errors))
                    (when-not new?
                      (dom/a {:onClick (fn []
                                         (form/undo-via-load! env))} (tr "Reload from server"))))))
              (div :.ui.attached.segment
                (render-fields env)))))))))

(def standard-form-container (comp/factory StandardFormContainer))

(defn standard-form-layout-renderer [{::form/keys [form-instance] :as env}]
  (let [{::form/keys [attributes layout tabbed-layout debug?] :as options} (comp/component-options form-instance)
        layout (cond
                 (vector? layout) (render-layout env options)
                 (vector? tabbed-layout) (ui-tabbed-layout env options)
                 :else (mapv (fn [attr] (render-attribute env attr options)) attributes))]
    (if (and #?(:clj false :cljs goog.DEBUG) debug?)
      (debug/top-bottom-debugger form-instance (comp/props form-instance)
        (constantly layout))
      layout)))

(defn- file-icon-renderer* [{::form/keys [form-instance] :as env}]
  (let [{::form/keys [attributes] :as options} (comp/component-options form-instance)
        attribute (first (filter ::blob/store attributes))
        sha-key   (::attr/qualified-key attribute)
        file-key  (blob/filename-key sha-key)
        url-key   (blob/url-key sha-key)
        props     (comp/props form-instance)
        filename  (get props file-key "File")
        dirty?    (fs/dirty? props sha-key)
        failed?   (blob/failed-upload? props sha-key)
        invalid?  (form/invalid-attribute-value? env attribute)
        pct       (blob/upload-percentage props sha-key)
        sha       (get props sha-key)
        url       (get props url-key)]
    (if (blob/uploading? props sha-key)
      (dom/span :.item {:key (str sha)}
        (dom/div :.ui.tiny.image
          (dom/i :.huge.file.icon)
          (dom/div :.ui.active.red.loader {:style {:marginLeft "-10px"}})
          (dom/div :.ui.bottom.attached.blue.progress {:data-percent pct}
            (div :.bar {:style {:transitionDuration "300ms"
                                :width              pct}}
              (div :.progress ""))))
        (div :.middle.aligned.content
          filename)
        (dom/button :.ui.red.icon.button {:onClick (fn []
                                                     (app/abort! form-instance sha)
                                                     (form/delete-child! env))}
          (dom/i :.times.icon)))
      ((if dirty? dom/span dom/a) :.item
       {:target  "_blank"
        :key     (str sha)
        :href    (str url "?filename=" filename)
        :onClick (fn [evt]
                   #?(:cljs (when-not (or (not (blob/blob-downloadable? props sha-key))
                                        (js/confirm (tr "View/download?")))
                              (evt/stop-propagation! evt)
                              (evt/prevent-default! evt))))}
       (dom/div :.ui.tiny.image
         (if failed?
           (dom/i :.huge.skull.crossbones.icon)
           (dom/i :.huge.file.icon)))
       (div :.middle.aligned.content
         (str filename (cond failed? (str " (" (tr "Upload failed. Delete and try again.") ")")
                             dirty? (str " (" (tr "unsaved") ")"))))
       (dom/button :.ui.red.icon.button {:onClick (fn [evt]
                                                    (evt/stop-propagation! evt)
                                                    (evt/prevent-default! evt)
                                                    (when #?(:clj true :cljs (js/confirm (tr "Permanently Delete File?")))
                                                      (form/delete-child! env)))}
         (dom/i :.times.icon))))))

(defn file-icon-renderer [env] (file-icon-renderer* env))
