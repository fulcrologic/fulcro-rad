(ns com.fulcrologic.rad.rendering.semantic-ui.boolean-input
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.rad.report :as report]
    [taoensso.timbre :as log]
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom])))

(defmethod report/render-parameter-input :boolean [this k]
  (log/info "Rendering boolean input")
  (let [value (get (comp/props this) k)]
    (dom/input {:type     "checkbox"
                :onChange #(m/set-value! this k (not value))
                :value    (boolean value)})))
