(ns com.example.schema
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.rad.database :as database]
    [com.example.model.account :as account]
    [com.example.model.employee :as employee]
    [com.fulcrologic.rad.schema :as schema]))

(def latest
  {::database/definition (database/sql-database :production :postgresql)
   ::schema/entities     []})

(def schema
  {::database/definition (database/sql-database :production :postgresql)
   ::schema/entities     [account/account
                          employee/employee]})

