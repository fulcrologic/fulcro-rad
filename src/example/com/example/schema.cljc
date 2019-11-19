(ns com.example.schema
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.rad.database :as database]
    [com.example.model.account :as account]
    [com.example.model.employee :as employee]
    [com.fulcrologic.rad.schema :as schema]))

(def prior-schema
  {::database/definition (database/datomic-database :production)
   ::schema/entities     []})

(def latest-schema
  {::database/definition (database/datomic-database :production)
   ::schema/roots        [account/all-accounts]
   ::schema/entities     [account/account
                          employee/employee]})

