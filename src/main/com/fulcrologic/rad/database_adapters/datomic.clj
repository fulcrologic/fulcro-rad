(ns com.fulcrologic.rad.database-adapters.datomic
  (:require
    [clojure.set :as set]
    [clojure.walk :as walk]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.database-adapters.protocols :as dbp]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [select-keys-in-ns]]
    [datomic.api :as d]
    [datomock.core :as dm :refer [mock-conn]]
    [edn-query-language.core :as eql]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]))

(def type-map
  {:string   :db.type/string
   :boolean  :db.type/boolean
   :password :db.type/string
   :int      :db.type/long
   :long     :db.type/long
   :money    :db.type/bigdec
   :inst     :db.type/inst
   :keyword  :db.type/keyword
   :symbol   :db.type/symbol
   :ref      :db.type/ref
   :uuid     :db.type/uuid})



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
  (get-by-ids [_ id-attr ids desired-output]
    ;; TODO: Should use consistent DB for atomicity
    (let [pk   (::attr/qualified-key id-attr)
          eids (mapv (fn [id] [pk id]) ids)
          db   (d/db connection)]
      (pull-* db desired-output eids)))
  (save-form [_ _ params]
    (let [txn (delta->datomic-txn (::form/delta params))]
      @(d/transact connection txn))
    nil))

(def suggested-logging-blacklist
  "A vector containing a list of namespace strings that generate a lot of debug noise when using Datomic. Can
  be added to Timbre's ns-blacklist to reduce logging overhead."
  ["com.mchange.v2.c3p0.impl.C3P0PooledConnectionPool"
   "com.mchange.v2.c3p0.stmt.GooGooStatementCache"
   "com.mchange.v2.resourcepool.BasicResourcePool"
   "com.zaxxer.hikari.pool.HikariPool"
   "com.zaxxer.hikari.pool.PoolBase"
   "com.mchange.v2.c3p0.impl.AbstractPoolBackedDataSource"
   "com.mchange.v2.c3p0.impl.NewPooledConnection"
   "datomic.common"
   "datomic.connector"
   "datomic.coordination"
   "datomic.db"
   "datomic.index"
   "datomic.kv-cluster"
   "datomic.log"
   "datomic.peer"
   "datomic.process-monitor"
   "datomic.reconnector2"
   "datomic.slf4j"
   "io.netty.buffer.PoolThreadCache"
   "org.apache.http.impl.conn.PoolingHttpClientConnectionManager"
   "org.projectodd.wunderboss.web.Web"
   "org.quartz.core.JobRunShell"
   "org.quartz.core.QuartzScheduler"
   "org.quartz.core.QuartzSchedulerThread"
   "org.quartz.impl.StdSchedulerFactory"
   "org.quartz.impl.jdbcjobstore.JobStoreTX"
   "org.quartz.impl.jdbcjobstore.SimpleSemaphore"
   "org.quartz.impl.jdbcjobstore.StdRowLockSemaphore"
   "org.quartz.plugins.history.LoggingJobHistoryPlugin"
   "org.quartz.plugins.history.LoggingTriggerHistoryPlugin"
   "org.quartz.utils.UpdateChecker"
   "shadow.cljs.devtools.server.worker.impl"])

(>defn automatic-schema
  "Returns a Datomic transaction for the complete schema that is represented in the RAD attributes
   that have a `::datomic/schema` that matches `schema-name`."
  [schema-name]
  [keyword? => vector?]
  (let [attributes (filter #(= schema-name (::schema %)) (vals @attr/attribute-registry))]
    (mapv
      (fn [{::attr/keys [unique? identity? type qualified-key cardinality] :as a}]
        (let [overrides    (select-keys-in-ns a "db")
              datomic-type (get type-map type)]
          (when-not datomic-type
            (throw (ex-info (str "No mapping from attribute type to Datomic: " type) {})))
          (merge
            (cond-> {:db/ident       qualified-key
                     :db/cardinality (if (= :many cardinality)
                                       :db.cardinality/many
                                       :db.cardinality/one)
                     :db/index       true
                     :db/valueType   datomic-type}
              unique? (assoc :db/unique :db.unique/value)
              identity? (assoc :db/unique :db.unique/identity))
            overrides)))
      attributes)))

(let [db-url      (fn [] (str "datomic:mem://" (gensym "test-database")))
      pristine-db (atom nil)
      migrated-db (atom {})
      setup!      (fn [schema txn]
                    (locking pristine-db
                      (when-not @pristine-db
                        (let [db-url (db-url)
                              _      (log/info "Creating test database" db-url)
                              _      (d/create-database db-url)
                              conn   (d/connect db-url)]
                          (reset! pristine-db (d/db conn))))
                      (when-not (get @migrated-db schema)
                        (let [conn (dm/mock-conn @pristine-db)
                              txn  (if (vector? txn) txn (automatic-schema schema))]
                          (log/debug "Transacting schema: " txn)
                          @(d/transact conn txn)
                          (swap! migrated-db assoc schema (d/db conn))))))]
  (defn empty-db-connection
    "Returns a Datomic database that contains the given application schema, but no data.
     This function must be passed a schema name (keyword).  The optional second parameter
     is the actual schema to use in the empty database, otherwise automatic generation will be used
     against RAD attributes. This function memoizes the resulting database for speed.

     See `reset-test-schema`."
    ([schema-name]
     (empty-db-connection schema-name nil))
    ([schema-name txn]
     (setup! schema-name txn)
     (dm/mock-conn (get @migrated-db schema-name))))

  (defn pristine-db-connection
    "Returns a Datomic database that has no application schema or data."
    []
    (setup!)
    (dm/mock-conn @pristine-db))

  (defn reset-test-schema
    "Reset the schema on the empty test database. This is necessary if you change the schema
    and don't want to restart your REPL/Test env."
    []
    (reset! pristine-db nil)
    (reset! migrated-db {})))

