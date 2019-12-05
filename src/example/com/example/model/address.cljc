(ns com.example.model.address
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [add-attribute!]]
    [com.fulcrologic.rad.authorization :as auth]))

(add-attribute! ::id :uuid
  {::attr/unique?   true
   ::datomic/schema :production
   ::datomic/entity ::address
   ::attr/index?    true
   ::attr/required? true
   ::auth/authority :local})

(add-attribute! ::street :string
  {::attr/index?    true
   ::datomic/entity-ids #{::id}
   ::datomic/schema :production
   ::datomic/entity ::address
   ::attr/required? true})

(add-attribute! ::city :string
  {::attr/index?    true
   ::datomic/entity-ids #{::id}
   ::datomic/schema :production
   ::datomic/entity ::address
   ::attr/required? true})

(add-attribute! ::state :enum
  {::attr/values    #{:AZ :AL :AK :CA :CT :DE :GA :HI :KS :MS :MO :MN :OR :WA}
   ::datomic/entity-ids #{::id}
   ::datomic/schema :production
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

(add-attribute! ::zip :string
  {::attr/index?    true
   ::datomic/entity-ids #{::id}
   ::datomic/schema :production
   ::datomic/entity ::address
   ::attr/required? true})

