(ns com.fulcrologic.rad.rendering.semantic-ui.decimal-field
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [clojure.string :as str]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(def ui-decimal-input
  (comp/factory (inputs/StringBufferedInput ::DecimalInput
                  {:model->string (fn [n] (math/numeric->str n))
                   :string->model (fn [s] (if (and (string? s)
                                                (or
                                                  (re-matches #"^-?\d+(\.\d*)?$" s)
                                                  (re-matches #"^-?\d*(\.\d+)$" s)))
                                            (math/numeric s)
                                            (math/zero)))
                   :string-filter (fn [s] (first (re-find #"^-?\d*(\.\d*)?" s)))})))

(def render-field (render-field-factory {} ui-decimal-input))
