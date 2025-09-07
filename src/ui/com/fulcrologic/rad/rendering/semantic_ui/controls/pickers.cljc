(ns com.fulcrologic.rad.rendering.semantic-ui.controls.pickers
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.rad.picker-options :as po]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])))

(defsc SimplePicker [_ {:keys [instance control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)
   :componentDidMount     (fn [this]
                            (let [{:keys [instance control-key] :as props} (comp/props this)
                                  controls (control/component-controls instance)
                                  {::po/keys [query-key] :as picker-options} (get controls control-key)]
                              (when query-key
                                (po/load-picker-options! instance (comp/react-type instance) props picker-options))))}
  (let [controls (control/component-controls instance)
        {:keys [label onChange disabled? visible? action placeholder options user-props] :as control} (get controls control-key)
        options  (or (?! options instance)
                     (po/current-picker-options instance control))]
    (when control
      (let [label       (or (?! label instance))
            disabled?   (?! disabled? instance)
            placeholder (?! placeholder instance)
            visible?    (or (nil? visible?) (?! visible? instance))
            value       (control/current-value instance control-key)]
        (when visible?
          (dom/div :.ui.field {:key (str control-key)}
            (dom/label label)
            (ui-wrapped-dropdown (merge
                                   user-props
                                   {:disabled    disabled?
                                    :placeholder (str placeholder)
                                    :options     options
                                    :value       value
                                    :onChange    (fn [v]
                                                   (control/set-parameter! instance control-key v)
                                                   (binding [rc/*after-render* true]
                                                     (when onChange
                                                       (onChange instance v))
                                                     (when action
                                                       (action instance))))}))))))))

(def render-control (comp/factory SimplePicker {:keyfn :control-key}))
