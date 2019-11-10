(ns com.fulcrologic.rad.database-adapters.datomic
  (:require
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.schema :as schema]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [clojure.string :as str]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]))

(def type-map
  {:string  :db.type/string
   :int     :db.type/long
   :long    :db.type/long
   :money   :db.type/bigdec
   :inst    :db.type/inst
   :keyword :db.type/keyword
   :symbol  :db.type/symbol
   :ref     :db.type/ref
   :uuid    :db.type/uuid})

(>defn new-entities->migration
  [schema new-entities]
  [::schema/schema (s/coll-of ::entity/entity) => vector?]
  (reduce
    (fn [txn {::entity/keys [attributes] :as entity}]
      (reduce
        (fn [txn {::attr/keys [qualified-key type index? component? unique cardinality]}]
          (let [datomic-cardinality (if (= :many cardinality) :db.cardinality/many :db.cardinality/one)
                datomic-type        (type-map type)]
            (conj txn
              (cond-> {:db/ident       qualified-key
                       :db/valueType   datomic-type
                       :db/cardinality datomic-cardinality}
                (and (= :ref type) component?) (assoc :db/isComponent true)
                index? (->
                           (assoc :db/index true)
                           (cond->
                             (= :string type) (assoc :db/fulltext true)))
                unique (assoc :db/unique (keyword "db.unique" (name unique)))))))
        txn
        attributes))
    []
    new-entities))

(defrecord DatomicAdapter [database-id]
  dba/DBAdapter
  (-diff->migration [this old-schema new-schema]
    (let [diff (schema/schema-diff database-id old-schema new-schema)
          {::schema/keys [new-entities]} diff
          txn  (new-entities->migration new-schema new-entities)]
      txn)))

