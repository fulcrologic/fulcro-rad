(ns com.fulcrologic.rad.resolvers-common
  (:require
    [com.fulcrologic.rad.authorization :as auth]))

(defn secure-resolver
  "Redact output from the given resolver"
  [resolver]
  (fn [env input]
    (->>
      (resolver env input)
      (auth/redact env))))