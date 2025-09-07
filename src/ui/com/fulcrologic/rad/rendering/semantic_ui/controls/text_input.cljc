(ns com.fulcrologic.rad.rendering.semantic-ui.controls.text-input
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.options-util :refer [?! debounce]]
    [taoensso.timbre :as log]
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])))

(defn- internal-store-name [control-key]
  (keyword (str 'com.fulcrologic.rad.rendering.semantic-ui.controls.text-input_ (namespace control-key))
    (name control-key)))

(defsc TextControl [this {:keys [instance control-key]}]
  {:shouldComponentUpdate (fn [_ _ _] true)}
  (let [controls (control/component-controls instance)
        props    (comp/props instance)
        {:keys [label onChange icon placeholder onIconClick disabled? visible? user-props] :as control} (get controls control-key)]
    (when control
      (let [label       (?! label instance)
            disabled?   (?! disabled? instance)
            placeholder (?! placeholder)
            visible?    (or (nil? visible?) (?! visible? instance))
            value       (control/current-value instance control-key)
            {:keys [last-sent-value]} (control/current-value instance (internal-store-name control-key))
            chg!        #(control/set-parameter! instance control-key (evt/target-value %))
            run!        (fn [run-if-unchanged? evt] (let [v                 (evt/target-value evt)
                                                          actually-changed? (not= v last-sent-value)]
                                                      (when (and onChange (or run-if-unchanged? actually-changed?))
                                                        (control/set-parameter! instance control-key v)
                                                        (control/set-parameter! instance
                                                          (internal-store-name control-key)
                                                          {:last-sent-value v})
                                                        ;; Change the URL parameter
                                                        (onChange instance v))))]
        (when visible?
          (let [inp (dom/input (merge user-props
                                 {:readOnly    (boolean disabled?)
                                  :placeholder (str placeholder)
                                  :onChange    chg!
                                  :onBlur      (partial run! false)
                                  :onKeyDown   (fn [evt] (when (evt/enter? evt) (run! true evt)))
                                  :value       (str value)}))]
            (dom/div :.ui.field {:key (str control-key)}
              (dom/label label)
              (if icon
                (dom/div :.ui.icon.input
                  {:onClick (fn [evt]
                              (when onIconClick
                                (onIconClick instance evt)))}
                  (dom/i {:className (str icon " icon")})
                  inp)
                inp))))))))

(def render-control (comp/factory TextControl {:keyfn :control-key}))
