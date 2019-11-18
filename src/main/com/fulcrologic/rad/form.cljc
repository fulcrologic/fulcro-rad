(ns com.fulcrologic.rad.form
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]))

(defn config [env] (uism/retrieve env :config))

(defn attr-value [env]
  [(-> env ::uism/event-data ::attr/attribute)
   (-> env ::uism/event-data :value)])

(defn set-attribute*
  "Mutation helper: Set the given attribute's value in app state."
  [state-map form attribute value])

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

(defn render-form [this props]
  (let [{::keys [attributes]} props]
    (
      )
    ))
