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
    [taoensso.timbre :as log]))

(defn render-to-many [form-instance {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [{::form/keys [ui can-delete-row? can-add-row? add-row-title]} (get subforms k)
        parent     (comp/props form-instance)
        items      (-> form-instance comp/props k)
        ui-factory (comp/computed-factory ui)
        {::form/keys [title] :as item-options} (comp/component-options ui)]
    (dom/div :.ui.segment
      (when title (dom/h3 title))
      (mapv
        (fn [props]
          (ui-factory props {::form/nested?         true
                             ::form/parent          form-instance
                             ::form/parent-relation k
                             ::form/can-delete?     (partial can-delete-row? parent)}))
        items)
      (when (can-add-row? parent)
        (dom/button :.ui.button
          {:onClick (fn [_]
                      (log/error "TODO: Implement add row")
                      #_(form/add-row! form-instance k))}
          (or (and add-row-title (i18n/tr-unsafe add-row-title)) (tr "Add")))))))

(defn render-to-one [form-instance {k ::attr/qualified-key :as attr} {::form/keys [subforms] :as options}]
  (let [ui-class   (get-in subforms [k ::form/ui])
        props      (-> form-instance comp/props k)
        ui-factory (comp/factory ui-class)]
    (dom/div :.ui.segment
      (ui-factory props))))

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

(defn render-layout [form-instance {:keys       [k->attribute]
                                    ::form/keys [layout] :as options}]
  (mapv
    (fn [row]
      (dom/div {:className (str (n->str (count row)) " fields")}
        (mapv (fn [col]
                (render-attribute form-instance (k->attribute col) options))
          row)))
    layout))

(defsc SemanticFormLayout [this props {::form/keys [form-instance parent parent-relation nested? can-delete?] :as computed-props}]
  {:initLocalState (fn [this]
                     (let [form-instance (comp/get-computed this :form-instance)
                           {::form/keys [attributes] :as options} (comp/component-options form-instance)
                           attribute-map (reduce
                                           (fn [m {::attr/keys [qualified-key] :as attr}]
                                             (assoc m qualified-key attr))
                                           {}
                                           attributes)]
                       (assoc options :k->attribute attribute-map)))}
  (let [{::form/keys [attributes layout] :as options} (comp/get-state this)
        dirty? (or (:ui/new? props) (fs/dirty? props))]
    (dom/div :.ui.form
      (dom/div :.ui.top.attached.segment
        (dom/h3 :.ui.header (or (some-> form-instance comp/component-options ::form/title i18n/tr-unsafe) (tr "Edit"))))
      (dom/div :.ui.attached.segment
        (if layout
          (render-layout form-instance options)
          (mapv
            (fn [attr] (render-attribute form-instance attr options))
            attributes)))
      (if nested?
        (when can-delete?
          (dom/div :.ui.bottom.attached.segment
            (dom/button :.ui.primary.button {:disabled (not (can-delete? props))
                                             :onClick  (fn []
                                                         (log/error "TODO: implement child delete")
                                                         #_(form/delete-child! parent parent-relation props))} (tr "Delete"))))
        (dom/div :.ui.bottom.attached.segment
          (dom/button :.ui.secondary.button {:disabled (not dirty?)
                                             :onClick  (fn [] (form/undo-all! form-instance))} (tr "Undo"))
          (dom/button :.ui.secondary.button {:onClick (fn [] (form/cancel! form-instance))} (tr "Cancel"))
          (dom/button :.ui.primary.button {:disabled (not dirty?)
                                           :onClick  (fn [] (form/save! form-instance))} (tr "Save")))))))

(defn ui-render-layout (comp/computed-factory SemanticFormLayout))
