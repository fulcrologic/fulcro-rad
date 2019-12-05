(ns com.example.components.auto-resolvers
  (:require
    [mount.core :refer [defstate]]
    [com.example.schema :as ex-schema]
    [com.fulcrologic.rad.resolvers :as res]
    [taoensso.timbre :as log]))

(defstate automatic-resolvers
  :start
  [] #_(res/schema->resolvers #{:production} ex-schema/latest-schema))
