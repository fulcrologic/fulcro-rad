(ns com.example.db.datomic-db-test
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.example.model.account :as acct]
    [datomic.api :as d]
    [taoensso.timbre :as log]
    [clojure.test :refer :all]))

(log/set-level! :debug)

(deftest sample-test
  (let [conn        (datomic/empty-db-connection :production)
        sample-data [{::acct/id   (new-uuid 1)
                      ::acct/name "Joe"}]]
    @(d/transact conn sample-data)

    (let [db (d/db conn)
          a  (d/pull db '[*] [::acct/id (new-uuid 1)])]
      (is (= "Joe" (::acct/name a))))))
