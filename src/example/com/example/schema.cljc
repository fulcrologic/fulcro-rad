(ns com.example.schema
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.rad.database :as database]
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.fulcrologic.rad.database-adapters.postgresql :as psql]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
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
  (let [adapter (datomic/->DatomicAdapter :production)]
    (pprint
      (dba/diff->migration adapter latest schema)))

  (let [adapter (psql/->PostgreSQLAdapter :production)]
    (.println System/out
      (dba/diff->migration adapter latest schema)))


  )
