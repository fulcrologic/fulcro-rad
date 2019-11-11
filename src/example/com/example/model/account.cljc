(ns com.example.model.account
  (:refer-clojure :exclude [name])
  (:require
    [com.wsscode.pathom.connect :as pc]
    [clojure.tools.namespace.repl :as tools-ns]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.entity :as entity :refer [defentity]]
    [com.fulcrologic.rad.validation :as validation]
    [com.fulcrologic.rad.authorization :as authorization]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr id :uuid
  ::attr/spec uuid?
  ::attr/unique :identity
  ::attr/index? true
  ::attr/required? true
  ::db/id :production)

(defattr legacy-id :int
  ::attr/unique :identity
  ::attr/index? true
  ::db/id :old-database)

(defattr bullshit :string
  ::attr/index? true
  ::db/id :old-database)

(defattr name :string
  ::db/id :production
  ::attr/spec string?
  ::attr/index? true
  ::attr/required? true
  ::validation/validator :spec
  ::validation/error-message "Name must not be empty")

(defattr role :keyword
  ::attr/spec #{:user :admin :support :manager}
  ::pc/input #{::id}
  ::attr/resolver (fn [env input]
                    ;; code to determine role
                    :user))

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
  ::attr/spec inst?
  ;; doesn't go in db, no resolver auto-generation
  ::authorization/required-contexts #{id}
  ::authorization/permissions (fn [context entity]
                                (owned-by context entity)))

(defentity account [id name role last-login legacy-id bullshit]
  ::entity/onCreate (fn [env new-entity] (assoc new-entity :entity/firm (:current/firm env))))

