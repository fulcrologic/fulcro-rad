(ns com.fulcrologic.rad.rendering.semantic-ui.instant-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form :refer [render-field]]))

(defmethod render-field :inst [this k props]
  (let [attribute  (attr/key->attribute k)
        {::form/keys [field-label]} attribute
        read-only? (form/read-only? this attribute)
        value      (or (and attribute (get props k)) "")]
    (div :.ui.field {:key (str k)}
      (label (or field-label (some-> k name str/capitalize)))
      (if read-only?
        (div (str value))
        ;; FIXME: date time input handling, which needs coercion logic and probably comp-local-state buffering
        (div (str value))))))

