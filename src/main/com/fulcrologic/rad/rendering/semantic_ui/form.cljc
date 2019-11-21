(ns com.fulcrologic.rad.rendering.semantic-ui.form
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]))

(defmethod form/render-layout :default [this props]
  (let [{::attr/keys [attributes]} (comp/component-options this)
        dirty? (or (:ui/new? props) (fs/dirty? props))]
    (dom/div :.ui.form
      (dom/div :.ui.top.attached.segment
        (dom/h3 :.ui.header (or (some-> this comp/component-options ::form/title) "Edit")))
      (dom/div :.ui.attached.segment
        (mapv
          (fn [k]
            (form/render-field this k props))
          attributes))
      (dom/div :.ui.bottom.attached.segment
        (dom/button :.ui.secondary.button {:onClick (fn [] (form/undo-all! this))} "Undo")
        (dom/button :.ui.secondary.button {:onClick (fn [] (form/cancel! this))} "Cancel")
        (dom/button :.ui.primary.button {:disabled (not dirty?)
                                         :onClick  (fn [] (form/save! this))} "Save")))))

