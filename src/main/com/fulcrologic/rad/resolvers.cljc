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
    [com.fulcrologic.rad.schema :as schema]
    [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]))

;; TODO: This is really CLJ stuff, meant for satisfying queries from the server. We also need a client-side
;; set of functions to generate resolvers for network dbs, like firebase, GraphQL, etc.

(>defn entity-query
  [dbid entity id-attr env input]
  [::db/id ::entity/entity ::attr/attribute map? (s/or :one map? :many sequential?)
   => (? (s/or :many vector? :one map?))]
  (enc/if-let [dbadapter (get-in env [::dba/adapters dbid])
               query     (or
                           (get env :com.wsscode.pathom.core/parent-query)
                           (get env ::default-query)
                           )
               ids       (if (sequential? input)
                           (into #{} (keep #(id-attr %) input))
                           #{(id-attr input)})]
    (do
      (log/info "Running" query "on entities with " id-attr ":" ids)
      (dba/get-by-ids dbadapter entity id-attr ids query))
    (do
      (log/info "Unable to complete query because the database adapter was missing.")
      nil)))

(>defn id-resolver
  [database-id
   {::entity/keys [qualified-key attributes] :as entity}
   {id-key ::attr/qualified-key :as id-attr}]
  [::db/id ::entity/entity ::attr/attribute => ::pc/resolver]
  (log/info "Building ID resolver for" qualified-key)
  (let [data-attributes (filter #(not= id-attr %) attributes)
        outputs         (attr/attributes->eql database-id data-attributes)]
    {::pc/sym     (symbol
                    (str (name database-id) "." (namespace qualified-key))
                    (str (name qualified-key) "-resolver"))
     ::pc/output  outputs
     ::pc/batch?  true
     ::pc/resolve (fn [env input] (entity-query database-id entity id-attr (assoc env ::default-query outputs) input))
     ::pc/input   #{id-key}}))

(defn just-pc-keys [m]
  (into {}
    (keep (fn [k]
            (when (or
                    (= (namespace k) "com.wsscode.pathom.connect")
                    (= (namespace k) "com.wsscode.pathom.core"))
              [k (get m k)])))
    (keys m)))

(>defn attribute-resolver
  [attr]
  [::attr/attribute => (? ::pc/resolver)]
  (log/info "Building attribute resolver for" (::attr/qualified-key attr))
  (enc/if-let [resolver (::attr/resolver attr)
               k        (::attr/qualified-key attr)
               output   [k]]
    (merge
      {::pc/output output}
      (just-pc-keys attr)
      {::pc/sym     (symbol (str k "-resolver"))
       ::pc/resolve resolver})
    (do
      (log/error "Virtual attribute " attr " is missing ::attr/resolver key.")
      nil)))

(>defn entity->resolvers
  "Convert a given entity into the resolvers for the entity itself (accessible from unique identities)
   as well as any virtual attributes."
  [database-id {::entity/keys [attributes] :as entity}]
  [::db/id ::entity/entity => (s/every ::pc/resolver)]
  (let [identity-attrs      (filter (fn [{::attr/keys [unique]}] (= :identity unique)) attributes)
        virtual-attrs       (remove ::db/id attributes)
        entity-resolvers    (keep (fn [a] (id-resolver database-id entity a)) identity-attrs)
        ;; TODO: Make this not suck
        pk                  (some-> identity-attrs first ::attr/qualified-key)
        attribute-resolvers (keep (fn [a] (attribute-resolver (assoc a ::pc/input #{pk}))) virtual-attrs)]
    (concat entity-resolvers attribute-resolvers)))

(>defn schema->resolvers
  [database-ids {::schema/keys [entities roots]}]
  [(s/every ::db/id) ::schema/schema => (s/every ::pc/resolver)]
  (let [database-ids (set database-ids)]
    (vec
      (concat
        (mapcat
          (fn [dbid]
            (mapcat (fn [entity] (entity->resolvers dbid entity)) entities))
          database-ids)
        (keep (fn [attr]
                (when (contains? database-ids (::db/id attr))
                  (attribute-resolver attr))) roots)))))
