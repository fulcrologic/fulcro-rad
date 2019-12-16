(ns com.fulcrologic.rad.rendering.semantic-ui.instant-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]))

(defn render-field [{::form/keys [form-instance] :as env} attribute]
  (let [k          (::attr/qualified-key attribute)
        props      (comp/props form-instance)
        {::form/keys [field-label]} attribute
        read-only? (form/read-only? form-instance attribute)
        value      (or (and attribute (get props k)) "")]
    (div :.ui.field {:key (str k)}
      (label (or field-label (some-> k name str/capitalize)))
      (if read-only?
        (div (str value))
        ;; FIXME: date time input handling, which needs coercion logic and probably comp-local-state buffering
        (div (str value))))))

