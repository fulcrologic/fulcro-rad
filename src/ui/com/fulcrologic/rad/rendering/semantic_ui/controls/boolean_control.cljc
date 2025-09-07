(ns com.fulcrologic.rad.rendering.semantic-ui.controls.boolean-control
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.rad.control :as control]))

(defsc BooleanControl [_ {:keys [instance control control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [controls (control/component-controls instance)
        {:keys [label style onChange disabled? visible? user-props label-top? toggle?]
         :or   {toggle? true} :as control} (or control (get controls control-key))]
    (let [toggle? (cond
                    (boolean? toggle?) toggle?
                    (= :toggle style) true
                    :else false)]
      (if control
        (when (or (nil? visible?) (?! visible? instance))
          (let [label     (or (?! label instance))
                disabled? (?! disabled? instance)
                value     (control/current-value instance control-key)
                inp-attr  (merge user-props
                            {:type     "checkbox"
                             :readOnly (boolean disabled?)
                             :onChange (fn [_]
                                         (control/set-parameter! instance control-key (not value))
                                         (when onChange
                                           (onChange instance (not value))))
                             :checked  (boolean value)})]
            (dom/div :.field {:key (str control-key)}
              (if label-top?
                (comp/fragment
                  (dom/label label)
                  (dom/div :.ui.fitted.checkbox {:key (str control-key) :classes [(when toggle? "toggle")]}
                    (dom/input inp-attr)
                    (dom/label "")))
                (comp/fragment
                  (dom/div :.ui.checkbox {:key (str control-key) :classes [(when toggle? "toggle")]}
                    (dom/input inp-attr)
                    (dom/label label)))))))
        (log/error "Could not find control definition for " control-key)))))

(def render-control (comp/factory BooleanControl {:keyfn :control-key}))
