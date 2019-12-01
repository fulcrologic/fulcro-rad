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
  {::db/id          :production
   ::auth/authority :local
   ::attr/index?    true
   ::attr/required? true})

(defattr ::all-accounts :ref
  {::db/id          :production
   ::auth/authority :local
   ::pc/output      [{::all-accounts [::id]}]
   ::attr/resolver  (fn [{:keys [query-params] :as env} input]
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
                           {::all-accounts (mapv (fn [id] {::id id}) ids)})))})

(defattr ::addresses :ref
  {::attr/cardinality :many
   ::attr/references  :com.example.model.address/id
   })

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
