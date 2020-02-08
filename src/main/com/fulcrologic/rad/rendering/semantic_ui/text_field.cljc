(ns com.fulcrologic.rad.rendering.semantic-ui.text-field
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :refer [input]]
       :clj  [com.fulcrologic.fulcro.dom-server :refer [input]])
    [com.fulcrologic.fulcro.dom.events :as evt]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]))

(defn- text-input [{:keys [value onChange onBlur]}]
  (input {:value    (or value "")
          :type     "text"
          :onBlur   (fn [evt]
                      (when onBlur
                        (onBlur (evt/target-value evt))))
          :onChange (fn [evt]
                      (when onChange
                        (onChange (evt/target-value evt))))}))

(def render-field (render-field-factory text-input))

