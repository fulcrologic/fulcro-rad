(ns com.example.components.datomic
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [taoensso.timbre :as log]
    [mount.core :refer [defstate]]
    [com.example.components.config :refer [config]]))

(defstate ^{:on-reload :noop} datomic-connections
  :start
  (datomic/start-databases config))
