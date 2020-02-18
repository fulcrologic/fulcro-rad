(ns com.fulcrologic.rad.rendering.semantic-ui.boolean-input
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.rad.report :as report]
    [taoensso.timbre :as log]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])))

(defsc BooleanInput [_ {:keys [this k]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [value (get (comp/props this) k)
        label (or
                (comp/component-options this ::report/parameters k :label)
                (some-> k name str/capitalize))]
    (dom/div :.ui.toggle.checkbox {:key (str k)}
      (dom/input {:type     "checkbox"
                  :onChange #(report/set-parameter! this k (not value))
                  :checked  (boolean value)})
      (dom/label label))))

(let [ui-boolean-input (comp/factory BooleanInput)]
  (defn render-input [this k]
    (ui-boolean-input {:this this :k k})))
