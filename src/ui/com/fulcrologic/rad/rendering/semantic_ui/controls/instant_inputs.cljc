(ns com.fulcrologic.rad.rendering.semantic-ui.controls.instant-inputs
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.rendering.semantic-ui.controls.control :as control]
    [cljc.java-time.local-time :as lt]
    [com.fulcrologic.fulcro.dom.events :as evt]
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.local-date :as ld]))

(defn ui-date-instant-input [{::keys [default-local-time]} {:keys [value onChange local-time] :as props}]
  (let [value      (if (nil? value) "" (dt/inst->html-date value))
        local-time (or local-time default-local-time)]
    (dom/input
      (merge props
        {:value    value
         :type     "date"
         :onChange (fn [evt]
                     (when onChange
                       (let [date-string (evt/target-value evt)
                             instant     (dt/html-date->inst date-string local-time)]
                         (onChange instant))))}))))

(defn ui-ending-date-instant-input
  "Display the date the user selects, but control a value that is midnight on the next date. Used for generating ending
  instants that can be used for a proper non-inclusive end date."
  [_ {:keys [value onChange] :as props}]
  (let [value (if (nil? value)
                ""
                (-> value
                  dt/inst->local-datetime
                  (ldt/minus-days 1)
                  ldt/to-local-date
                  dt/local-date->html-date-string))]
    (dom/input
      (merge props
        {:value    value
         :type     "date"
         :onChange (fn [evt]
                     (when onChange
                       (onChange (some-> (evt/target-value evt)
                                   (dt/html-date-string->local-date)
                                   (ld/plus-days 1)
                                   (ld/at-time lt/midnight)
                                   (dt/local-datetime->inst)))))}))))

(defn ui-date-time-instant-input [_ {:keys [disabled? value onChange] :as props}]
  (let [value (if (nil? value) "" (dt/inst->html-datetime-string value))]
    (dom/input
      (merge props
        (cond->
          {:value    value
           :type     "date"
           :onChange (fn [evt]
                       (when onChange
                         (let [date-time-string (evt/target-value evt)
                               instant          (dt/html-datetime-string->inst date-time-string)]
                           (onChange instant))))}
          disabled? (assoc :readOnly true))))))

(defn date-time-control [render-env]
  (control/ui-control (assoc render-env :input-factory ui-date-time-instant-input)))

(defn midnight-on-date-control [render-env]
  (control/ui-control (assoc render-env
                        :input-factory ui-date-instant-input
                        ::default-local-time lt/midnight)))

(defn midnight-next-date-control [render-env]
  (control/ui-control (assoc render-env
                        :input-factory ui-ending-date-instant-input)))

(defn date-at-noon-control [render-env]
  (control/ui-control (assoc render-env
                        ::default-local-time lt/noon
                        :input-factory ui-date-instant-input)))

