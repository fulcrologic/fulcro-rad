(ns com.fulcrologic.rad.resolvers
  (:require
    [clojure.spec.alpha :as s]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.attributes :as attr]
    [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]))

(>defn entity-query
  [dbid entity id-attr]
  [::db/id ::entity/entity ::attr/attribute => (? (s/or
                                                    :many vector?
                                                    :one map?))]
  (fn [env input]
    (log/debug "Running entity query for" id-attr)
    (enc/if-let [dbadapter (get-in env [::dba/adapters dbid])
                 query     (or (get env :query) [(::attr/qualified-key id-attr)])]
      (when-let [ids (if (sequential? input)
                       (into #{} (keep #(id-attr %) input))
                       #{(id-attr input)})]
        (log/debug "Running" query "on entities with " id-attr ":" ids)
        (dba/get-by-ids dbadapter entity id-attr ids query))
      (log/error "Unable to complete query because the database adapter was missing."))))

(>defn id-resolver
  [database-id
   {::entity/keys [qualified-key attributes] :as entity}
   {id-key ::attr/qualified-key :as id-attr}]
  [::db/id ::entity/entity ::attr/attribute => ::pc/resolver]
  (let [outputs (attr/attributes->eql database-id attributes)]
    {::pc/sym     (symbol
                    (str (name database-id) "." (namespace qualified-key))
                    (str (name qualified-key) "-resolver"))
     ::pc/output  outputs
     ::pc/batch?  true
     ::pc/resolve (entity-query database-id entity id-attr)
     ::pc/input   #{id-key}}))

(>defn entity->resolvers
  "Convert a given entity into the resolvers for the entity itself (accessible from unique identities)
   as well as any virtual attributes."
  [database-id {::entity/keys [qualified-key attributes] :as entity}]
  [::db/id ::entity/entity => (s/every ::pc/resolver)]
  (let [identity-attrs      (filter (fn [{::attr/keys [unique]}] (= :identity unique)) attributes)
        entity-resolvers    (map (fn [a] (id-resolver database-id entity a)) identity-attrs)
        attribute-resolvers []]
    (concat entity-resolvers attribute-resolvers)))

