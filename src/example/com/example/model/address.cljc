(ns com.example.model.address
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.authorization :as auth]))

(defattr id ::id :uuid
  {::attr/unique?   true
   ::datomic/schema :production
   ::datomic/entity ::address
   ::attr/index?    true
   ::attr/required? true
   ::auth/authority :local})

(defattr street ::street :string
  {::attr/index?        true
   ::datomic/entity-ids #{::id}
   ::datomic/schema     :production
   ::datomic/entity     ::address
   ::attr/required?     true})

(defattr city ::city :string
  {::attr/index?        true
   ::datomic/entity-ids #{::id}
   ::datomic/schema     :production
   ::datomic/entity     ::address
   ::attr/required?     true})

(defattr state ::state :enum
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

(defattr zip ::zip :string
  {::attr/index?        true
   ::datomic/entity-ids #{::id}
   ::datomic/schema     :production
   ::datomic/entity     ::address
   ::attr/required?     true})

(def attributes [id street city state zip])
