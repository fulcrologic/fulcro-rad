(ns com.fulcrologic.rad.rendering.semantic-ui.text-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [camel-snake-kebab.core :as csk]
    [com.fulcrologic.rad.rendering.data-field :as data-field :refer [render-field]]))

(defmethod render-field :text [this k props]
  (let [attribute (attr/key->attribute k)
        {::form/keys [field-label]} attribute
        asm-id    (comp/get-ident this)
        value     (or (and attribute (get props k)) "")]
    (div :.ui.field {:key (str k)}
      (label (or field-label (some-> k name str/capitalize)))
      (input {:value    value
              :onBlur   (fn [evt]
                          (uism/trigger! this asm-id :event/blur
                            {::attr/qualified-key k
                             :form-ident          (comp/get-ident this)
                             :value               (evt/target-value evt)}))
              :onChange (fn [evt]
                          (uism/trigger! this asm-id :event/attribute-changed
                            {::attr/qualified-key k
                             :form-ident          (comp/get-ident this)
                             :value               (evt/target-value evt)}))}))))

