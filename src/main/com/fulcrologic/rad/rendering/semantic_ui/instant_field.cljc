(ns com.fulcrologic.rad.rendering.semantic-ui.instant-field
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs [com.fulcrologic.fulcro.dom :refer [div label input]]
       :clj  [com.fulcrologic.fulcro.dom-server :refer [div label input]])
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]))

(def ui-datetime-input
  (comp/factory (inputs/StringBufferedInput ::DateTimeInput
                  {:model->string (fn [tm] (datetime/inst->html-datetime-string "America/Los_Angeles" tm))
                   :string->model (fn [s] (datetime/html-datetime-string->inst "America/Los_Angeles" s))})))

(def render-field (render-field-factory ui-datetime-input))

