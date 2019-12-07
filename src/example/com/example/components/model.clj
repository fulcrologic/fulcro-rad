(ns com.example.components.model
  (:require
    [com.example.model.account :as account]
    [com.example.model.address :as address]
    [com.example.model.entity :as entity]
    [com.example.model.firm :as firm]
    [com.example.model.tag :as tag]
    [mount.core :refer [defstate args]]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.attributes :as attr]))

(defstate all-attributes
  :start
  (let [all-attributes (vec (concat
                              account/attributes
                              address/attributes
                              firm/attributes
                              entity/attributes
                              tag/attributes))]
    (attr/register-attributes! all-attributes)
    all-attributes))
