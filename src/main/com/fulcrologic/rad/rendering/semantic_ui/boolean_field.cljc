(ns com.fulcrologic.rad.rendering.semantic-ui.boolean-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.dom.events :as evt]))

(defn render-field [this attribute props]
  (let [k          (::attr/qualified-key attribute)
        {::form/keys [field-label]} attribute
        read-only? (form/read-only? this attribute)
        value      (get props k false)]
    (div :.ui.field {:key (str k)}
      (div :.ui.checkbox
        (input {:checked  value
                :type     "checkbox"
                :disabled (boolean read-only?)
                :onChange (fn [evt]
                            (let [v (not value)]
                              (form/input-blur! this k v)
                              (form/input-changed! this k v)))})
        (label (or field-label (some-> k name str/capitalize)))))))

