(ns com.fulcrologic.rad.database-adapters.datomic
  (:require
    [com.fulcrologic.rad.database-adapters.protocols :as dbp]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.schema :as schema]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [datomic.api :as d]
    [datomock.core :refer [mock-conn]]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [clojure.string :as str]
    [edn-query-language.core :as eql]
    [clojure.walk :as walk]
    [clojure.set :as set]))


(def type-map
  {:string   :db.type/string
   :password :db.type/string
   :int      :db.type/long
   :long     :db.type/long
   :money    :db.type/bigdec
   :inst     :db.type/inst
   :keyword  :db.type/keyword
   :symbol   :db.type/symbol
   :ref      :db.type/ref
   :uuid     :db.type/uuid})

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
        (map attr/key->attribute attributes)))
    []
    new-entities))

(defn replace-ref-types
  "dbc   the database to query
   refs  a set of keywords that ref datomic entities, which you want to access directly
          (rather than retrieving the entity id)
   m     map returned from datomic pull containing the entity IDs you want to deref"
  [db refs arg]
  (walk/postwalk
    (fn [arg]
      (cond
        (and (map? arg) (some #(contains? refs %) (keys arg)))
        (reduce
          (fn [acc ref-k]
            (cond
              (and (get acc ref-k) (not (vector? (get acc ref-k))))
              (update acc ref-k (comp :db/ident (partial d/entity db) :db/id))
              (and (get acc ref-k) (vector? (get acc ref-k)))
              (update acc ref-k #(mapv (comp :db/ident (partial d/entity db) :db/id) %))
              :else acc))
          arg
          refs)
        :else arg))
    arg))

(defn pull-*
  "Will either call d/pull or d/pull-many depending on if the input is
  sequential or not.

  Optionally takes in a transform-fn, applies to individual result(s)."
  ([db pattern eid-or-eids]
   (->> (if (and (not (eql/ident? eid-or-eids)) (sequential? eid-or-eids))
          (d/pull-many db pattern eid-or-eids)
          (d/pull db pattern eid-or-eids))
     ;; TODO: Pull known enum ref types from schema
     (replace-ref-types db #{})))
  ([db pattern eid-or-eids transform-fn]
   (let [result (pull-* db pattern eid-or-eids)]
     (if (sequential? result)
       (mapv transform-fn result)
       (transform-fn result)))))

(defn ref->ident
  "Sometimes references on the client are actual idents and sometimes they are
  nested maps, this function attempts to return an ident regardless."
  [x]
  (when (eql/ident? x) x))

;; TODO: Use attribute defs to elide the bits of delta that don't belong to this db
(defn delta->datomic-txn
  "Takes in a normalized form delta, usually from client, and turns in
  into a datomic transaction."
  [delta]
  (mapcat (fn [[[id-k id] entity-diff]]
            (conj
              (mapcat (fn [[k diff]]
                        (let [{:keys [before after]} diff]
                          (cond
                            (ref->ident after) (if (nil? after)
                                                 [[:db/retract (str id) k (ref->ident before)]]
                                                 [[:com.fulcrologic.rad.fn/add-ident (str id) k (ref->ident after)]])

                            (and (sequential? after) (every? ref->ident after))
                            (let [before   (into #{}
                                             (comp (map ref->ident) (remove nil?))
                                             before)
                                  after    (into #{}
                                             (comp (map ref->ident) (remove nil?))
                                             after)
                                  retracts (set/difference before after)
                                  adds     (set/difference after before)
                                  eid      (str id)]
                              (vec
                                (concat
                                  (for [r retracts] [:db/retract eid k r])
                                  (for [a adds] [:com.fulcrologic.rad.fn/add-ident eid k a]))))

                            (and (sequential? after) (every? keyword? after))
                            (let [before   (into #{}
                                             (comp (remove nil?))
                                             before)
                                  after    (into #{}
                                             (comp (remove nil?))
                                             after)
                                  retracts (set/difference before after)
                                  adds     (set/difference after before)
                                  eid      (str id)]
                              (vec
                                (concat
                                  (for [r retracts] [:db/retract eid k r])
                                  (for [a adds] [:db/add eid k a]))))

                            ;; Assume field is optional and omit
                            (and (nil? before) (nil? after)) []

                            :else (if (nil? after)
                                    (if (ref->ident before)
                                      [[:db/retract (str id) k (ref->ident before)]]
                                      [[:db/retract (str id) k before]])
                                    [[:db/add (str id) k after]]))))
                entity-diff)
              {id-k id :db/id (str id)}))
    delta))

(defrecord DatomicAdapter [database-id connection]
  dbp/DBAdapter
  (get-by-ids [this entity id-attr ids desired-output]
    ;; TODO: Should use consistent DB for atomicity
    (let [pk   (::attr/qualified-key id-attr)
          eids (mapv (fn [id] [pk id]) ids)
          db   (d/db connection)]
      (pull-* db desired-output eids)))
  (diff->migration [this old-schema new-schema]
    (let [diff (schema/schema-diff database-id old-schema new-schema)
          {::schema/keys [new-entities]} diff
          txn  (new-entities->migration new-schema new-entities)]
      txn))
  (save-form [this mutation-env params]
    (let [txn (delta->datomic-txn (::form/delta params))]
      @(d/transact connection txn))
    nil))


