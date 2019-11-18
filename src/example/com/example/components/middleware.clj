(ns com.example.components.middleware
  (:require
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [com.example.components.config :as config]
    [com.example.components.parser :as parser]
    [taoensso.timbre :as log]))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      (server/handle-api-request (:transit-params request) (fn [query] (parser/parser {} query)))
      (handler request))))

(def not-found-handler
  (fn [req]
    {:status 404
     :body   {}}))

(defstate middleware
  :start
  (let [defaults-config (:ring.middleware/defaults-config config/config)]
    (-> not-found-handler
      (wrap-api "/api")
      (server/wrap-transit-params {})
      (server/wrap-transit-response {})
      (wrap-defaults defaults-config))))

