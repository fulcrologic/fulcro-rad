(ns com.example.schema
  (:require
    [clojure.pprint :refer [pprint]]
    [com.example.model.account :as account]
    [com.example.model.address :as address]
    [com.example.model.tag :as tag]
    [com.fulcrologic.rad.schema :as schema]
    [com.fulcrologic.rad.attributes :as attr]))

(def prior-schema
  {::schema/entities []})

(def latest-schema
  {::schema/roots    (mapv attr/key->attribute [::account/all-accounts])
   ::schema/entities [account/account
                      address/address
                      tag/tag]})

