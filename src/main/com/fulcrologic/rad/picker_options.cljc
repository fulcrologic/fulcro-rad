(ns com.fulcrologic.rad.picker-options
  "Utilities to help support entity/enumeration pickers in the UI, along with loading/normalizing/caching the options."
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]))

(defmutation transform-options
  "INTERNAL MUTATION. Do not use."
  [{:keys  [ref]
    ::keys [pick-one]}]
  (action [{:keys [state] :as env}]
    (let [{:options/keys [subquery]} pick-one
          result    (:ui/query-result
                      (fdn/db->tree [{:ui/query-result (comp/get-query subquery)}]
                        (get-in @state ref) @state))
          transform (get pick-one :options/transform)
          options   (if transform
                      (mapv transform result)
                      result)]
      (swap! state assoc-in (conj ref :ui/options) options))))

(defn load-options!
  "Load picker options into the options cache."
  ([app-ish form-class props attribute] (load-options! app-ish form-class props attribute {}))
  ([app-ish form-class props {:com.fulcrologic.rad.attributes/keys [qualified-key] :as attribute} load-options]
   (let [{::app/keys [state-atom] :as app} (comp/any->app app-ish)
         {:com.fulcrologic.rad.form/keys [field-options]} (comp/component-options form-class)
         field-options (get field-options qualified-key)
         {::keys [remote query-key query-component cache-key options-xform cache-time-ms query-parameters]} (merge attribute field-options)
         params        (or (?! query-parameters app form-class props) {})
         cache-time-ms (or cache-time-ms 100)
         state-map     @state-atom
         cache-key     (or cache-key query-key)
         time-path     [::options-cache cache-key :cached-at]
         target-path   [::options-cache cache-key :query-result]
         options-path  [::options-cache cache-key :options]
         now           (inst-ms (dt/now))
         cached-at     (get-in state-map time-path 0)
         reload?       (> (- now cached-at) cache-time-ms)]
     (when-not query-key
       (log/error "Ref attribute has a field style that is using picker options, but no ::picker-options/query-key!" qualified-key))
     (when-not query-component
       (log/warn "Ref attribute is using a picker, but no ::picker-options/query-component was supplied. This means options will not be normalized." qualified-key))
     ;; prevent a screen that has many of the same pickers on it from issuing more than one load per cache timeout
     (swap! state-atom assoc-in time-path now)
     (when reload?
       (df/load! app-ish query-key query-component
         (cond-> (merge load-options
                   {:target      target-path
                    :params      params
                    :post-action (fn [{:keys [state result] :as env}]
                                   (let [query-result (get-in @state target-path)
                                         raw-result   (get-in result [:body query-key])
                                         options      (vec
                                                        (cond-> query-result
                                                          options-xform (options-xform raw-result)))]
                                     (fns/swap!-> state
                                       (assoc-in options-path options))))})
           remote (assoc :remote remote)))))))
