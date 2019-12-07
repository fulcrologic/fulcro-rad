(ns com.example.model.entity
  (:require
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr firm ::firm :ref
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
   ::datomic/entity-ids #{:com.example.model.account/id :com.example.model.tag/id}
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

(def attributes [firm])
