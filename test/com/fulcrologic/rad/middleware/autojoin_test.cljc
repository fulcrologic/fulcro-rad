(ns com.fulcrologic.rad.middleware.autojoin-test
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.middleware.autojoin :as subj]
    [com.fulcrologic.rad.middleware.autojoin-options :as ajo]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(defattr person-id :person/id :int
  {ao/identity? true})

(defattr person-list :person/list :ref
  {ao/identity?   true
   ao/identities  #{:person/id}
   ao/cardinality :one
   ao/target      :list/id})

(defattr list-id :list/id :int
  {ao/identity? true})

(defattr list-owner :list/owner :ref
  {ao/identities         #{:list/id}
   ao/cardinality        :one
   ajo/forward-reference :person/list
   ao/target             :person/id})

(defattr list-items :list/items :ref
  {ao/identity?   true
   ao/identities  #{:list/id}
   ao/cardinality :many
   ao/target      :item/id})

(defattr item-id :item/id :int
  {ao/identity? true})

(defattr item-label :item/label :string
  {ao/identities #{item-id}})

(defattr item-list :item/list :ref
  {ao/identities         #{item-id}
   ajo/forward-reference :list/items})

(def attrs [person-id person-list list-owner list-id list-items item-id item-label item-list])
(def k->a (attr/attribute-map attrs))

(specification "ensure-idents"
  (assertions
    "single ident"
    (#'subj/ensure-ident [:a/id 1]) => [:a/id 1]
    "nil"
    (#'subj/ensure-ident nil) => nil
    "one map"
    (#'subj/ensure-ident {:foo/id 1}) => [:foo/id 1]))

(specification "keys-to-autojoin"
  (assertions
    "Finds the virtual edges to fix"
    (subj/keys-to-autojoin attrs) => #{:item/list :list/owner}))

(specification "autojoin"
  (let [base-env {::attr/key->attribute k->a
                  ::attr/all-attributes attrs}
        env      (fn [delta] (assoc base-env
                               ::form/params {::form/id    1
                                              ::form/pk    :item/id
                                              ::form/delta delta}))
        delta-of #(get-in % [::form/params ::form/delta])]
    (component "CREATE"
      (component "to-many, with existing owner"
        (let [tid (tempid/tempid)]
          (assertions
            (delta-of
              (subj/autojoin
                (env
                  {[:item/id tid] {:item/list  {:after [:list/id 1]}
                                   :item/label {:after "Foo"}}})))
            => {[:item/id tid] {:item/label {:after "Foo"}}
                [:list/id 1]   {:list/items {:after [[:item/id tid]]}}})))
      (component "to-one, with existing owner"
        (let [tid (tempid/tempid)]
          (assertions
            (delta-of
              (subj/autojoin
                (env
                  {[:list/id tid] {:list/owner {:after [:person/id 1]}}})))
            => {[:list/id tid] {}
                [:person/id 1] {:person/list {:after [:list/id tid]}}}))))
    (component "to-many, move item to new list (even with other list edits)"
      (assertions
        (delta-of
          (subj/autojoin
            (env
              {[:item/id 1] {:item/list {:before [:list/id 2] :after [:list/id 1]}}
               [:list/id 1] {:list/items {:before [[:item/id 42]] :after [[:item/id 99]]}}})))
        => {[:item/id 1] {}
            [:list/id 2] {:list/items {:before [[:item/id 1]]}}
            [:list/id 1] {:list/items {:before [[:item/id 42]] :after [[:item/id 99] [:item/id 1]]}}}))
    (component "to-many, remove item from list"
      (assertions
        (delta-of
          (subj/autojoin
            (env
              {[:item/id 1] {:item/list {:before [:list/id 2]}}})))
        => {[:item/id 1] {}
            [:list/id 2] {:list/items {:before [[:item/id 1]]}}}))
    (component "to-one, delete list owner"
      (assertions
        (delta-of
          (subj/autojoin
            (env
              {[:list/id 1] {:list/owner {:before [:person/id 2]}}})))
        => {[:list/id 1]   {}
            [:person/id 2] {:person/list {:before [:list/id 1]}}}))
    (component "to-one, change list owner"
      (assertions
        (delta-of
          (subj/autojoin
            (env
              {[:list/id 1] {:list/owner {:before [:person/id 2] :after [:person/id 1]}}})))
        => {[:list/id 1]   {}
            [:person/id 1] {:person/list {:after [:list/id 1]}}
            [:person/id 2] {:person/list {:before [:list/id 1]}}}))))
