(ns com.example.components.parser
  (:require
    [com.example.components.auto-resolvers :refer [automatic-resolvers]]
    [com.example.components.config :refer [config]]
    [com.fulcrologic.rad.pathom :as pathom]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]
    [datomic.api :as d]
    [com.fulcrologic.rad.form :as form]))

(defstate parser
  :start
  (pathom/new-parser config (fn [env] env) [automatic-resolvers]))
