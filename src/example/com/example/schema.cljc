(ns com.example.schema
  (:require
    [com.fulcrologic.rad.database :as database]
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.fulcrologic.rad.database-adapters.postgresql :as psql]
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

(comment
  (let [adapter (psql/->PostgreSQLAdapter :production)]
    (.println System/out
      (dba/diff->migration adapter latest schema))))
