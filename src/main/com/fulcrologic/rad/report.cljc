(ns com.fulcrologic.rad.report
  (:require
    [com.fulcrologic.rad.controller :as controller :refer [io-complete!]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]))

(defn load-report! [app TargetReportClass {::controller/keys [id]
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
         :target      path}))))

(defstatemachine form-machine
  {::uism/actors
   #{:actor/report}

   ::uism/aliases
   {:new?                 [:actor/form :ui/new?]
    :confirmation-message [:actor/form :ui/confirmation-message]}

   ::uism/states
   {:initial
    {::uism/hander (fn [env]
                     )}

    :state/editing
    {::uism/events
     {:event/attribute-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                 ;; NOTE: value at this layer is ALWAYS typed to the attribute.
                                                 ;; The rendering layer is responsible for converting the value to/from
                                                 ;; the representation needed by the UI component (e.g. string)
                                                 (let [{:keys       [value]
                                                        ::attr/keys [qualified-key]} event-data
                                                       form-ident     (uism/actor->ident env :actor/form)
                                                       path           (when (and form-ident qualified-key)
                                                                        (conj form-ident qualified-key))
                                                       ;; TODO: Decide when to properly set the field to marked
                                                       mark-complete? true]
                                                   (when-not path
                                                     (log/error "Unable to record attribute change. Path cannot be calculated."))
                                                   (cond-> env
                                                     mark-complete? (uism/apply-action fs/mark-complete* form-ident qualified-key)
                                                     path (uism/apply-action assoc-in path value))))}
      :event/will-leave        {::uism/handler (fn [env]
                                                 ;; TODO: Handle the controller asking if it is OK to abort this edit
                                                 env)}
      ;; TODO: event for leaving to GC form state
      :event/blur              {::uism/handler (fn [env] env)}
      :event/save              {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                 (let [form-class   (uism/actor-class env :actor/form)
                                                       data-to-save (calc-diff env)
                                                       params       (merge event-data data-to-save)]
                                                   (-> env
                                                     (uism/trigger-remote-mutation :actor/form `save-form
                                                       {::uism/error-event :event/save-failed
                                                        :params            params
                                                        ;; TODO: Make return optional?
                                                        ::m/returning      form-class
                                                        ::uism/ok-event    :event/saved}))))}
      :event/saved             {::uism/handler (fn [env]
                                                 (let [form-ident (uism/actor->ident env :actor/form)]
                                                   (-> env
                                                     (uism/apply-action fs/entity->pristine* form-ident))))}
      :event/reset             {::uism/handler (fn [env]
                                                 (let [form-ident (uism/actor->ident env :actor/form)]
                                                   (uism/apply-action env fs/pristine->entity* form-ident)))}
      :event/cancel            {::uism/handler (fn [{::uism/keys [fulcro-app] :as env}]
                                                 )}}}}})
