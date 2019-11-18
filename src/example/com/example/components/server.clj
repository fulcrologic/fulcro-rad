(ns com.example.components.server
  (:require
    [immutant.web :as web]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [com.example.components.config :as s.config]
    [com.example.components.middleware :refer [middleware]]))

(defstate http-server
  :start
  (let [cfg            (get s.config/config ::config)
        running-server (web/run middleware cfg)]
    (log/info "Starting webserver with config " cfg)
    {:server running-server})
  :stop
  (when-let [server (:server http-server)]
    (web/stop server)))
