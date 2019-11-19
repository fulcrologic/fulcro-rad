(ns com.fulcrologic.rad.schema
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.attributes :as attributes]
    [com.fulcrologic.guardrails.core :refer [>def >defn => ?]]
    [clojure.set :as set]
    [taoensso.timbre :as log]))

(>def ::entities (s/every ::entity/entity))

;; TODO: Index support
(>def ::index map?)
(>def ::indexes (s/every ::index))
(>def ::roots (s/every ::attributes/attribute))

(>def ::schema (s/keys
                 :req [::entities]
                 :opt [::indexes ::roots]))

(>defn find-attribute
  [schema attribute]
  [::schema qualified-keyword? => (? ::attributes/attribute)]
  (reduce
    (fn [_ {::entity/keys [attributes]}]
      (let [attr (first (filter (fn [{::attributes/keys [qualified-key]}]
                                  (= qualified-key attribute))
                          attributes))]
        (when attr
          (reduced attr))))
    nil
    (::entities schema)))

(>def ::new-attributes (s/every ::attributes/attribute))
(>def ::old-attribute ::attributes/attribute)
(>def ::new-attribute ::attributes/attribute)
(>def ::attribute-diff (s/keys :opt [::old-attribute ::new-attribute]))
(>def ::new-entities (s/every ::entity/entity))
(>def ::entity-diff (s/every ::attribute-diff))
(>def ::modified-entities (s/map-of ::entity/qualified-key ::entity-diff))
(>def ::deleted-entities (s/every ::entity/qualified-key))
(>def ::schema-diff (s/keys :opt [::new-entities ::deleted-entities ::modified-entities]))

(>defn entity-diff
  [database-id {old-attributes ::entity/attributes :as old-entity} {new-attributes ::entity/attributes :as new-entity}]
  [::db/id ::entity/entity ::entity/entity => ::entity-diff]
  (let [belongs?  (fn [{::db/keys [id]}] (= database-id id))
        old-attrs (into {}
                    (comp
                      (filter belongs?)
                      (map (fn [{::attributes/keys [qualified-key] :as attr}] [qualified-key attr])))
                    old-attributes)
        new-attrs (into {}
                    (comp
                      (filter belongs?)
                      (map (fn [{::attributes/keys [qualified-key] :as attr}] [qualified-key attr])))
                    new-attributes)
        all-keys  (set/union (set (keys new-attrs)) (set (keys old-attrs)))
        new?      (fn [k] (and
                            (contains? new-attrs k)
                            (not (contains? old-attrs k))))
        deleted?  (fn [k] (and
                            (contains? old-attrs k)
                            (not (contains? new-attrs k))))
        modified? (fn [k] (and
                            (contains? new-attrs k)
                            (contains? old-attrs k)
                            (not= (get old-attrs k) (get new-attrs k))))
        all       (into []
                    (keep (fn [k]
                            (cond
                              (new? k) {::new-attribute (get new-attrs k)}
                              (modified? k) {::old-attribute (get old-attrs k)
                                             ::new-attribute (get new-attrs k)}
                              (deleted? k) {::old-attribute (get old-attrs k)})))
                    all-keys)]
    all))

(>defn limited-attributes
  "Returns an entity with just the attributes associated with the given database."
  [dbid entity]
  [::db/id ::entity/entity => ::entity/entity]
  (update entity ::entity/attributes (fn [attrs]
                                       (filterv (fn [{::db/keys [id]}]
                                                  (= dbid id)) attrs))))

(>defn schema-diff
  "Return a diff between two schemas, which can be turned into migrations using a db adapter.

  * `database-id` - The ID of the database to calculate the diff for.
  * `old-schema` - The old schema (from the last snapshot)
  * `new-schema` - The new (currently proposed) schema

  Returns a proposed migration for the database.
  "
  [database-id {old-entities ::entities :as old-schema} {new-entities ::entities :as new-schema}]
  [::db/id ::schema ::schema => ::schema-diff]
  (let [belongs?     (fn [{::entity/keys [attributes]}] (some #(= database-id (::db/id %)) attributes))
        old-entities (into {}
                       (keep (fn [{::entity/keys [qualified-key] :as entity}]
                               (when (belongs? entity)
                                 [qualified-key (limited-attributes database-id entity)])))
                       old-entities)
        new-entities (into {}
                       (keep (fn [{::entity/keys [qualified-key] :as entity}]
                               (when (belongs? entity)
                                 [qualified-key (limited-attributes database-id entity)])))
                       new-entities)
        all-keys     (set/union (set (keys new-entities)) (set (keys old-entities)))
        new?         (fn [k] (and
                               (contains? new-entities k)
                               (not (contains? old-entities k))))
        deleted?     (fn [k] (and
                               (contains? old-entities k)
                               (not (contains? new-entities k))))
        modified?    (fn [k] (and
                               (contains? new-entities k)
                               (contains? old-entities k)
                               (not= (get old-entities k) (get new-entities k))))
        result       (reduce
                       (fn [result k]
                         (cond
                           (new? k) (update result ::new-entities (fnil conj []) (get new-entities k))
                           (modified? k) (update result ::modified-entities (fnil conj [])
                                           {k (entity-diff database-id (get old-entities k) (get new-entities k))})
                           (deleted? k) (update result ::deleted-entities (fnil conj []) k)))
                       {}
                       all-keys)]
    result))
