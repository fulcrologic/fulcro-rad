(ns com.fulcrologic.rad.database-adapters.protocols)

(defprotocol DBAdapter
  ;; TODO: some kind of runtime schema validation support
  ;; TODO: Scheme for remembering where our migrations are vs where the schema is
  (get-by-ids [this entity id-attr ids desired-output]
    "Run a query to find multiple entities that have the given id-attr, returning the desired output.")
  (diff->migration [this old-schema new-schema] "Returns a migration that should work to apply the given changes to the database in question."))


