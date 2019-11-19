(ns com.example.client
  (:require
    [com.example.ui :as ui :refer [Root]]
    [com.example.ui.login-dialog :refer [LoginForm]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.rad.controller :as controller]
    [com.fulcrologic.rad.schema :as schema]
    [com.example.schema :refer [latest-schema]]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.components :as comp]))

(defonce app (app/fulcro-app {:remotes          {:remote (http/fulcro-http-remote {})}
                              :client-did-mount (fn [app]
                                                  (auth/start! app {:local (uism/with-actor-class (comp/get-ident LoginForm {}) LoginForm)})
                                                  (controller/start! app
                                                    {::schema/schema     latest-schema
                                                     ::controller/router ui/MainRouter
                                                     ::controller/id     ::main-controller}))}))

(defn start [] (app/mount! app Root "app"))
