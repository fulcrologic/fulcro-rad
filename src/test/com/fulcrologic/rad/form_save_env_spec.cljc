(ns com.fulcrologic.rad.form-save-env-spec
  "Behavioral tests for the form_save_env helper functions that work with
   RAD form save middleware environment."
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-save-env :as fse]
    [fulcro-spec.core :refer [specification behavior component assertions =>]]))

;; =============================================================================
;; Test Fixtures / Helpers
;; =============================================================================

(defn make-save-env
  "Creates a save middleware env with the required ::form/params structure.
   `overrides` can provide custom values for :id, :master-pk, and :delta."
  ([] (make-save-env {}))
  ([{:keys [id master-pk delta]
     :or   {id        123
            master-pk :user/id
            delta     {}}}]
   {::form/params {::form/id        id
                   ::form/master-pk master-pk
                   ::form/delta     delta}}))

;; =============================================================================
;; new-master-entity? Tests
;; =============================================================================

(specification "new-master-entity?"
  (component "when the master entity has a tempid"
    (let [temp-id  (tempid/tempid)
          save-env (make-save-env {:id temp-id})]
      (behavior "returns true for a tempid"
        (assertions
          (fse/new-master-entity? save-env) => true))))

  (component "when the master entity has a real ID"
    (behavior "returns false for an integer ID"
      (let [save-env (make-save-env {:id 42})]
        (assertions
          (fse/new-master-entity? save-env) => false)))

    (behavior "returns false for a UUID"
      (let [real-uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
            save-env  (make-save-env {:id real-uuid})]
        (assertions
          (fse/new-master-entity? save-env) => false)))

    (behavior "returns false for a string ID"
      (let [save-env (make-save-env {:id "abc-123"})]
        (assertions
          (fse/new-master-entity? save-env) => false))))

  (component "edge cases"
    (behavior "returns false for nil ID"
      (let [save-env (make-save-env {:id nil})]
        (assertions
          (fse/new-master-entity? save-env) => false)))

    (behavior "returns false when params key is missing the id path"
      (let [save-env {::form/params {::form/master-pk :user/id
                                     ::form/delta     {}}}]
        (assertions
          (fse/new-master-entity? save-env) => false)))))

;; =============================================================================
;; master-ident Tests
;; =============================================================================

(specification "master-ident"
  (component "when both master-pk and id are present"
    (behavior "returns a valid ident vector"
      (let [save-env (make-save-env {:master-pk :user/id :id 42})]
        (assertions
          (fse/master-ident save-env) => [:user/id 42])))

    (behavior "works with tempids"
      (let [temp-id  (tempid/tempid)
            save-env (make-save-env {:master-pk :account/id :id temp-id})]
        (assertions
          (fse/master-ident save-env) => [:account/id temp-id])))

    (behavior "works with UUID ids"
      (let [uuid-id  #uuid "550e8400-e29b-41d4-a716-446655440000"
            save-env (make-save-env {:master-pk :item/id :id uuid-id})]
        (assertions
          (fse/master-ident save-env) => [:item/id uuid-id]))))

  (component "when master-pk is missing"
    (behavior "returns nil"
      (let [save-env {::form/params {::form/id    42
                                     ::form/delta {}}}]
        (assertions
          (fse/master-ident save-env) => nil))))

  (component "when id is missing"
    (behavior "returns nil"
      (let [save-env {::form/params {::form/master-pk :user/id
                                     ::form/delta     {}}}]
        (assertions
          (fse/master-ident save-env) => nil))))

  (component "when both master-pk and id are missing"
    (behavior "returns nil"
      (let [save-env {::form/params {::form/delta {}}}]
        (assertions
          (fse/master-ident save-env) => nil))))

  (component "edge cases"
    (behavior "returns nil when params is nil"
      (assertions
        (fse/master-ident {::form/params nil}) => nil))

    (behavior "returns nil when params key is missing entirely"
      (assertions
        (fse/master-ident {}) => nil))))

;; =============================================================================
;; save-delta Tests
;; =============================================================================

(specification "save-delta"
  (component "when delta is present"
    (behavior "returns the full delta map"
      (let [delta    {[:user/id 1]    {:user/name {:before "Old" :after "New"}}
                      [:address/id 2] {:address/street {:before nil :after "123 Main"}}}
            save-env (make-save-env {:delta delta})]
        (assertions
          (fse/save-delta save-env) => delta)))

    (behavior "returns empty map when delta is empty"
      (let [save-env (make-save-env {:delta {}})]
        (assertions
          (fse/save-delta save-env) => {}))))

  (component "when delta is missing"
    (behavior "returns nil when delta key is not present"
      (let [save-env {::form/params {::form/id        42
                                     ::form/master-pk :user/id}}]
        (assertions
          (fse/save-delta save-env) => nil))))

  (component "edge cases"
    (behavior "returns nil when params is empty"
      (assertions
        (fse/save-delta {::form/params {}}) => nil))

    (behavior "returns nil when params is nil"
      (assertions
        (fse/save-delta {::form/params nil}) => nil))

    (behavior "returns nil when env has no params key"
      (assertions
        (fse/save-delta {}) => nil))))

;; =============================================================================
;; save-delta-idents Tests
;; =============================================================================

(specification "save-delta-idents"
  (component "when delta contains idents"
    (behavior "returns a set of all idents being saved"
      (let [delta    {[:user/id 1]    {:user/name {:before "Old" :after "New"}}
                      [:address/id 2] {:address/street {:before nil :after "123 Main"}}
                      [:phone/id 3]   {:phone/number {:before nil :after "555-1234"}}}
            save-env (make-save-env {:delta delta})]
        (assertions
          (fse/save-delta-idents save-env) => #{[:user/id 1] [:address/id 2] [:phone/id 3]})))

    (behavior "returns a single ident when delta has one entry"
      (let [delta    {[:user/id 42] {:user/name {:before "A" :after "B"}}}
            save-env (make-save-env {:delta delta})]
        (assertions
          (fse/save-delta-idents save-env) => #{[:user/id 42]})))

    (behavior "returns empty set when delta is empty"
      (let [save-env (make-save-env {:delta {}})]
        (assertions
          (fse/save-delta-idents save-env) => #{}))))

  (component "when delta contains mixed tempids and real IDs"
    (let [temp-id  (tempid/tempid)
          delta    {[:user/id temp-id] {:user/name {:before nil :after "New User"}}
                    [:user/id 42]      {:user/name {:before "Old" :after "Updated"}}}
          save-env (make-save-env {:delta delta})]
      (behavior "includes both tempid and real ID idents"
        (assertions
          (fse/save-delta-idents save-env) => #{[:user/id temp-id] [:user/id 42]}))))

  (component "edge cases"
    (behavior "returns empty set when delta is missing"
      (let [save-env {::form/params {::form/id        42
                                     ::form/master-pk :user/id}}]
        (assertions
          (fse/save-delta-idents save-env) => #{})))

    (behavior "returns empty set when params is nil"
      (assertions
        (fse/save-delta-idents {::form/params nil}) => #{}))

    (behavior "returns empty set when env is empty"
      (assertions
        (fse/save-delta-idents {}) => #{}))))
