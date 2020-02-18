(ns com.fulcrologic.rad.rendering.semantic-ui.form
  (:require
    [clojure.string :as str]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro-i18n.i18n :as i18n :refer [tr]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.fulcro.mutations :as m]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom :refer [div h3 button i span]]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom :refer [div h3 button i span]])
    [com.fulcrologic.fulcro.dom.html-entities :as ent]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn render-to-many [{::form/keys [form-instance] :as env} {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{:semantic-ui/keys [add-position]
         ::form/keys       [ui title can-delete-row? can-add-row? sort-children]} (get subforms k)
        parent      (comp/props form-instance)
        can-delete? (fn [item] (can-delete-row? parent item))
        items       (-> form-instance comp/props k
                      (cond->
                        sort-children sort-children))
        title       (or
                      title
                      (some-> ui (comp/component-options ::form/title)) "")
        add         (when (and can-add-row? (can-add-row? parent))
                      (button :.ui.tiny.icon.button
                        {:onClick (fn [_]
                                    (form/add-child! (assoc env
                                                       ::form/parent-relation k
                                                       ::form/parent form-instance
                                                       ::form/child-class ui)))}
                        (i :.plus.icon)))
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
               ::form/can-delete?     (if can-delete-row? can-delete? false)})))
        items)
      (when (= :bottom add-position) add))))

(defn render-to-one [{::form/keys [form-instance] :as env} {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{::form/keys [ui can-delete-row? title pick-one label] :as subform-options} (get subforms k)
        picker?    (boolean pick-one)
        parent     (comp/props form-instance)
        form-props (comp/props form-instance)
        props      (get form-props k)
        title      (or title (some-> ui (comp/component-options ::form/title)) "")
        ui-factory (comp/computed-factory ui)
        std-props  {::form/nested?         true
                    ::form/parent          form-instance
                    ::form/parent-relation k
                    ::form/can-delete?     (if can-delete-row?
                                             (partial can-delete-row? parent)
                                             false)}]
    (cond
      picker?
      (let [selected-option props
            picker-key      (form/picker-join-key k)
            picker-props    (get form-props picker-key)]
        (div :.field {:key (str k)}
          (dom/label (str (or label (some-> k name str/capitalize))))
          (ui-factory picker-props
            (merge std-props subform-options {:currently-selected-value selected-option
                                              :onSelect                 (fn [v] (form/input-changed! env k v))}))))

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

(defn render-ref [env {::attr/keys [cardinality] :as attr} options]
  (if (= :many cardinality)
    (render-to-many env attr options)
    (render-to-one env attr options)))

(defn render-attribute [env attr {::form/keys [subforms] :as options}]
  (let [{k ::attr/qualified-key} attr]
    (if (contains? subforms k)
      (render-ref env attr options)
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

(defn render-layout [env {::form/keys [attributes layout] :as options}]
  (let [k->attribute (attribute-map attributes)]
    (map-indexed
      (fn [idx row]
        (div {:key idx :className (n-fields-string (count row))}
          (mapv (fn [col]
                  (enc/if-let [_    k->attribute
                               attr (k->attribute col)]
                    (render-attribute env attr options)
                    (log/error "Missing attribute (or lookup) for" col)))
            row)))
      layout)))

(defn ui-render-layout [{::form/keys [props computed-props form-instance master-form] :as env}]
  (let [{::form/keys [can-delete?]} computed-props
        nested?  (not= master-form form-instance)
        {::form/keys [attributes layout] :as options} (comp/component-options form-instance)
        valid?   (form/valid? env)
        invalid? (form/invalid? env)
        dirty?   (or (:ui/new? props) (fs/dirty? props))]
    (when #?(:cljs goog.DEBUG :clj true)
      (log/debug "Form " (comp/component-name form-instance) " valid? " valid?)
      (log/debug "Form " (comp/component-name form-instance) " dirty? " dirty?))
    (if nested?
      (div :.ui.form {:classes [(when invalid? "error")]}
        (div :.ui.segment
          (when can-delete?
            (button :.ui.icon.primary.right.floated.button {:disabled (not (can-delete? props))
                                                            :onClick  (fn []
                                                                        (form/delete-child! env))}
              (i :.times.icon)))
          (if layout
            (render-layout env options)
            (mapv
              (fn [attr] (render-attribute env attr options))
              attributes))))
      (div :.ui.container
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
                (button :.ui.positive.basic.button {:disabled (not dirty?)
                                                    :onClick  (fn [] (form/save! env))} (tr "Save")))))
          (div :.ui.error.message (tr "The form has errors and cannot be saved."))
          (div :.ui.attached.segment
            (if layout
              (render-layout env (merge options computed-props {::form/nested? true}))
              (mapv
                (fn [attr] (render-attribute env attr options))
                attributes))))))))

(defn layout-renderer [env]
  (ui-render-layout env))

(defn ui-render-entity-picker [{::form/keys [form-instance] :as env} attribute]
  (let [k (::attr/qualified-key attribute)
        {:keys [currently-selected-value onSearchChange onSelect]} (comp/get-computed form-instance)
        {:ui/keys [options]} (comp/props form-instance)
        {::form/keys [field-label]} attribute]
    (div :.ui.field {:key (str k)}
      (dom/label (or field-label (some-> k name str/capitalize)))
      (ui-wrapped-dropdown (cond->
                             {:onChange (fn [v] (onSelect v))
                              :value    currently-selected-value
                              :options  options}
                             onSearchChange (assoc :onSearchChange onSearchChange))))))
