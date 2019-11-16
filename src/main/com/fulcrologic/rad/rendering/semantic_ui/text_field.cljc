(ns com.fulcrologic.rad.rendering.semantic-ui.text-field
  (:require
    #?(:cljs
       [com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
       :clj
       [com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]])
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.rendering.data-field :as data-field :refer [render-field]]))

(defmethod render-field :text [this {::attr/keys [attribute]
                                     ::uism/keys [asm-id]
                                     :as         props}]
  (let [{::attr/keys [field-label]} attribute
        value (or (and attribute (attribute props)) "")]
    (div :.ui.field
      (label field-label)
      (input {:value    value
              :onBlur   (fn [evt]
                          (uism/trigger! this asm-id :event/blur
                            {::attr/attribute attribute
                             :form-ident      (comp/get-ident this)
                             :value           (evt/target-value evt)}))
              :onChange (fn [evt]
                          (uism/trigger! this asm-id :event/attribute-changed
                            {::attr/attribute attribute
                             :form-ident      (comp/get-ident this)
                             :value           (evt/target-value evt)}))}))))

