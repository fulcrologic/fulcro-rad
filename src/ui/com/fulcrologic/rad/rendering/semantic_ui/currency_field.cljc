(ns com.fulcrologic.rad.rendering.semantic-ui.currency-field
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [clojure.string :as str]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(def ui-currency-input
  (comp/factory (inputs/StringBufferedInput ::DecimalInput
                  {:model->string (fn [n]
                                    (math/numeric->currency-str n))
                   :string->model (fn [s]
                                    (math/numeric (str/replace s #"[$,]" "")))
                   :string-filter (fn [s] s)})))

(def render-field (render-field-factory {} ui-currency-input))
