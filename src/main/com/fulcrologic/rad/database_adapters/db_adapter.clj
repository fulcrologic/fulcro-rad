(ns com.fulcrologic.rad.database-adapters.db-adapter
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.database-adapters.protocols :as dbp :refer [DBAdapter]]
    [com.fulcrologic.guardrails.core :refer [>defn => >def ?]]
    [clojure.spec.alpha :as s]))

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
  "Run a query to find attributes of the `eql-query` that are co-located on the thing with the given id-attr."
  [dbadapter id-attribute ids eql-query]
  [any? ::attr/attribute (s/coll-of any?) (? vector?) => any?]
  (dbp/get-by-ids dbadapter id-attribute ids eql-query))

(defn save-form
  [dbadapter mutation-env params]
  ;; TODO: Tease apart. It is possible that attributes in the diff came in for more than one database.
  (dbp/save-form dbadapter mutation-env params))



