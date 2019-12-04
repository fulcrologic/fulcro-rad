(ns com.example.components.auto-resolvers
  (:require
    [mount.core :refer [defstate]]
    [com.example.schema :as ex-schema]
    [com.fulcrologic.rad.resolvers :as res]
    [taoensso.timbre :as log]))

(defstate automatic-resolvers
  :start
  (res/schema->resolvers #{:primary-db} ex-schema/latest-schema))
