(ns com.fulcrologic.rad.rendering.semantic-ui.controls.text-input
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad.options-util :refer [?! debounce]]
    [com.fulcrologic.rad.report :as report]
    [taoensso.timbre :as log]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])))

(defsc TextControl [this {:keys [report-instance control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [{:keys [:com.fulcrologic.rad.control/controls]} (comp/component-options report-instance)
        props (comp/props report-instance)
        {:keys [label onChange icon placeholder disabled? visible?] :as control} (get controls control-key)]
    (when control
      (let [label       (?! label report-instance)
            disabled?   (?! disabled? report-instance)
            placeholder (?! placeholder)
            visible?    (or (nil? visible?) (?! visible? report-instance))
            chg!        #(report/set-parameter! report-instance control-key (evt/target-value %))
            run!        (fn [evt] (let [v (evt/target-value evt)]
                                    (when onChange (onChange report-instance v))))
            value       (get-in props [:ui/parameters control-key])]
        (when visible?
          (dom/div :.ui.field {:key (str control-key)}
            (dom/label label)
            (if icon
              (dom/div :.ui.icon.input
                (dom/i {:className (str icon " icon")})
                (dom/input {:readOnly    (boolean disabled?)
                            :placeholder (str placeholder)
                            :onChange    chg!
                            :onBlur      run!
                            :onKeyDown   (fn [evt] (when (evt/enter? evt) (run! evt)))
                            :value       (str value)}))
              (dom/input {:readOnly    (boolean disabled?)
                          :placeholder (str placeholder)
                          :onChange    chg!
                          :onBlur      run!
                          :onKeyDown   (fn [evt] (when (evt/enter? evt) (run! evt)))
                          :value       (str value)}))))))))

(def render-control (comp/factory TextControl {:keyfn :control-key}))
