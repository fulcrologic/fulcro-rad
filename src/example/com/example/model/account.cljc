(ns com.example.model.account
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[datomic.api :as d]
         [com.wsscode.pathom.connect :as pc :refer [defmutation]]]
        :cljs
        [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.database-adapters.postgresql :as psql]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr :refer [new-attribute]]
    [com.fulcrologic.rad.authorization :as auth]
    [taoensso.timbre :as log]))

(def attributes
  [(new-attribute :user/email :string
     {::psql/schema    :auth
      ::psql/table     "user"
      ::attr/unique?   true
      ::attr/required? true})

   ;; Once logged in, this is placed in the session to identify the Datomic database
   ;; in which this user's data is stored
   (new-attribute :user/datomic-shard :keyword
     {::psql/schema    :auth
      ::psql/table     "user"
      ::attr/required? true})

   (new-attribute :user/password :password
     {::psql/schema    :auth
      ::psql/table     "user"
      ::attr/required? true})

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; The concept of "ownership" can be modeled in Datomic using a single attribute
   ;; that can be placed on entities that refers to some central thing (firm in this
   ;; example).
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   (new-attribute :firm/id :uuid
     {::attr/identity? true
      ::datomic/schema :production
      ::attr/required? true

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; The auth system must at least name "which provider" must be authenticated
      ;; against before allowing access to some property. The resolved authorization
      ;; data would then appear in the `env` of various lambdas for doing additional
      ;; work like determining read/write/execute permissions. Again, this is easily
      ;; extensible as plug-ins that affect everything from the Pathom parser to
      ;; generalizations for UI interactions on the client. We're simply naming the "thing" that should be
      ;; used to gain an identity for the user in the context of some (sub)set of
      ;; attributes.
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ::auth/authority :local})

   (new-attribute :firm/name :string
     {::datomic/schema     :production

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; In the Datomic case it is sufficient to indicate identity fields as a way to
      ;; indicate which entities an attribute can appear on. From that we can derive
      ;; a number of things like schema save validation (does that attribute belong on an entity with
      ;; the given ID) to optimized Pathom resolvers that can pull related attributes in a single
      ;; query. A similar mechanism is trivial for SQL as well `::sql/table "account"`.
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ::datomic/entity-ids #{:firm/id}

      ::attr/required?     true
      ::auth/authority     :local})

   (new-attribute :entity/firm :ref
     {::attr/target        :firm/id
      ::datomic/schema     :production

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; This attribute can live on any entity identified by this set.
      ;; Note that a db-specific thing like this can generate resolvers that can be ambiguous in
      ;; some contexts (you can flatten things so that more than one ID might be in context). It makes
      ;; sense that the database-centric code be leveraged to generate resolvers so that these facets
      ;; can be handled in a db-centric manner, with the addition of db-centric props on the attribute
      ;; to supply any necessary extra information.  This points to an interesting facet of
      ;; attributes: They may be resolved from multiple sources (more than one resolver
      ;; might be generated for a single defattr). Overrides for this are simply db-centric options
      ;; like `::datomic/generate-resolvers? false` on an attribute, followed by manual resolvers
      ;; defined elsewhere in the source.
      ::datomic/entity-ids #{::id :com.example.model.tag/id}
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; Writing attributes like an ownership ref this pose a challenge since they can be resolved from
      ;; multiple entities. If we limit forms so that they have to edit a *particular* entity
      ;; at each layer (folding not allowed), which is required for other reasons, then
      ;; the save of a form will have exactly one incoming ident per entity changed, which can resolve the correct
      ;; entity on which to store things; however, it may be the case that the *server* is
      ;; in control of an attribute's value (in this case the :entity/firm indicates
      ;; ownership and should be forced to the user session's firm). Thus, form write
      ;; overrides are a necessary mechanism for any db adapter.
      ;; Hooks like can probably be generalized as a function over the form delta itself:
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;::form/beforeWrite   (fn [env form-delta] #?(:clj (add-firm env form-delta)))

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; NOTE: This is overly broad in some cases. Is the attribute required on ALL entities it
      ;; can be on, or is it optional in some circumstances?
      ;; ::attr/required? as a boolean is too broad for all uses. ::form/required? true and ::datomic/required-on #{::id}
      ;; give context-sensitive meaning (which might even be co-located on another artifact, like a
      ;; `defsc-form`). Notice, however, that `::attr/required?` *can* have a well-defined
      ;; "default" meaning for all contexts (required on forms and all entities on which it can appear).
      ::attr/required?     true
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

      ::auth/authority     :local})

   (new-attribute ::id :uuid
     {::attr/identity? true
      ::datomic/schema :production
      ::attr/required? true
      ::auth/authority :local})

   (new-attribute ::email :string
     {::attr/unique?       true
      ::datomic/schema     :production
      ::datomic/entity-ids #{::id}
      ::attr/required?     true
      ::auth/authority     :local})

   ;; Save must put it in storage, and return a URL that is stored in the attribute.
   #_(new-attribute ::avatar :linked-binary
       {::auth/authority     :local
        ::datomic/schema     :production
        ::datomic/entity-ids #{::id}
        ::attr/save-binary   (fn [env java-io-file] "http://www.example.com/image.png")
        ::attr/binary-data   (fn [env url] (comment "returns binary data. optional."))
        ::attr/delete-binary (fn [env url] (comment "hook to delete binary. optional."))})

   (new-attribute ::active? :boolean
     {::auth/authority     :local
      ::datomic/schema     :production
      ::datomic/entity-ids #{::id}
      ::form/default-value true
      ::attr/required?     true})

   (new-attribute ::password :password
     {;; TODO: context sense to allow for owner to write
      ::auth/authority          :local
      ::datomic/schema          :production
      ::datomic/entity-ids      #{::id}

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; Permissions are typically only trusted at the server, but cases where we can
      ;; know permissions at the UI layer are also useful.
      ;;
      ;; The parser layer is certainly pluggable, so really any kind of read/write security
      ;; scheme could be enforced there (even the general saves have a well-known delta format
      ;; that could be checked before running the mutation).
      ;;
      ;; It probably makes sense to have some simple declarative CLJC version that can
      ;; give limited localized utility (i.e. knowing to hide a field for a user based on app state
      ;; in the client), but also the ability to declare things like a parser-level verification that
      ;; only happens on the server, a full-stack way for the client to ask what the permissions are, etc.
      ;; Each use-case can have a custom parameter if necessary. For example, if you want the client
      ;; to use a resolver property with parameters to query the server for the "current permissions" you could
      ;; certainly add such a system, have it pull that data on startup and put it in state, and then
      ;; examine that in the CLJS side of a lambda here.
      ;;
      ;; A mechanism that will work on the server for almost all cases is to include, in the `env`,
      ;; everything from the databases to the Ring request to the attribute name being checked. This
      ;; could then allow logic to figure out the permissions on an attribute for any circumstance.
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ::auth/permissions        (fn [env] #{})

      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ;; Things like this may or may not belong at the attr level.  Support for a
      ;; one-way hashed value is so common as to probably merit built-in support,
      ;; though the open maps make it possible to simply make this an extension point
      ;; for some kind of plugin.  The form augmentation of `::form/beforeWrite` shown
      ;; elsewhere is potentially a more appropriate generalization for this.
      ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
      ::attr/encrypt-iterations 100
      ::attr/required?          true})

   (new-attribute ::name :string
     {::auth/authority     :local
      ::datomic/schema     :production
      ::datomic/entity-ids #{::id}
      ::attr/required?     true})

   ;; In SQL engine default to one->many with target table holding back-ref
   (new-attribute ::addresses :ref
     {::attr/target              :com.example.model.address/id
      ::attr/cardinality         :many
      ::datomic/schema           :production
      ::datomic/intended-targets #{:com.example.model.address/id}
      ::datomic/component?       true
      ::datomic/entity-ids       #{::id}
      ::auth/authority           :local})

   (new-attribute ::tags :ref
     {::attr/target              :tag/id
      ::attr/cardinality         :many
      ::datomic/schema           :production
      ::datomic/intended-targets #{:com.example.model.tag/id}
      ::datomic/entity-ids       #{::id}
      ::auth/authority           :local})

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; If there is no database-specific representation of an attribute then one must
   ;; define the Pathom-specific mechanism for resolving it. We can (and may) hang write-level stuff
   ;; here, but Fulcro reified mutations are probably sufficient in many cases where virtual attributes
   ;; support any kind of "create/update".
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   (new-attribute ::all-accounts :ref
     {::attr/target    ::id
      ::auth/authority :local
      ::pc/output      [{::all-accounts [::id]}]
      ::pc/resolve     (fn [{:keys [query-params] :as env} input]
                         #?(:clj
                            (let [{:keys [db]} env
                                  ids (if (:ui/show-inactive? query-params)
                                        (d/q [:find '[?uuid ...]
                                              :where
                                              ['?dbid ::id '?uuid]] db)
                                        (d/q [:find '[?uuid ...]
                                              :where
                                              ['?dbid ::active? true]
                                              ['?dbid ::id '?uuid]] db))]
                              {::all-accounts (mapv (fn [id] {::id id}) ids)})))})])

#?(:clj
   (defmutation login [env {:keys [username password]}]
     {::pc/params #{:username :password}}
     (log/info "Attempt to login for " username)
     {::auth/provider  :local
      ::auth/real-user "Tony"})
   :cljs
   (defmutation login [params]
     (ok-action [{:keys [app result]}]
       (log/info "Login result" result)
       (auth/logged-in! app :local))
     (remote [env]
       (-> env
         (m/returning auth/Session)))))
