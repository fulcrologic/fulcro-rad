(ns com.fulcrologic.rad.rendering.semantic-ui.decimal-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [clojure.string :as str]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(def ui-decimal-input
  (comp/factory (inputs/StringBufferedInput ::DecimalInput
                  {:model->string (fn [n] (if (math/numeric? n) (math/numeric->str n) ""))
                   :string->model (fn [s] (math/numeric s))
                   :string-filter (fn [s] (str/replace s #"[^\d.]" ""))})))

(def render-field (render-field-factory ui-decimal-input))
