(ns com.fulcrologic.rad.rendering.semantic-ui.form
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro-i18n.i18n :as i18n :refer [tr]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn render-to-many [form-instance {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{::form/keys [ui can-delete-row? can-add-row? add-row-title]} (get subforms k)
        parent      (comp/props form-instance)
        can-delete? (fn [item] (can-delete-row? parent item))
        items       (-> form-instance comp/props k)
        ui-factory  (comp/computed-factory ui)
        {::form/keys [title] :as item-options} (comp/component-options ui)]
    (dom/div :.ui.basic.segment
      (dom/h3 "Addresses")
      (mapv
        (fn [props]
          (ui-factory props {::form/nested?         true
                             ::form/parent          form-instance
                             ::form/parent-relation k
                             ::form/can-delete?     (if can-delete-row?
                                                      (partial can-delete-row? parent)
                                                      false)}))
        items)
      (when (and can-add-row? (can-add-row? parent))
        (dom/div :.ui.basic.segment
          (dom/button :.ui.icon.button
            {:onClick (fn [_]
                        (log/error "TODO: Implement add row")
                        #_(form/add-row! form-instance k))}
            (dom/i :.plus.icon)))))))

(defn render-to-one [form-instance {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{::form/keys [ui can-delete-row?]} (get subforms k)
        parent     (comp/props form-instance)
        props      (-> form-instance comp/props k)
        ui-factory (comp/computed-factory ui)]
    (when props
      (ui-factory props {::form/nested?         true
                         ::form/parent          form-instance
                         ::form/parent-relation k
                         ::form/can-delete?     (if can-delete-row?
                                                  (partial can-delete-row? parent)
                                                  false)}))))

(defn render-ref [form-instance {::attr/keys [cardinality] :as attr} options]
  (if (= :many cardinality)
    (render-to-many form-instance attr options)
    (render-to-one form-instance attr options)))

(defn render-attribute [form-instance attr {::form/keys [subforms] :as options}]
  (let [props (comp/props form-instance)
        {k ::attr/qualified-key} attr]
    (if (contains? subforms k)
      (render-ref form-instance attr options)
      (form/render-field form-instance attr props))))

(def n->str {1 "one"
             2 "two"
             3 "three"
             4 "four"
             5 "five"
             6 "six"
             7 "seven"})

(def attribute-map (memoize
                     (fn [attributes]
                       (reduce
                         (fn [m {::attr/keys [qualified-key] :as attr}]
                           (assoc m qualified-key attr))
                         {}
                         attributes))))

(defn render-layout [form-instance {::form/keys [attributes layout] :as options}]
  (let [k->attribute (attribute-map attributes)]
    (map-indexed
      (fn [idx row]
        (dom/div {:key idx :className (str (n->str (count row)) " fields")}
          (mapv (fn [col]
                  (enc/if-let [_    k->attribute
                               attr (k->attribute col)]
                    (render-attribute form-instance attr options)
                    (log/error "Missing attribute (or lookup) for" col)))
            row)))
      layout)))

(defn ui-render-layout [props {::form/keys [form-instance parent parent-relation nested? can-delete?] :as computed-props}]
  (let [{::form/keys [attributes layout] :as options} (comp/component-options form-instance)
        dirty? (or (:ui/new? props) (fs/dirty? props))]
    (if nested?
      (dom/div :.ui.form

        (dom/div :.ui.segment
          (when can-delete?
            (dom/button :.ui.icon.primary.right.floated.button {:disabled (not (can-delete? props))
                                                                :onClick  (fn []
                                                                            (log/error "TODO: implement child delete")
                                                                            #_(form/delete-child! parent parent-relation props))}
              (dom/i :.times.icon)))
          (if layout
            (render-layout form-instance (merge options computed-props {::form/nested? true}))
            (mapv
              (fn [attr] (render-attribute form-instance attr options))
              attributes)))
        )
      (dom/div :.ui.form
        (dom/div :.ui.top.attached.segment
          (dom/h3 :.ui.header (or (some-> form-instance comp/component-options ::form/title i18n/tr-unsafe) (tr "Edit"))))
        (dom/div :.ui.attached.segment
          (if layout
            (render-layout form-instance (merge options computed-props {::form/nested? true}))
            (mapv
              (fn [attr] (render-attribute form-instance attr options))
              attributes)))
        (dom/div :.ui.bottom.attached.segment
          (dom/button :.ui.secondary.button {:disabled (not dirty?)
                                             :onClick  (fn [] (form/undo-all! form-instance))} (tr "Undo"))
          (dom/button :.ui.secondary.button {:onClick (fn [] (form/cancel! form-instance))} (tr "Cancel"))
          (dom/button :.ui.primary.button {:disabled (not dirty?)
                                           :onClick  (fn [] (form/save! form-instance))} (tr "Save")))))))

