(ns com.fulcrologic.rad.database-adapters.datomic
  (:require
    [clojure.set :as set]
    [clojure.walk :as walk]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.ids :refer [select-keys-in-ns]]
    [datomic.api :as d]
    [datomic.function :as df]
    [datomock.core :as dm :refer [mock-conn]]
    [edn-query-language.core :as eql]
    [mount.core :refer [defstate]]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]
    [com.fulcrologic.rad.authorization :as auth]
    [clojure.spec.alpha :as s]
    [com.example.components.config :as config]))

(def type-map
  {:string   :db.type/string
   :enum     :db.type/ref
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

(defn get-by-ids [db pk ids desired-output]
  ;; TODO: Should use consistent DB for atomicity
  (let [eids (mapv (fn [id] [pk id]) ids)]
    (pull-* db desired-output eids)))

(defn ref->ident
  "Sometimes references on the client are actual idents and sometimes they are
  nested maps, this function attempts to return an ident regardless."
  [x]
  (when (eql/ident? x) x))

(defn delta->datomic-txn
  "Takes in a normalized form delta, usually from client, and turns in
  into a Datomic transaction for the given schema (returns empty txn if there is nothing on the delta for that schema)."
  [schema delta]
  ;; TASK: test mapcat on nil (nothing on schema)
  (mapcat (fn [[[id-k id] entity-diff]]
            (when (-> id-k attr/key->attribute ::schema (= schema))
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
                {id-k id :db/id (str id)})))
    delta))

(def keys-in-delta
  (memoize
    (fn keys-in-delta [delta]
      (let [id-keys  (into #{}
                       (map first)
                       (keys delta))
            all-keys (into id-keys
                       (mapcat keys)
                       (vals delta))]
        all-keys))))

(defn schemas-for-delta [delta]
  (let [all-keys (keys-in-delta delta)
        schemas  (into #{}
                   (keep #(-> % attr/key->attribute ::schema))
                   all-keys)]
    schemas))

;; TASK: Is Two-phase commit possible, since multiple database might be saved to? We could auto-generate the inverse of
;; the delta as an "undo", but it is hard to ensure an undo will succeed.
;; TASK: UNTESTED.
(defn save-form
  "Do all of the possible Datomic operations for the given form delta (save to all Datomic databases involved)"
  [env form-delta]
  (let [schemas (schemas-for-delta form-delta)]
    (log/info "Saving form across " schemas)
    (doseq [schema schemas
            :let [connection (-> env ::connections (get schema))
                  txn        (delta->datomic-txn schema (::form/delta form-delta))]]
      (log/info "Saving form delta" form-delta)
      (log/info "on schema" schema)
      (log/info "Running txn" txn)
      (if (and connection (seq txn))
        @(d/transact connection txn)
        (log/error "Unable to save form. Either connection was missing in env, or txn was empty."))))
  nil)

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

(defn- attribute-schema [schema-name]
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

(defn- enumerated-values [schema-name]
  (let [attributes (filter #(= schema-name (::schema %)) (vals @attr/attribute-registry))]
    (mapcat
      (fn [{::attr/keys [qualified-key type enumerated-values] :as a}]
        (when (= :enum type)
          (let [enum-nspc (str (namespace qualified-key) "." (name qualified-key))]
            (keep (fn [v]
                    (cond
                      (map? v) v
                      (qualified-keyword? v) {:db/ident v}
                      :otherwise (let [enum-ident (keyword enum-nspc (name v))]
                                   {:db/ident enum-ident})))
              enumerated-values))))
      attributes)))

(>defn automatic-schema
  "Returns a Datomic transaction for the complete schema that is represented in the RAD attributes
   that have a `::datomic/schema` that matches `schema-name`."
  [schema-name]
  [keyword? => vector?]
  (let [txn (attribute-schema schema-name)
        txn (into txn (enumerated-values schema-name))]
    txn))

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

(defn config->postgres-url [{:postgresql/keys [user host port password database]
                             datomic-db       :datomic/database}]
  (assert user ":postgresql/user must be specified")
  (assert host ":postgresql/host must be specified")
  (assert port ":postgresql/port must be specified")
  (assert password ":postgresql/password must be specified")
  (assert database ":postgresql/database must be specified")
  (assert datomic-db ":datomic/database must be specified")
  (str "datomic:sql://" datomic-db "?jdbc:postgresql://" host (when port (str ":" port)) "/"
    database "?user=" user "&password=" password))

(defn config->url [{:datomic/keys [driver] :as config}]
  (case driver
    :postgresql (config->postgres-url config)
    (throw (ex-info "Unsupported Datomic back-end driver." {:driver driver}))))

(defn ensure-transactor-functions!
  "Must be called on any Datomic database that will be used with automatic form save. This
  adds transactor functions.  The built-in startup logic (if used) will automatically call this,
  but if you create/start your databases with custom code you should run this on your newly
  created database."
  [conn]
  @(d/transact conn [{:db/id    (d/tempid :db.part/user)
                      :db/ident :com.fulcrologic.rad.fn/add-ident
                      :db/fn    (df/construct
                                  '{:lang   "clojure"
                                    :params [db eid rel ident]
                                    :code   (do
                                              (when-not (and (= 2 (count ident))
                                                          (keyword? (first ident)))
                                                (throw (IllegalArgumentException.
                                                         (str "ident must be an ident, got " ident))))
                                              (let [ref-val (or (:db/id (datomic.api/entity db ident))
                                                              (str (second ident)))]
                                                [[:db/add eid rel ref-val]]))})}]))

(defn start-database!
  "Starts a Datomic database connection given the standard sub-element config described
  in `start-databases`. Typically use that function instead of this one.


  * `:config` a map of k-v pairs for setting up a connection.
  * `schemas` a map from schema name to either :auto, :none, or (fn [k conn]).

  Returns a migrated database connection."
  [{:datomic/keys [driver schema prevent-changes?] :as config} schemas]
  (let [url             (config->url config)
        generator       (get schemas schema :auto)
        _               (d/create-database url)
        mock?           (boolean (or prevent-changes? (System/getProperty "force.mocked.connection")))
        real-connection (d/connect url)
        conn            (if mock? (dm/fork-conn real-connection) real-connection)]
    (log/info "Adding form save support to database transactor functions.")
    (ensure-transactor-functions! conn)
    (cond
      (= :auto generator) (let [txn (automatic-schema schema)]
                            (log/info "Transacting automatic schema.")
                            @(d/transact conn txn))
      (ifn? generator) (do
                         (log/info "Running custom schema function.")
                         (generator conn))
      :otherwise (log/info "Schema management disabled."))
    (log/info "Finished connecting to and migrating database.")
    conn))

(defn start-databases
  "Start all of the databases described in config, using the schemas defined in schemas.

  * `config`:  a map that contains the key ::datomic/databases.
  * `schemas`:  a map whose keys are schema names, and whose values can be missing (or :auto) for
  automatic schema generation, a `(fn [schema-name conn] ...)` that updates the schema for schema-name
  on the database reachable via `conn`. You may omit `schemas` if automatic generation is being used
  everywhere.

TASK: Perhaps it makes sense to require the attributes as an explicit list so we don't have this
stupid side-effect requirement for proper operation. That would return us to having a symbol to stand
in for an attribute?

  WARNING: Be sure all of your model files are required before running this function, since it
  will use the attribute definitions during startup.

  The `::datomic/databases` entry in the config is a map with the following form:

  ```
  {:production-shard-1 {:datomic/schema :production
                        :datomic/driver :postgresql
                        :datomic/database \"prod\"
                        :postgresql/host \"localhost\"
                        :postgresql/port \"localhost\"
                        :postgresql/user \"datomic\"
                        :postgresql/password \"datomic\"
                        :postgresql/database \"datomic\"}}
  ```

  The `:datomic/schema` is used to select the attributes that will appear in that database's schema.
  The remaining parameters select and configure the back-end storage system for the database.

  Each supported driver type has custom options for configuring it. See Fulcro's config
  file support for a good method of defining these in EDN config files for use in development
  and production environments.

  Returns a map whose keys are the database keys (i.e. `:production-shard-1`) and
  whose values are the live database connection.
  "
  ([config]
   (start-databases config {}))
  ([config schemas]
   (reduce-kv
     (fn [m k v]
       (log/info "Starting database " k)
       (assoc m k (start-database! v schemas)))
     {}
     (::databases config))))

(defn entity-query
  [{::keys      [schema]
    ::attr/keys [qualified-key]
    :as         env} input]
  (let [one? (not (sequential? input))]
    (enc/if-let [db    (get-in env [::databases schema])
                 query (or
                         (get env :com.wsscode.pathom.core/parent-query)
                         (get env ::default-query))
                 ids   (if one?
                         [(get input qualified-key)]
                         (into [] (keep #(get % qualified-key) input)))]
      (do
        (log/info "Running" query "on entities with " qualified-key ":" ids)
        (let [
              result (get-by-ids db qualified-key ids query)]
          (if one?
            (first result)
            result)))
      (do
        (log/info "Unable to complete query.")
        nil))))

(>defn id-resolver
  [id-key attributes]
  [qualified-keyword? ::attr/attributes => ::pc/resolver]
  (log/info "Building ID resolver for" id-key)
  (enc/if-let [outputs   (attr/attributes->eql attributes)
               attribute (attr/key->attribute id-key)
               schema    (::schema attribute)]
    {::pc/sym     (symbol
                    (str (namespace id-key))
                    (str (name id-key) "-resolver"))
     ::pc/output  outputs
     ::pc/batch?  true
     ::pc/resolve (fn [env input] (->>
                                    (entity-query
                                      (assoc env
                                        ::schema schema
                                        ::attr/qualified-key id-key
                                        ::default-query outputs)
                                      input)
                                    (auth/redact env)))
     ::pc/input   #{id-key}}
    (do
      (log/error "Unable to generate id-resolver. "
        "Attribute was missing schema, or could not be found in the attribute registry: " id-key)
      nil)))

(defn generate-resolvers
  "Generate all of the resolvers that make sense for the given database config. This should be passed
  to your Pathom parser to register resolvers for each of your schemas."
  [attributes schema]
  (let [attributes            (filter #(= schema (::schema %)) attributes)
        entity-id->attributes (group-by ::k (mapcat (fn [attribute]
                                                      (map
                                                        (fn [id-key] (assoc attribute ::k id-key))
                                                        (get attribute ::entity-ids)))
                                              attributes))
        entity-resolvers      (reduce-kv
                                (fn [result k v] (conj result (id-resolver k v)))
                                []
                                entity-id->attributes)]
    entity-resolvers))

