(ns com.fulcrologic.rad.picker-options
  "Utilities to help support entity/enumeration pickers in the UI, along with loading/normalizing/caching the options.

  Pickers are commonly used for things like autocomplete fields and (cascading) dropdowns where the list of options must be loaded dynamically
  due to some event in the UI."
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

(defn load-picker-options!
  "Load picker options based on raw picker options"
  ([app-ish component-class props picker-options] (load-picker-options! app-ish component-class props picker-options {}))
  ([app-ish component-class props picker-options load-options]
   (let [{::app/keys [state-atom] :as app} (comp/any->app app-ish)
         {::keys [remote query-key query-component cache-key options-xform cache-time-ms query-parameters]} picker-options
         params        (or (?! query-parameters app component-class props) {})
         cache-time-ms (or cache-time-ms 100)
         state-map     @state-atom
         cache-key     (or (?! cache-key component-class props) query-key)
         time-path     [::options-cache cache-key :cached-at]
         target-path   [::options-cache cache-key :query-result]
         options-path  [::options-cache cache-key :options]
         now           (inst-ms (dt/now))
         cached-at     (get-in state-map time-path 0)
         reload?       (> (- now cached-at) cache-time-ms)]
     (when-not query-key
       (log/error "Cannot load picker options because there is no query-key."))
     (when-not query-component
       (log/warn "Cannot load picker options because ::query-component is missing."))
     (swap! state-atom assoc-in time-path now)
     (when reload?
       (df/load! app-ish query-key query-component
         (cond-> (merge load-options
                   {:target      target-path
                    :params      params
                    :post-action (fn [{:keys [state result]}]
                                   (let [query-result (get-in @state target-path)
                                         raw-result   (get-in result [:body query-key])
                                         options      (vec
                                                        (cond-> query-result
                                                          options-xform (options-xform raw-result)))]
                                     (fns/swap!-> state
                                       (assoc-in options-path options))))})
           remote (assoc :remote remote)))))))

(defn load-options!
  "Load picker options into the options cache for a form field."
  ([app-ish form-class props attribute] (load-options! app-ish form-class props attribute {}))
  ([app-ish form-class props {:com.fulcrologic.rad.attributes/keys [qualified-key] :as attribute} load-options]
   (let [{:com.fulcrologic.rad.form/keys [field-options]} (comp/component-options form-class)
         field-options  (get field-options qualified-key)
         picker-options (merge attribute field-options)]
     (load-picker-options! app-ish form-class props picker-options load-options))))

(def remote
  "The keyword name of the remote that the picker options are loaded from. Defaults to :remote."
  ::remote)

(def query-key
  "The top-level query keyword to use when pulling the options."
  ::query-key)

(def query-component
  "A Fulcro defsc with the subquery to use for pulling options."
  ::query-component)

(def query-parameters
  "A map of query parameters to include in the option load, or a `(fn [app cls props] map?)` that can generate those
   props on demand."
  ::query-parameters)

(def cache-key
  "A keyword or `(fn [cls props] keyword?)` under which the normalized picker options will be saved."
  ::cache-key)

(def options-xform
  "A `(fn [normalized-result raw-result] [{:text t :value v} ...])`. This generates the options to show the user. If
  not provided then it is assumed that the query result itself is a vector of these text/value pair maps."
  ::options-xform)

(def cache-time-ms
  "How long the options can be cached. This allows multiple uses of the same options load to be used over time. Caching
  is done under ::cache-key."
  ::cache-time-ms)