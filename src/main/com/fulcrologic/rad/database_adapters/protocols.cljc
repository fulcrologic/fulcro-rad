(ns com.fulcrologic.rad.database-adapters.protocols)

(defprotocol DBAdapter
  (get-by-ids [this id-attr ids desired-output]
    "Run a query to find multiple entities that have the given id-attr, returning the desired output.")
  (save-form [this env params] "Save a form diff"))


