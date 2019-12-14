(ns com.fulcrologic.rad.config)

(defn datomic? [config]
  (boolean (seq (:com.fulcrologic.rad.database-adapters.datomic/databases config))))

(defn sql? [config]
  (boolean (seq (:com.fulcrologic.rad.database-adapters.sql/databases config))))
