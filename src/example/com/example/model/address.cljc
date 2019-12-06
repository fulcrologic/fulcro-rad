(ns com.example.model.address
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [new-attribute]]
    [com.fulcrologic.rad.authorization :as auth]))

(def attributes
  [(new-attribute ::id :uuid
     {::attr/unique?   true
      ::datomic/schema :production
      ::datomic/entity ::address
      ::attr/index?    true
      ::attr/required? true
      ::auth/authority :local})

   (new-attribute ::street :string
     {::attr/index?        true
      ::datomic/entity-ids #{::id}
      ::datomic/schema     :production
      ::datomic/entity     ::address
      ::attr/required?     true})

   (new-attribute ::city :string
     {::attr/index?        true
      ::datomic/entity-ids #{::id}
      ::datomic/schema     :production
      ::datomic/entity     ::address
      ::attr/required?     true})

   (new-attribute ::state :enum
     {::attr/enumerated-values #{:AZ :AL :AK :CA :CT :DE :GA :HI :KS :MS :MO :MN :OR :WA}
      ::datomic/entity-ids     #{::id}
      ::datomic/schema         :production
      ::datomic/entity         ::address
      ::attr/index?            true
      ::attr/required?         true
      ::attr/labels            {:AZ "Arizona"
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

   (new-attribute ::zip :string
     {::attr/index?        true
      ::datomic/entity-ids #{::id}
      ::datomic/schema     :production
      ::datomic/entity     ::address
      ::attr/required?     true})])
