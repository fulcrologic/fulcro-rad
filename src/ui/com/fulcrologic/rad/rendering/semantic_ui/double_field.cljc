(ns com.fulcrologic.rad.rendering.semantic-ui.double-field
  (:require
    [clojure.string :as str]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.inputs :refer [StringBufferedInput]]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]))

(defn to-numeric [s]
  #?(:clj
     (try
       (Double/parseDouble s)
       (catch Exception _ nil))
     :cljs
     (let [n (js/parseFloat s)]
       (when-not (js/isNaN n) n))))

(let [digits (into #{"." "-"} (map str) (range 10))]
  (defn just-decimal
    "Returns `s` with all non-digits stripped."
    [s]
    (str/join (filter digits (seq s)))))

(def ui-double-input
  "An integer input. Can be used like `dom/input` but onChange and onBlur handlers will be passed an int instead of
  a raw react event, and you should supply an int for `:value` instead of a string.  You may set the `:type` to text
  or number depending on how you want the control to display, even though the model value is always an int or nil.
  All other attributes passed in props are passed through to the contained `dom/input`."
  (comp/factory (StringBufferedInput ::DoubleInput {:model->string str
                                                    :string->model to-numeric
                                                    :string-filter just-decimal})))

(def render-field (render-field-factory ui-double-input))
