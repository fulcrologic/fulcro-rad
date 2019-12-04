(ns com.example.model.tag
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [clojure.string :as str]))

(defattr ::id :uuid
  {::attr/authority :local
   ::datomic/id     :primary-db
   ::datomic/entity ::tag
   ::attr/unique?   true
   ::attr/required? true
   ::attr/index?    true})

(defattr ::label :string
  {::attr/authority :local
   ::datomic/id     :primary-db
   ::datomic/entity ::tag
   ::attr/unique?   :value
   ::attr/normalize (fn [v] (str/capitalize v))
   ::attr/required? true
   ::attr/index?    true})

