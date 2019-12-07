(ns com.example.model.firm
  (:refer-clojure :exclude [name])
  (:require
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The concept of "ownership" can be modeled in Datomic using a single attribute
;; that can be placed on entities that refers to some central thing (firm in this
;; example).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defattr id ::id :uuid
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

(defattr name ::name :string
  {::datomic/schema     :production

   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ;; In the Datomic case it is sufficient to indicate identity fields as a way to
   ;; indicate which entities an attribute can appear on. From that we can derive
   ;; a number of things like schema save validation (does that attribute belong on an entity with
   ;; the given ID) to optimized Pathom resolvers that can pull related attributes in a single
   ;; query. A similar mechanism is trivial for SQL as well `::sql/table "account"`.
   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
   ::datomic/entity-ids #{::id}

   ::attr/required?     true
   ::auth/authority     :local})

(def attributes [id name])

