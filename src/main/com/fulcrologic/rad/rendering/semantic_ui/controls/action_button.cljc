(ns com.fulcrologic.rad.rendering.semantic-ui.controls.action-button
  (:require
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]))

(defsc ActionButton [_ {:keys [report-instance control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [{:keys [:com.fulcrologic.rad.control/controls]} (comp/component-options report-instance)
        props (comp/props report-instance)
        {:keys [label icon class action disabled? visible?] :as control} (get controls control-key)]
    (when control
      (let [label     (?! label report-instance)
            class     (?! class report-instance)
            loading?  (df/loading? (get-in props [df/marker-table (comp/get-ident report-instance)]))
            disabled? (or loading? (?! disabled? report-instance))
            visible?  (or (nil? visible?) (?! visible? report-instance))]
        (when visible?
          (dom/button :.ui.tiny.primary.button
            {:key      (str control-key)
             :classes  [(when class class)]
             :disabled (boolean disabled?)
             :onClick  (fn [] (when action (action report-instance control-key)))}
            (when icon (dom/i {:className (str icon " icon")}))
            (when label label)))))))

(def render-control (comp/factory ActionButton {:keyfn :control-key}))
