(ns com.fulcrologic.rad.rendering.semantic-ui.boolean-input
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.rad.report :as report]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom])))

(defn render-input [this k]
  (let [value (get (comp/props this) k)]
    (dom/input {:type     "checkbox"
                :onChange #(m/set-value! this k (not value))
                :checked  (boolean value)})))
