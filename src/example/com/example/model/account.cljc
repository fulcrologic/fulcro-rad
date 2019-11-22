(ns com.example.model.account
  (:refer-clojure :exclude [name])
  (:require
    #?@(:clj
        [[datomic.api :as d]
         [com.wsscode.pathom.connect :as pc :refer [defmutation]]]
        :cljs
        [[com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]])
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.entity :as entity :refer [defentity]]
    [com.fulcrologic.rad.validation :as validation]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.authorization :as auth]
    [taoensso.timbre :as log]))

(defattr ::id :uuid
  {::attr/unique    :identity
   ::attr/index?    true
   ::attr/required? true
   ::auth/authority :local
   ::db/id          :production})

(defattr ::email :string
  {::db/id          :production
   ::attr/index?    true
   ::attr/required? true})

(defattr ::active? :boolean
  {::db/id              :production
   ::form/default-value true
   ::attr/index?        true
   ::attr/required?     true})

(defattr ::password :password
  {::db/id                   :production
   ;; TODO: context sense to allow for owner to write
   ::auth/permissions        (fn [env] #{})
   ::attr/encrypt-iterations 100
   ::attr/required?          true})

(defattr ::name :string
  {::db/id                    :production
   ::auth/authority           :local
   ::attr/spec                string?
   ::attr/index?              true
   ::attr/required?           true
   ::validation/validator     :spec
   ::validation/error-message "Name must not be empty"})

(defattr ::role :keyword
  {::attr/spec      #{:user :admin :support :manager}
   ::auth/authority :local
   ::pc/input       #{::id}
   ::attr/resolver  (fn [env input]
                      ;; code to determine role
                      :user)})

(defn admin? [context]
  (= :admin (::role context)))

(defn support? [context]
  (= :support (::role context)))

(defn owned-by [{::entity/keys [primary-key]
                 ::auth/keys   [context] :as env}]
  (cond
    (or (= (::id context) primary-key)
      (admin? context))
    #{:read :write}

    (support? context)
    #{:read}

    :else
    #{}))

(defattr ::last-login :inst
  {::attr/spec              inst?
   ;; doesn't go in db, no resolver auto-generation
   ::attr/resolver          (fn [env input] #?(:clj {::last-login (java.util.Date.)}))
   ::report/column-header   "Last Login"
   ::auth/authority         :local
   ::auth/required-contexts #{::id}
   ::auth/permissions       (fn [env] (owned-by env))})

(defattr ::all-accounts :ref
  {::db/id            :production
   ;;::auth/permissions (fn [env] (admin? env))
   ::attr/cardinality :many
   ::attr/target      :com.example.model.account/id
   ::auth/authority   :local
   ::pc/output        [{::all-accounts [::id]}]
   ::attr/resolver    (fn [{:keys [query-params] :as env} input]
                        #?(:clj
                           (let [{:keys [db]} env
                                 ids (if (:ui/show-inactive? query-params)
                                       (d/q [:find '[?uuid ...]
                                             :where
                                             ['?dbid ::id '?uuid]] db)
                                       (d/q [:find '[?uuid ...]
                                             :where
                                             ['?dbid ::active? true]
                                             ['?dbid ::id '?uuid]] db))]
                             (log/spy :info query-params)
                             {::all-accounts (mapv (fn [id] {::id id}) ids)})))})

;; List just persisted ones that "group together" in storage. Could cross storage
;; boundaries, though, so not sure how well the concept works in total.
;; TODO: Move virtual attributes out of the entity, and auto-find them in registry for resolver generation
(defentity account [::id ::name ::email ::password ::last-login ::role ::active?])

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
