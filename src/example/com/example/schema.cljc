(ns com.example.schema
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.rad.database :as database]
    [com.example.model.account :as account]
    [com.example.model.employee :as employee]
    [com.fulcrologic.rad.schema :as schema]
    [com.fulcrologic.rad.attributes :as attr]))

(def prior-schema
  {::database/definition (database/datomic-database :production)
   ::schema/entities     []})

(def latest-schema
  {::database/definition (database/datomic-database :production)
   ::schema/roots        (mapv attr/key->attribute [::account/all-accounts])
   ::schema/entities     [account/account
                          employee/employee]})

