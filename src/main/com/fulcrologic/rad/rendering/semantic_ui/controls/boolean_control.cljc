(ns com.fulcrologic.rad.rendering.semantic-ui.controls.boolean-control
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.report :as report]
    [taoensso.timbre :as log]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])))

(defsc BooleanControl [_ {:keys [report-instance control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [{:keys [:com.fulcrologic.rad.control/controls]} (comp/component-options report-instance)
        props (comp/props report-instance)
        {:keys [label onChange disabled? visible?] :as control} (get controls control-key)]
    (when control
      (let [label     (or (?! label report-instance))
            disabled? (?! disabled? report-instance)
            visible?  (or (nil? visible?) (?! visible? report-instance))
            value     (get-in props [:ui/parameters control-key])]
        (when visible?
          (dom/div :.ui.toggle.checkbox {:key (str control-key)}
            (dom/input {:type     "checkbox"
                        :readOnly (boolean disabled?)
                        :onChange (fn [_]
                                    (report/set-parameter! report-instance control-key (not value))
                                    (when onChange
                                      (onChange report-instance (not value))))
                        :checked  (boolean value)})
            (dom/label label)))))))

(def render-control (comp/factory BooleanControl {:keyfn :control-key}))
