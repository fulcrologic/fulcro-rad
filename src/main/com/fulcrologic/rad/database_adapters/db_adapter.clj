(ns com.fulcrologic.rad.database-adapters.db-adapter
  (:require
    [com.fulcrologic.rad.schema :as schema]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.database-adapters.protocols :as dbp :refer [DBAdapter]]
    [com.fulcrologic.guardrails.core :refer [>defn => >def ?]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The long-term goal is for the developer (and RAD) to be able to rely on attribute
;; declarations in the *current source* as the source of truth for the schema.
;; A major production pain is to have to construct code artifacts based on reading
;; a series of migrations from the beginning of time (useless) or having to go
;; physically query the database to see what is there. We want the code declarations
;; to be completely authoritative.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(>def ::adapter (fn [v] (satisfies? dbp/DBAdapter v)))
(>def ::adapters (s/map-of ::db/id ::adapter))

(defn get-by-ids
  "Run a query to find an entity that has the given id-attr, returning the desired output."
  [dbadapter entity-definition id-attribute ids eql-query]
  [any? ::entity/entity ::attr/attribute (s/coll-of any?) (? vector?) => any?]
  (dbp/get-by-ids dbadapter entity-definition id-attribute ids eql-query))

(>defn diff->migration
  "Convert a schema diff to a migration using the specified db adapter."
  [dbadapter old-schema new-schema]
  [::adapter ::schema/schema ::schema/schema => any?]
  (dbp/diff->migration dbadapter old-schema new-schema))

(defn save-form
  [mutation-env params]
  ;; TODO: Tease apart. It is possible that attributes in the diff came in for more than one database.
  (log/info "TODO: Save" params))



