(ns com.example.model.address
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.authorization :as auth]))

(defattr ::id :uuid
  {::attr/unique?   true
   ::datomic/id     :primary-db
   ::datomic/entity ::address
   ::attr/index?    true
   ::attr/required? true
   ::auth/authority :local})

(defattr ::street :string
  {::attr/index?    true
   ::datomic/id     :primary-db
   ::datomic/entity ::address
   ::attr/required? true})

(defattr ::city :string
  {::attr/index?    true
   ::datomic/id     :primary-db
   ::datomic/entity ::address
   ::attr/required? true})

(defattr ::state :enum
  {::attr/values    #{:AZ :AL :AK :CA :CT :DE :GA :HI :KS :MS :MO :MN :OR :WA}
   ::datomic/id     :primary-db
   ::datomic/entity ::address
   ::attr/index?    true
   ::attr/required? true
   ::attr/labels    {:AZ "Arizona"
                     :AL "Alabama"
                     :AK "Alaska"
                     :CA "California"
                     :CT "Connecticut"
                     :DE "Deleware"
                     :GA "Georgia"
                     :HI "Hawaii"
                     :KS "Kansas"
                     :MS "Mississippi"
                     :MO "Missouri"
                     :OR "Oregon"
                     :WA "Washington"}})

(defattr ::zip :string
  {::attr/index?    true
   ::datomic/id     :primary-db
   ::datomic/entity ::address
   ::attr/required? true})

