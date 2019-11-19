(ns com.fulcrologic.rad.rendering.data-field
  (:require
    [taoensso.timbre :as log]))

(defmulti render-field (fn [this attribute props] (::type attribute)))

(defmethod render-field :default
  [_ attr _]
  (log/error "Attempt to render a field that did not have anything to dispatch to."
    "Did you remember to require the namespace that implements the field type:"
    (::type attr)))
