(ns development
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [clojure.tools.namespace.repl :as tools-ns]
    [com.example.components.middleware]
    [com.example.components.server]
    [com.example.model.account :as account]
    [com.example.model.employee :as employee]
    [com.example.schema :as ex-schema]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.fulcrologic.rad.database-adapters.postgresql :as psql]
    [com.fulcrologic.rad.resolvers :as res]
    [mount.core :as mount]
    [taoensso.timbre :as log]))

(defn start []
  (mount/start-with-args {:config "config/dev.edn"})
  :ok)

(defn stop
  "Stop the server."
  []
  (mount/stop))

(def go start)

(defn restart
  "Stop, refresh, and restart the server."
  []
  (stop)
  (tools-ns/refresh :after 'development/start))


(comment

  (res/schema->resolvers #{:production} ex-schema/schema)
  (res/entity->resolvers :production employee/employee)
  (res/entity->resolvers :production account/account))

(comment
  (let [adapter (datomic/->DatomicAdapter :production)]
    (pprint
      (dba/diff->migration adapter latest schema)))

  (let [adapter (datomic/->DatomicAdapter :old-database)]
    (pprint
      (dba/diff->migration adapter latest schema)))

  (let [adapter (psql/->PostgreSQLAdapter :production)]
    (print
      (dba/diff->migration adapter latest schema)))

  (let [adapter (psql/->PostgreSQLAdapter :old-database)]
    (print
      (dba/diff->migration adapter latest schema))))
