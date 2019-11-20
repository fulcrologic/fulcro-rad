(ns com.fulcrologic.rad.form
  (:require
    [com.fulcrologic.rad :as rad]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.rendering.data-field :refer [render-field]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.controller :as controller]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [taoensso.timbre :as log]))

(defn config [env] (uism/retrieve env :config))

(defn attr-value [env]
  [(-> env ::uism/event-data ::attr/attribute)
   (-> env ::uism/event-data :value)])

(defn set-attribute*
  "Mutation helper: Set the given attribute's value in app state."
  [state-map form attribute value])

(defn render-form [this props]
  (let [{::attr/keys [attributes]} (comp/component-options this)]
    (mapv
      (fn [attribute]
        (render-field this attribute props))
      attributes)))

(defn- start-edit! [app TargetClass {machine-id ::id
                                     ::rad/keys [id target-route]}]
  (log/debug "START EDIT" (comp/component-name TargetClass))
  (let [id-key (some-> TargetClass (comp/ident {}) first)
        ;; TODO: Coercion from string IDs to type of ID field
        id     (new-uuid id)]
    (df/load! app [id-key id] TargetClass
      {:post-action (fn [{:keys [state]}]
                      (log/debug "Marking the form complete")
                      (fns/swap!-> state
                        (assoc-in [id-key id :ui/new?] false)
                        (fs/mark-complete* [id-key id]))
                      (controller/io-complete! app {::controller/id    machine-id
                                                    ::rad/target-route target-route}))})))

;; TODO: ID generation pluggable? Use tempids?  NOTE: The controller has to generate the ID because the incoming
;; route is already determined
(defn- start-create! [app TargetClass {machine-id ::id
                                       ::rad/keys [target-route tempid]}]
  (log/debug "START CREATE" (comp/component-name TargetClass))
  (let [id-key        (some-> TargetClass (comp/ident {}) first)
        ident         [id-key tempid]
        fields        (comp/component-options TargetClass ::attr/attributes)
        ;; TODO: Make sure there is one and only one unique identity key on the form
        initial-value (into {:ui/new? true}
                        (keep (fn [{::attr/keys [qualified-key default-value unique]}]
                                (cond
                                  (= unique :identity) [qualified-key tempid]
                                  default-value [qualified-key default-value])))
                        fields)
        filled-fields (keys initial-value)
        tx            (into []
                        (map (fn [k]
                               (fs/mark-complete! {:entity-ident ident
                                                   :field        k})))
                        filled-fields)]
    (merge/merge-component! app TargetClass initial-value)
    (when (seq tx)
      (log/debug "Marking fields with default values complete")
      (comp/transact! app tx))
    (controller/io-complete! app {::controller/id    machine-id
                                  ::rad/target-route target-route})))

(defstatemachine form-machine
  {::uism/actors
   #{:actor/form-root
     :actor/confirmation-dialog}

   ::uism/aliases
   {}

   ::uism/states
   {:initial
    {::uism/hander (fn [env]
                     (let [{::uism/keys [event-data]} env
                           config (::config event-data)]
                       (-> env
                         (uism/store :config config)
                         (uism/activate :state/idle))))}

    :state/idle
    {::uism/events
     {
      :event/create! {::uism/handler (fn [env]
                                       (let [{::uism/keys [asm-id]} env
                                             {:keys [form]} (config env)]
                                         (-> env
                                           (uism/trigger ::auth/auth :event/authorize!
                                             {::uism/asm-id asm-id
                                              :form         form})
                                           (uism/activate :state/authorizing))))}
      :event/load!   {::uism/handler (fn [env]
                                       (let [{::uism/keys [event-data]} env])
                                       )}
      :event/loaded  {::uism/handler (fn [env]
                                       )}
      :event/failed  {::uism/handler (fn [env]
                                       )}
      }}

    :state/authorizing
    {}

    :state/editing
    {::uism/events
     {
      :event/delete!           {::uism/handler (fn [env])}
      :event/attribute-changed {::uism/handler (fn [{::uism/keys [event-data] :as env}]
                                                 (let [{:keys       [form-ident value]
                                                        ::attr/keys [attribute]} event-data
                                                       {::attr/keys [qualified-key]} attribute
                                                       path (when (and form-ident qualified-key)
                                                              (conj form-ident qualified-key))]
                                                   (cond-> env
                                                     path (uism/apply-action assoc-in path value))))}
      :event/blur              {::uism/handler (fn [env]
                                                 )}
      }}

    }})

(defmethod controller/-start-io! ::rad/form
  [{::uism/keys [fulcro-app]} TargetClass {::rad/keys [target-route] :as options}]
  (log/info "Starting I/O processing for RAD Form" (comp/component-name TargetClass))
  (let [[_ action id] target-route
        form-machine-id [(first (comp/ident TargetClass {})) (new-uuid id)]
        event-data      (merge options {::action action})]
    (uism/begin! fulcro-app form-machine form-machine-id
      {:actor/form (uism/with-actor-class form-machine-id TargetClass)}
      event-data)))
