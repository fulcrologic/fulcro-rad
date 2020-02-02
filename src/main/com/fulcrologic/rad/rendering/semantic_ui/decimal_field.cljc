(ns com.fulcrologic.rad.rendering.semantic-ui.decimal-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.inputs :as inputs]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(def ui-decimal-input
  (comp/factory (inputs/StringBufferedInput ::DecimalInput
                  {:model->string (fn [n] (if (math/numeric? n) (math/numeric->str n) ""))
                   :string->model (fn [s] (math/numeric s))
                   :string-filter (fn [s] (str/replace s #"[^\d.]" ""))})))

(defn render-field [{::form/keys [form-instance] :as env} attribute]
  (let [k          (::attr/qualified-key attribute)
        props      (comp/props form-instance)
        {::form/keys [field-label]} attribute
        read-only? (form/read-only? form-instance attribute)
        value      (or (and attribute (get props k)) (math/numeric "0"))]
    (div :.ui.field {:key (str k)}
      (label (or field-label (some-> k name str/capitalize)))
      (if read-only?
        (div (or (some-> value (math/numeric->str)) ""))
        (ui-decimal-input {:type     "number"
                           :value    value
                           :onBlur   (fn [v] (form/input-blur! env k v))
                           :onChange (fn [v] (form/input-changed! env k v))})))))

