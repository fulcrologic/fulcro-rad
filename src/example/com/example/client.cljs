(ns com.example.client
  (:require
    [com.example.ui :as ui :refer [Root]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.rad.controller :as controller]))

(defonce app (app/fulcro-app {:remotes          {:remote (http/fulcro-http-remote {})}
                              :client-did-mount (fn [app]
                                                  (controller/start! app
                                                    {:auth       ui/AuthController
                                                     :controller ui/CRUDController
                                                     :target-route      ["landing-page"]}))}))

(defn start [] (app/mount! app Root "app"))
