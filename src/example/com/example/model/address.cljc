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
  {::attr/unique    :identity
   ::attr/index?    true
   ::attr/required? true
   ::auth/authority :local
   ::db/id          :production})

(defattr ::street :string
  {::db/id          :production
   ::attr/index?    true
   ::attr/required? true})

(defattr ::city :string
  {::db/id          :production
   ::attr/index?    true
   ::attr/required? true})

(defattr ::state :enum
  {::db/id          :production
   ::attr/cardinality :one
   ::attr/values #{:AZ :AL :AK :CA :CT :DE :GA :HI :KS :MS :MO :MN :OR :WA}
   ::attr/index?    true
   ::attr/required? true})

(defattr ::zip :string
  {::db/id          :production
   ::attr/index?    true
   ::attr/required? true})

