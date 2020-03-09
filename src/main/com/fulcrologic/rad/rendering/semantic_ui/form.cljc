(ns com.fulcrologic.rad.rendering.semantic-ui.form
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.options-util :refer [?! narrow-keyword]]
    [com.fulcrologic.rad.ui-validation :as validation]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro-i18n.i18n :as i18n :refer [tr]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.application :as app]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom :refer [div h3 button i span]]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div h3 button i span]])
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn render-to-many [{::form/keys [form-instance] :as env} {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{:semantic-ui/keys [add-position]
         ::form/keys       [ui title can-delete? can-add? added-via-upload? sort-children]} (get subforms k)
        parent      (comp/props form-instance)
        can-delete? (fn [item] (?! can-delete? parent item))
        items       (-> form-instance comp/props k
                      (cond->
                        sort-children sort-children))
        title       (or
                      title
                      (some-> ui (comp/component-options ::form/title)) "")
        add         (when (or (nil? can-add?) (?! can-add? parent))
                      (if (?! added-via-upload? env)
                        (dom/input {:type     "file"
                                    :onChange (fn [evt]
                                                (log/info "UPLOAD FILE!!!")
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
                                                  (blob/upload-file! form-instance sha-attr js-file {:file-ident [id-key new-id]})))})
                        (button :.ui.tiny.icon.button
                          {:onClick (fn [_]
                                      (form/add-child! (assoc env
                                                         ::form/parent-relation k
                                                         ::form/parent form-instance
                                                         ::form/child-class ui)))}
                          (i :.plus.icon))))
        ui-factory  (comp/computed-factory ui {:keyfn (fn [item] (-> ui (comp/get-ident item) second str))})]
    (div :.ui.basic.segment {:key (str k)}
      (h3 title (span ent/nbsp ent/nbsp) (when (or (nil? add-position) (= :top add-position)) add))
      (mapv
        (fn [props]
          (ui-factory props
            (merge
              env
              {::form/parent          form-instance
               ::form/parent-relation k
               ::form/can-delete?     (if can-delete? (?! can-delete?) false)})))
        items)
      (when (= :bottom add-position) add))))

(defn render-to-one [{::form/keys [form-instance] :as env} {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{::form/keys [ui can-delete? title]} (get subforms k)
        parent     (comp/props form-instance)
        form-props (comp/props form-instance)
        props      (get form-props k)
        title      (or title (some-> ui (comp/component-options ::form/title)) "")
        ui-factory (comp/computed-factory ui)
        std-props  {::form/nested?         true
                    ::form/parent          form-instance
                    ::form/parent-relation k
                    ::form/can-delete?     (if can-delete?
                                             (partial can-delete? parent)
                                             false)}]
    (cond
      props
      (div {:key (str k)}
        (h3 :.ui.header title)
        (ui-factory props (merge env std-props)))

      :else
      (div {:key (str k)}
        (h3 :.ui.header title)
        (button {:onClick (fn [] (form/add-child! (assoc env
                                                    ::form/parent-relation k
                                                    ::form/parent form-instance
                                                    ::form/child-class ui)))} "Create")))))

(defn standard-ref-container [env {::attr/keys [cardinality] :as attr} options]
  (if (= :many cardinality)
    (render-to-many env attr options)
    (render-to-one env attr options)))

(defn render-single-file [{::form/keys [form-instance] :as env} {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{::form/keys [ui can-delete? title pick-one label] :as subform-options} (get subforms k)
        parent     (comp/props form-instance)
        form-props (comp/props form-instance)
        props      (get form-props k)
        ui-factory (comp/computed-factory ui)
        label      (form/field-label env attr)
        std-props  {::form/nested?         true
                    ::form/parent          form-instance
                    ::form/parent-relation k
                    ::form/can-delete?     (if can-delete?
                                             (partial can-delete? parent)
                                             false)}]
    (if props
      (div :.field {:key (str k)}
        (dom/label label)
        (ui-factory props (merge env std-props)))
      (div {:key (str k)}
        (div "Upload??? (TODO)")))))

