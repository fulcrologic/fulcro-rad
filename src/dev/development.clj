(ns development
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [com.example.components.datomic :refer [production-database]]
    [com.example.components.middleware]
    [com.example.components.server]
    [com.example.model.account :as account]
    [com.example.model.employee :as employee]
    [com.example.schema :as ex-schema :refer [latest-schema prior-schema]]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.fulcrologic.rad.database-adapters.postgresql :as psql]
    [com.fulcrologic.rad.resolvers :as res]
    [mount.core :as mount]
    [taoensso.timbre :as log]
    [datomic.api :as d]
    [com.fulcrologic.rad.attributes :as attr]))

(defn seed []
  (let [u new-uuid
        {:keys [connection]} production-database]
    (d/transact connection [{::account/id       (u 1)
                             ::account/name     "Joe Blow"
                             ::account/email    "joe@example.com"
                             ::account/active?  true
                             ::account/password (attr/encrypt "letmein" "some-salt"
                                                  (::attr/encrypt-iterations
                                                    (attr/key->attribute ::account/password)))}
                            {::account/id       (u 2)
                             ::account/name     "Sam Hill"
                             ::account/email    "sam@example.com"
                             ::account/active?  false
                             ::account/password (attr/encrypt "letmein" "some-salt"
                                                  (::attr/encrypt-iterations
                                                    (attr/key->attribute ::account/password)))}
                            {::account/id       (u 3)
                             ::account/name     "Jose Haplon"
                             ::account/email    "jose@example.com"
                             ::account/active?  true
                             ::account/password (attr/encrypt "letmein" "some-salt"
                                                  (::attr/encrypt-iterations
                                                    (attr/key->attribute ::account/password)))}
                            {::account/id       (u 4)
                             ::account/name     "Rose Small"
                             ::account/email    "rose@example.com"
                             ::account/active?  true
                             ::account/password (attr/encrypt "letmein" "some-salt"
                                                  (::attr/encrypt-iterations
                                                    (attr/key->attribute ::account/password)))}])))

(defn start []
  (mount/start-with-args {:config "config/dev.edn"})
  (seed)
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

(def reset #'restart)

(comment
  (seed)
  (res/schema->resolvers #{:production} ex-schema/latest-schema)
  (res/entity->resolvers :production employee/employee)
  (res/entity->resolvers :production account/account))

(comment
  (let [adapter (datomic/->DatomicAdapter :production nil)]
    (pprint
      (dba/diff->migration adapter prior-schema latest-schema)))

  (let [adapter (datomic/->DatomicAdapter :old-database)]
    (pprint
      (dba/diff->migration adapter prior-schema latest-schema)))

  (let [adapter (psql/->PostgreSQLAdapter :production)]
    (print
      (dba/diff->migration adapter prior-schema latest-schema)))

  (let [adapter (psql/->PostgreSQLAdapter :old-database)]
    (print
      (dba/diff->migration adapter prior-schema latest-schema))))
