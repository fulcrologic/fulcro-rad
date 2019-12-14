(ns com.fulcrologic.rad.config)

(alias 'rad.datomic 'com.fulcrologic.rad.database-adapters.datomic)
(alias 'rad.sql     'com.fulcrologic.rad.database-adapters.sql)

(defn datomic? [config]
  (boolean (seq (::rad.datomic/databases config))))

(defn sql? [config]
  (boolean (seq (::rad.sql/databases config))))
