(ns com.example.schema
  (:require
    [com.fulcrologic.rad.database :as database]
    [com.example.model.account :as account]))

(def schema
  {::database/definition (database/sql-database :production :postgresql)
   ::database/entities   [account/entity]})

