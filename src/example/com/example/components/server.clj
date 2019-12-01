(ns com.example.components.server
  (:require
    [mount.core :refer [defstate]]
    [org.httpkit.server :refer [run-server]]
    [taoensso.timbre :as log]
    [com.example.components.config :as s.config]
    [com.example.components.middleware :refer [middleware]]))

(defstate http-server
  :start
  (let [cfg            (get s.config/config ::config)
        running-server (run-server middleware cfg)]
    (log/info "Starting webserver with config " cfg)
    {:shutdown running-server})
  :stop
  (when-let [shutdown (:shutdown http-server)]
    (shutdown)))
