(ns com.fulcrologic.rad.report
  (:require
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.controller :as controller :refer [io-complete!]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]))

(defn load-report! [app TargetReportClass {::controller/keys [id]
                                           ::keys            [parameters]
                                           ::rad/keys        [target-route] :as options}]
  (let [{::rad/keys [BodyItem source-attribute]} (comp/component-options TargetReportClass)
        path (conj (comp/get-ident TargetReportClass {}) source-attribute)]
    (log/info "Loading report" source-attribute
      (comp/component-name TargetReportClass)
      (comp/component-name BodyItem))
    (df/load! app source-attribute BodyItem
      (merge
        options
        {:post-action (fn [{:keys [app]}] (io-complete! app {::controller/id    id
                                                             ::rad/target-route target-route}))
         :params      parameters
         :target      path}))))

(defstatemachine form-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {}

   ::uism/states
   {:initial
    {::uism/hander (fn [env] env)}

    :state/gathering-parameters
    {::uism/events
     {:event/parameter-changed {::uism/handler (fn [env] env)}
      :event/submit            {::uism/target-state :state/loading
                                ::uism/handler      (fn [env] env)}}}

    :state/loading
    {::uism/events
     {:event/loaded {::uism/handler (fn [env] env)}
      :event/submit {::uism/target-state :state/loading
                     ::uism/handler      (fn [env] env)}}}

    }})
