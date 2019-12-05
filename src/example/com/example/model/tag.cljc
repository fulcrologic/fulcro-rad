(ns com.example.model.tag
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.attributes :as attr :refer [new-attribute]]
    [clojure.string :as str]))

(def attributes
  [(new-attribute ::id :uuid
     {::attr/authority :local
      ::datomic/schema :production
      ::datomic/entity ::tag
      ::attr/unique?   true
      ::attr/required? true
      ::attr/index?    true})

   (new-attribute ::label :string
     {::attr/authority     :local
      ::datomic/entity-ids #{::id}
      ::datomic/schema     :production
      ::datomic/entity     ::tag
      ::attr/unique?       :value
      ::attr/normalize     (fn [v] (str/capitalize v))
      ::attr/required?     true
      ::attr/index?        true})])

