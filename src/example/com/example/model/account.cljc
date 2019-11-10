(ns com.example.model.account
  (:refer-clojure :exclude [name])
  (:require
    [com.wsscode.pathom.connect :as pc]
    [clojure.tools.namespace.repl :as tools-ns]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.validation :as validation]
    [com.fulcrologic.rad.authorization :as authorization]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr id :uuid
  ::attr/clojure-spec uuid?
  ::attr/unique :identity
  ::attr/required? true
  ::db/database :production)

(defattr name :string
  ::attr/clojure-spec string?
  ::attr/index? true
  ::attr/required? true
  ::db/database :production
  ::validation/validator :spec
  ::validation/error-message "Name must not be empty")

(defattr role :keyword
  ::attr/clojure-spec #{:user :admin :support :manager}
  ::pc/input #{::id}
  ::attr/resolver (fn [env input]
                    ;; code to determine role
                    :user)
  ::attr/virtual? true)

(defn admin? [context]
  (= :admin (role context)))

(defn support? [context]
  (= :support (role context)))

(defn owned-by [context entity]
  (cond
    (or (= (id context) (id entity))
      (admin? context))
    #{:read :write}

    (support? context)
    #{:read}

    :else
    #{}))

(defattr last-login :inst
  ::attr/clojure-spec inst?
  ;; doesn't go in db, no resolver auto-generation
  ::attr/virtual? true
  ::authorization/required-contexts #{id}
  ::authorization/permissions (fn [context entity]
                                (owned-by context entity)))

(def entity
  {::entity/attributes [id name role last-login]})
