(ns com.fulcrologic.rad.rendering.semantic-ui.controls.action-button
  (:require
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.semantic-ui-options :as suo]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.rad.control :as control]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]))

(defsc ActionButton [_ {:keys [instance control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [controls (control/component-controls instance)
        render   (suo/get-rendering-options instance suo/action-button-render)
        props    (comp/props instance)
        {:keys [label icon class htmlStyle action disabled? visible?] :as control} (get controls control-key)]
    (when control
      (let [label     (?! label instance)
            class     (?! class instance)
            loading?  (df/loading? (get-in props [df/marker-table (comp/get-ident instance)]))
            disabled? (or loading? (?! disabled? instance))
            visible?  (or (nil? visible?) (?! visible? instance))
            onClick   (fn [] (when action (action instance control-key)))]
        (when visible?
          (or
            (?! render instance (merge control
                                  {:key       control-key
                                   :label     label
                                   :class     class
                                   :onClick   onClick
                                   :disabled? disabled?
                                   :loading?  loading?}))
            (dom/button
              (cond-> {:key       (str control-key)
                       :className (or class "ui tiny primary button")
                       :disabled  (boolean disabled?)
                       :onClick   onClick}
                htmlStyle (assoc :style htmlStyle))
              (when icon (dom/i {:className (str icon " icon")}))
              (when label label))))))))

(def render-control (comp/factory ActionButton {:keyfn :control-key}))
