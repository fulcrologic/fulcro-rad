(ns com.example.components.parser
  (:require
    [com.example.components.auto-resolvers :refer [automatic-resolvers]]
    [com.example.components.config :refer [config]]
    [com.example.components.datomic :refer [datomic-connections]]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.pathom :as pathom]
    [mount.core :refer [defstate]]
    [datomic.api :as d]))

(defstate parser
  :start
  (pathom/new-parser config
    (fn [env]
      ;; Setup required datomic env entries. This is how you would
      ;; select the correct connection when doing things like sharding.
      (assoc env
        ::datomic/connections {:production (:main datomic-connections)}
        ::datomic/databases {:production (d/db (:main datomic-connections))}))
    [automatic-resolvers]))
