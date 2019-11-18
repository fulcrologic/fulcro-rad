(ns com.example.client
  (:require
    [com.example.ui :refer [Root]]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(defonce app (app/fulcro-app {:remotes {:remote (http/fulcro-http-remote {})}}))

(defn start [] (app/mount! app Root "app"))
