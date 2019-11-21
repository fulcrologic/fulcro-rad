(ns com.fulcrologic.rad.rendering.semantic-ui.report
  (:require
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]))

(defmethod report/render-layout :default [this]
  (let [{::report/keys [source-attribute BodyItem parameters]} (comp/component-options this)
        props   (comp/props this)
        rows    (get props source-attribute [])
        factory (comp/factory BodyItem)]
    (log/info "Rendering report layout")
    (dom/div
      (dom/div :.ui.top.attached.segment
        (dom/h3 :.ui.header
          (or (some-> this comp/component-options ::report/title) "Report")
          (dom/button :.ui.tiny.right.floated.primary.button {:onClick (fn [] (report/run-report! this))} "Run!"))
        (dom/div :.ui.form
          (map-indexed
            (fn [idx k]
              (let [kind (get parameters k)]
                (dom/div :.ui.inline.field {:key idx}
                  (dom/label (-> k name str/capitalize))
                  (report/render-parameter-input this k))))
            (keys parameters))))
      (dom/div :.ui.attached.segment
        (dom/div :.ui.list
          (mapv factory rows))))))

