(ns com.fulcrologic.rad.rendering.data-field
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [taoensso.timbre :as log]))

(def default-mapping
  {:string :text
   })

(defmulti render-field (fn [this k props]
                         (when-let [attr (attr/key->attribute k)]
                           (or
                             (::type attr)
                             (some-> attr ::attr/type default-mapping)))))

(defmethod render-field :default
  [_ attr _]
  (log/error "Attempt to render a field that did not have anything to dispatch to."
    "Did you remember to require the namespace that implements the field type:"
    attr))
