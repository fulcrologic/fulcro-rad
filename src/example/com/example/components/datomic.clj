(ns com.example.components.datomic
  (:require
   [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
   [com.fulcrologic.rad.database-adapters.protocols :as dbp]
   [com.fulcrologic.rad.database-adapters.datomic :as datomic-adapter]
   [com.example.schema :refer [prior-schema latest-schema]]
   [taoensso.timbre :as log]
   [datomic.api :as d]
   [datomock.core :refer [mock-conn]]
   [mount.core :refer [defstate]]
   [com.example.components.config :refer [config]]
   [clojure.string :as str]))

(defn config->datomic-url [{::keys [config]}]
  (let [{:postgres/keys [user host port password database]
         datomic-db     :datomic/database} config]
    (format "datomic:sql://%s?jdbc:postgresql://%s%s/%s?user=%s&password=%s"
            datomic-db host (when port (str ":" port)) database
            user password)))

(defn presence
  "Returns s if s is not blank. Takes an optional default."
  [s & [default]]
  (if (str/blank? s)
    default
    s))

(def migration-code "" "
  (do
    (when-not (and (= 2 (count ident))
                   (keyword? (first ident)))
      (throw (IllegalArgumentException.
              (str \"ident must be an ident, got \" ident))))
    (let [ref-val (or (:db/id (datomic.api/entity db ident))
                      (str (second ident)))]
      [[:db/add eid rel ref-val]]))
")

;; TODO: This can become part of library once the pattern is well-established
(defn start-database
  []
  (log/info "Starting Datomic")
  (let [url                    (presence (System/getenv "DATOMIC_URL")
                                         (config->datomic-url config))
        ;; TODO: Better system of dealing with migrations
        created?               (d/create-database url)
        mocking-required?      (boolean (System/getProperty
                                         "force.mocked.connection"))
        mocking-ok?            (or mocking-required?
                                   (boolean (System/getProperty
                                             "allow.mocked.connection")))
        conn                   (if mocking-required?
                                 (mock-conn (d/db (d/connect url)))
                                 (d/connect url))
        adapter                (datomic-adapter/->DatomicAdapter :production conn)
        migration              (dbp/diff->migration adapter prior-schema
                                                    latest-schema)]
    (log/warn "Datomic URL: " url)
    (when created?
      (log/info "New Datomic database created: " :main))
    (when mocking-required?
      (log/warn "USING A MOCKED CONNECTION. No changes to the database will persist.")
      (when-not mocking-ok?
        (throw (ex-info (str "REFUSING TO START a database that has SNAPSHOT "
                             "migrations. Please set allow.mocked.connection "
                             "JVM property if you want to allow this.") {}))))
    (log/info "Running Migrations")
    (try
      (d/transact conn [#:db{:fn {:db.fn/lang     :clojure
                                  :db.fn/imports  []
                                  :db.fn/requires []
                                  :db.fn/params   '[db eid rel ident]
                                  :db.fn/code migration-code}
                                        ;:id    #db/id[:db.part/user -1000005]
                             :ident :com.fulcrologic.rad.fn/add-ident}])
      (d/transact conn migration)
      (catch Exception e
        (log/error "Database migration failed:" {:exception e})
        (throw e)))
    {:connection    conn
     ::dba/adapters {:production adapter}}))

(defstate ^{:on-reload :noop} production-database
  :start
  (start-database))
