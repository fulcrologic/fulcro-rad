(ns com.example.model.address
  (:require
    #?@(:clj
        [[com.wsscode.pathom.connect :as pc :refer [defmutation]]])
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.entity :as entity :refer [defentity]]
    [com.fulcrologic.rad.validation :as validation]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.authorization :as auth]
    [taoensso.timbre :as log]))

(defattr ::id :uuid
  {::attr/unique?   true
   ::db/id          :primary-db
   ::db/entity      ::address
   ::attr/index?    true
   ::attr/required? true
   ::auth/authority :local})

(defattr ::street :string
  {::attr/index?    true
   ::db/id          :primary-db
   ::db/entity      ::address
   ::attr/required? true})

(defattr ::city :string
  {::attr/index?    true
   ::db/id          :primary-db
   ::db/entity      ::address
   ::attr/required? true})

(defattr ::state :enum
  {::attr/values    #{:AZ :AL :AK :CA :CT :DE :GA :HI :KS :MS :MO :MN :OR :WA}
   ::db/id          :primary-db
   ::db/entity      ::address
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
   ::db/id          :primary-db
   ::db/entity      ::address
   ::attr/required? true})

(defentity address [::id ::street ::city ::state ::zip]
  {})