(defsc ManyFiles [this {{::form/keys [form-instance] :as env} :env
                        {k ::attr/qualified-key :as attr}     :attribute
                        {::form/keys [subforms] :as options}  :options}]
  {:initLocalState (fn [this] {:input-key (str (rand-int 1000000))})}
  (let [{:semantic-ui/keys [add-position]
         ::form/keys       [ui title can-delete? can-add? sort-children]} (get subforms k)
        parent      (comp/props form-instance)
        can-delete? (fn [item] (?! can-delete? parent item))
        items       (-> form-instance comp/props k
                      (cond->
                        sort-children sort-children))
        title       (or
                      title
                      (some-> ui (comp/component-options ::form/title)) "")
        upload-id   (str k "-file-upload")
        add         (when (or (nil? can-add?) (?! can-add? parent))
                      (dom/div
                        (dom/label :.ui.green.button {:htmlFor upload-id}
                          (dom/i :.ui.plus.icon)
                          "Add File")
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
        ui-factory  (comp/computed-factory ui {:keyfn (fn [item] (-> ui (comp/get-ident item) second str))})]
    (div :.ui.basic.segment {:key (str k)}
      (dom/h2 :.ui.header title)
      (when (or (nil? add-position) (= :top add-position)) add)
      (div :.ui.very.relaxed.items
        (mapv
          (fn [props]
            (ui-factory props
              (merge
                env
                {::form/parent          form-instance
                 ::form/parent-relation k
                 ::form/can-delete?     (if can-delete? (?! can-delete?) false)})))
          items))
      (when (= :bottom add-position) add))))

(def ui-many-files (comp/factory ManyFiles {:keyfn (fn [{:keys [attribute]}] (::attr/qualified-key attribute))}))

(defn file-ref-container
  [env {::attr/keys [cardinality] :as attr} options]
  (if (= :many cardinality)
    (ui-many-files {:env env :attribute attr :options options})
    (render-single-file env attr options)))

(defn render-attribute [env attr {::form/keys [subforms] :as options}]
  (let [{k ::attr/qualified-key} attr]
    (if (contains? subforms k)
      (let [render-ref (or (form/ref-container-renderer env attr) standard-ref-container)]
        (render-ref env attr options))
      (form/render-field env attr))))

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
  (map-indexed
    (fn [idx row]
      (div {:key idx :className (n-fields-string (count row))}
        (mapv (fn [col]
                (enc/if-let [_    k->attribute
                             attr (k->attribute col)]
                  (render-attribute env attr options)
                  (log/error "Missing attribute (or lookup) for" col)))
          row)))
    layout))

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

(defn standard-form-container [{::form/keys [props computed-props form-instance master-form] :as env}]
  (let [{::form/keys [can-delete?]} computed-props
        nested?       (not= master-form form-instance)
        valid?        (form/valid? env)
        invalid?      (form/invalid? env)
        dirty?        (or (:ui/new? props) (fs/dirty? props))
        remote-busy?  (seq (::app/active-remotes props))
        render-fields (or (form/form-layout-renderer env) standard-form-layout-renderer)]
    (when #?(:cljs goog.DEBUG :clj true)
      (log/debug "Form " (comp/component-name form-instance) " valid? " valid?)
      (log/debug "Form " (comp/component-name form-instance) " dirty? " dirty?))
    (if nested?
      (div :.ui.form {:classes [(when invalid? "error")]
                      :key     (str (comp/get-ident form-instance))}
        (div :.ui.segment
          (when can-delete?
            (button :.ui.icon.primary.right.floated.button {:disabled (not (?! can-delete? props))
                                                            :onClick  (fn []
                                                                        (form/delete-child! env))}
              (i :.times.icon)))
          (render-fields env)))
      (div :.ui.container {:key (str (comp/get-ident form-instance))}
        (div :.ui.form {:classes [(when invalid? "error")]}
          (div :.ui.top.menu
            (div :.header.item
              (or (some-> form-instance comp/component-options ::form/title i18n/tr-unsafe) (tr "Edit")))
            (div :.right.item
              (div :.ui.basic.buttons
                (button :.ui.basic.button {:classes [(if dirty? "negative" "positive")]
                                           :onClick (fn [] (form/cancel! env))}
                  (if dirty? (tr "Cancel") (tr "Done")))
                (button :.ui.positive.basic.button {:disabled (not dirty?)
                                                    :onClick  (fn [] (form/undo-all! env))} (tr "Undo"))
                (button :.ui.positive.basic.button {:disabled (or (not dirty?) remote-busy?)
                                                    :classes  [(when remote-busy? "loading")]
                                                    :onClick  (fn [] (form/save! env))} (tr "Save")))))
          (div :.ui.error.message (tr "The form has errors and cannot be saved."))
          (div :.ui.attached.segment
            (render-fields env)))))))

(defn standard-form-layout-renderer [{::form/keys [form-instance] :as env}]
  (let [{::form/keys [attributes layout tabbed-layout] :as options} (comp/component-options form-instance)]
    (cond
      (vector? layout) (render-layout env options)
      (vector? tabbed-layout) (ui-tabbed-layout env options)
      :else (mapv (fn [attr] (render-attribute env attr options)) attributes))))

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
        invalid?  (validation/invalid-attribute-value? env attribute)
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
                                        (js/confirm "View/download?"))
                              (evt/stop-propagation! evt)
                              (evt/prevent-default! evt))))}
       (dom/div :.ui.tiny.image
         (if failed?
           (dom/i :.huge.skull.crossbones.icon)
           (dom/i :.huge.file.icon)))
       (div :.middle.aligned.content
         (str filename (cond failed? " (Upload failed. Delete and try again.)"
                             dirty? " (unsaved)")))
       (dom/button :.ui.red.icon.button {:onClick (fn [evt]
                                                    (evt/stop-propagation! evt)
                                                    (evt/prevent-default! evt)
                                                    (when #?(:clj true :cljs (js/confirm "Permanently Delete File?"))
                                                      (form/delete-child! env)))}
         (dom/i :.times.icon))))))

(defn file-icon-renderer [env] (file-icon-renderer* env))
