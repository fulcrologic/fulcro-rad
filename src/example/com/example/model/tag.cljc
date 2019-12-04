(ns com.example.model.tag
  (:require
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.entity :as entity :refer [defentity]]
    [com.fulcrologic.rad.authorization :as auth]
    [clojure.string :as str]
    [taoensso.timbre :as log]))

(defattr ::id :uuid
  {::attr/authority :local
   ::db/id          :primary-db
   ::db/entity      ::tag
   ::attr/unique?   true
   ::attr/required? true
   ::attr/index?    true})

(defattr ::label :string
  {::attr/authority :local
   ::db/id          :primary-db
   ::db/entity      ::tag
   ::attr/unique?   :value
   ::attr/normalize (fn [v] (str/capitalize v))
   ::attr/required? true
   ::attr/index?    true})

(defentity tag [::id ::label]
  {})
