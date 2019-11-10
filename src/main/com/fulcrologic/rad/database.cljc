(ns com.fulcrologic.rad.database
  "Tools for defining databases"
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>def >defn => | ?]]))

(>def ::id (s/with-gen keyword? #(s/gen #{:production :authorization})))
(>def ::variant (s/with-gen keyword? #(s/gen #{:postgresql :mysql :h2})))
(>def ::migration-generator ifn?)
(>def ::url string?)

(>def ::definition (s/keys :req [::id
                                 ::migration-generator]
                     :opt [::variant
                           ::url]))

(>defn generate-datomic-migration
  "Generate a migration that can take the database from the old-schema
  to a new schema in `proposed-schema`.

  Returns the migration, or an error indicating that the suggested migration
  will break things."
  [db-def old-schema proposed-schema]
  [::definition map? map? => map?]
  {})

(>defn generate-sql-migration
  "Generate a migration that can take the database from the old-schema
  to a new schema in `proposed-schema`.

  Returns the migration, or an error indicating that the suggested migration
  will break things."
  [db-def old-schema proposed-schema]
  [::definition map? map? => map?]
  {})

(>defn datomic-database
  "Returns a database definition."
  [id]
  [::id => ::definition]
  {::id                  id
   ::migration-generator generate-datomic-migration})

(>defn sql-database
  "Returns a database definition."
  [id variant]
  [::id ::variant => ::definition]
  {::id                  id
   ::variant             variant
   ::migration-generator generate-sql-migration})
