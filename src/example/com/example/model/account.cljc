(ns com.example.model.account
  (:refer-clojure :exclude [name])
  (:require
    #?(:clj
       [com.wsscode.pathom.connect :as pc :refer [defmutation]]
       :cljs
       [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]])
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.entity :as entity :refer [defentity]]
    [com.fulcrologic.rad.validation :as validation]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.authorization :as auth]
    [taoensso.timbre :as log]))

(defattr id :uuid
  ::attr/spec uuid?
  ::attr/unique :identity
  ::attr/index? true
  ::attr/required? true
  ::auth/authority :local
  ::db/id :production)

(defattr legacy-id :int
  ::attr/unique :identity
  ::attr/index? true
  ::auth/authority :local
  ::db/id :old-database)

(defattr bullshit :string
  ::attr/index? true
  ::db/id :old-database)

(defattr name :string
  ::db/id :production
  ::auth/authority :local
  ::attr/spec string?
  ::attr/index? true
  ::attr/required? true
  ::validation/validator :spec
  ::validation/error-message "Name must not be empty")

(defattr role :keyword
  ::attr/spec #{:user :admin :support :manager}
  ::auth/authority :local
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
  ::auth/authority :local
  ::auth/required-contexts #{id}
  ::auth/permissions (fn [context entity]
                       (owned-by context entity)))

(defentity account [id name role last-login legacy-id bullshit]
  ::auth/authority :local
  ::entity/beforeCreate (fn [env new-entity]
                          #_(attr/set! new-entity company (:current/firm env))))

#?(:clj
   (defmutation login [env {:keys [username password]}]
     {::pc/params #{:username :password}}
     (log/info "Attempt to login for " username)
     {::auth/provider  :local
      ::auth/real-user "Tony"})
   :cljs
   (defmutation login [params]
     (ok-action [{:keys [app result]}]
       (log/info "Login result" result)
       (auth/logged-in! app :local))
     (remote [env]
       (-> env
         (m/returning auth/Session)))))
