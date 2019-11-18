(ns com.example.components.config
  (:require
    [com.fulcrologic.fulcro.server.config :as fserver]
    [mount.core :refer [defstate args]]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]))

(defn start-logging! [config]
  (let [{:keys [taoensso.timbre/logging-config]} config]
    (log/info "Configuring Timbre with " logging-config)
    (log/merge-config!
      (assoc logging-config
        :middleware (if (System/getProperty "dev")
                      [(fn [{:keys [level vargs] :as data}]
                         (if (and (= :debug level) (= "=>" (second vargs)))
                           (update-in data [:vargs 2] #(with-out-str (clojure.pprint/pprint %)))
                           data))]
                      [])))))

(defstate config
  "The overrides option in args is for overriding
   configuration in tests."
  :start (let [{:keys [config overrides]
                :or   {config "config/dev.edn"}} (args)
               loaded-config (merge (fserver/load-config {:config-path config}) overrides)]
           (log/warn "Loading config" config)
           (start-logging! loaded-config)

           loaded-config))


