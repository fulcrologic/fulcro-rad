(ns com.fulcrologic.rad.database-adapters.db-adapter
  (:require
    [com.fulcrologic.rad.schema :as schema]
    [com.fulcrologic.guardrails.core :refer [>defn => >def]]))

(defprotocol DBAdapter
  (-diff->migration [this old-schema new-schema] "Returns a migration that should work to apply the given changes to the database in question."))

(>def ::adapter (fn [v] (satisfies? DBAdapter v)))

(>defn diff->migration
  "Convert a schema diff to a migration using the specified db adapter."
  [dbadapter old-schema new-schema]
  [::adapter ::schema/schema ::schema/schema => any?]
  (str
    "BEGIN;\n"
    (-diff->migration dbadapter old-schema new-schema)
    "COMMIT;\n"))



