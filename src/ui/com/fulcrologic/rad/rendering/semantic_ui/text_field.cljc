(ns com.fulcrologic.rad.rendering.semantic-ui.text-field
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?(:cljs [com.fulcrologic.fulcro.dom :refer [div label input textarea]]
       :clj  [com.fulcrologic.fulcro.dom-server :refer [div label input textarea]])
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.rendering.semantic-ui.form-options :as sufo]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]))

(defn- with-handlers [type {:keys [value onChange onBlur] :as props}]
  (assoc props
    :value (or value "")
    :type type
    :onBlur (fn [evt]
              (when onBlur
                (onBlur (evt/target-value evt))))
    :onChange (fn [evt]
                (when onChange
                  (onChange (evt/target-value evt))))))

(defn- text-input [props] (input (with-handlers "text" props)))
(defn- password-input [{:keys [value onChange onBlur] :as props}] (input (with-handlers "password" props)))

(defsc ViewablePasswordField [this {:keys [value onChange onBlur] :as props}]
  {:initLocalState (fn [_] {:hidden? true})}
  (let [hidden? (comp/get-state this :hidden?)]
    (input (assoc props
             :value (if hidden? "*******" (or value ""))
             :type "text"
             :onBlur (fn [evt]
                       (comp/set-state! this {:hidden? true})
                       (when onBlur
                         (onBlur (evt/target-value evt))))
             :onFocus (fn [_] (comp/set-state! this {:hidden? false}))
             :onChange (fn [evt]
                         (when onChange
                           (onChange (evt/target-value evt))))))))

(def render-field (render-field-factory text-input))
(def render-password (render-field-factory password-input))
(def render-viewable-password (render-field-factory (comp/factory ViewablePasswordField)))

(defn render-dropdown [{::form/keys [form-instance] :as env} attribute]
  (let [{k           ::attr/qualified-key
         ::attr/keys [required?]} attribute
        values             (form/field-style-config env attribute :sorted-set/valid-values)
        input-props        (?! (form/field-style-config env attribute :input/props) env)
        options            (mapv (fn [v] {:text v :value v}) values)
        props              (comp/props form-instance)
        value              (and attribute (get props k))
        invalid?           (not (contains? values value))
        omit-label?        (form/omit-label? form-instance attribute)
        validation-message (when invalid? (form/validation-error-message env attribute))
        field-label        (form/field-label env attribute)
        top-class          (sufo/top-class form-instance attribute)
        read-only?         (form/read-only? form-instance attribute)]
    (div {:className (or top-class "ui field")
          :key       (str k)}
      (when-not omit-label?
        (label field-label (when invalid?
                             (if (string? validation-message)
                               (str " (" validation-message ")")
                               validation-message))))
      (ui-wrapped-dropdown
        (merge
          {:disabled  read-only?
           :options   options
           :clearable (not required?)
           :value     value
           :onChange  (fn [v] (form/input-changed! env k v))}
          input-props))
      (when (and omit-label? invalid?)
        (div nil validation-message)))))

(def render-multi-line
  (render-field-factory (fn [{:keys [value onChange onBlur] :as props}]
                          (textarea (assoc props
                                      :value (or value "")
                                      :onBlur (fn [evt]
                                                (when onBlur
                                                  (onBlur (evt/target-value evt))))
                                      :onChange (fn [evt]
                                                  (when onChange
                                                    (onChange (evt/target-value evt)))))))))
