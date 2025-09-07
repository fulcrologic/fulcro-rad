(ns com.fulcrologic.rad.rendering.semantic-ui.instant-field
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    #?(:cljs [com.fulcrologic.fulcro.dom :refer [div label input]]
       :clj  [com.fulcrologic.fulcro.dom-server :refer [div label input]])
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]))

(def ui-datetime-input
  (comp/factory (inputs/StringBufferedInput ::DateTimeInput
                  {:model->string (fn [tm]
                                    (if tm
                                      (datetime/inst->html-datetime-string tm)
                                      ""))
                   :string->model (fn [s] (some-> s (datetime/html-datetime-string->inst)))})))

(def ui-date-noon-input
  (comp/factory (inputs/StringBufferedInput ::DateTimeInput
                  {:model->string (fn [tm]
                                    (if tm
                                      (str/replace (datetime/inst->html-datetime-string tm) #"T.*$" "")
                                      ""))
                   :string->model (fn [s] (some-> s (str "T12:00") (datetime/html-datetime-string->inst)))})))

(def render-field
  "Uses current timezone and gathers date/time."
  (render-field-factory {:type "datetime-local"} ui-datetime-input))
(def render-date-at-noon-field
  "Uses current timezone and gathers a local date but saves it as an instant at noon on that date."
  (render-field-factory {:type "date"} ui-date-noon-input))

