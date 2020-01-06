(ns com.fulcrologic.rad.form-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.form :as form]
    [clojure.test :refer [use-fixtures]]
    [fulcro-spec.core :refer [assertions specification when-mocking! component]]))

(declare =>)

(def default-street "111 Main")
(def default-email "nobody@example.net")

(defattr person-id :person/id :uuid {::attr/identity? true})
(defattr person-name :person/name :string {})
(defattr account-id :account/id :uuid {::attr/identity? true})
(defattr spouse :account/spouse :ref {::attr/cardinality :one
                                      ::attr/target      :person/id})
(defattr email :account/email :string {::attr/required?     true
                                       ::attr/default-value default-email})
(defattr addresses :account/addresses :ref {::attr/target      :address/id
                                            ::attr/cardinality :many})
(defattr address-id :address/id :uuid {::attr/identity? true})
(defattr street :address/street :string {::attr/required? true})

(use-fixtures :once
  (fn [tests]
    (attr/clear-registry!)
    (attr/register-attributes! [person-id person-name account-id spouse email addresses address-id street])
    (tests)
    (attr/clear-registry!)))

(defsc AddressForm [_ _]
  {::form/attributes [street]
   ::form/default    {:address/street default-street}
   ::attr/target     :address/id
   ::form/id         address-id
   :query            [:mocked/query]})

(defsc PersonForm [_ _]
  {::form/attributes [person-name]
   ::form/id         person-id})

(defsc AccountForm [_ _]
  {::form/attributes [email addresses spouse]
   ::form/id         account-id
   ::form/default    {:account/spouse {}}
   ::form/subforms   {:account/addresses {::form/ui AddressForm}
                      :account/spouse    {::form/ui PersonForm}}})

(specification "attributes->form-query"
  (component "Single-level query conversion"
    (let [eql       (form/form-options->form-query (comp/component-options AddressForm))
          eql-items (set eql)]
      (assertions
        "Returns an EQL vector"
        (vector? eql) => true
        "Includes the ID of the form in the query"
        (contains? eql-items :address/id) => true
        "Includes the ASM table"
        (contains? eql-items [::uism/asm-id '_]) => true
        "Includes the form config join"
        (contains? eql-items fs/form-config-join) => true
        "Includes the scalar attribute keys"
        (contains? eql-items :address/street) => true)))
  (component "Nested query conversion"
    (let [eql       (form/form-options->form-query (comp/component-options AccountForm))
          eql-items (set eql)]
      (assertions
        "Includes a join to the proper sub-query"
        (contains? eql-items {:account/addresses [:mocked/query]}) => true))))

(specification "New entity initial state" :focus
  (component "simple entity"
    (let [id 1
          v  (form/default-state AddressForm id)]
      (assertions
        "Includes the new ID as the ID of the entity"
        (get v :address/id) => id
        "Adds any default fields to the entity"
        (get v :address/street) => default-street)))
  (component "nested entity"
    (let [id 1
          v  (form/default-state AccountForm id)]
      (assertions
        "Includes the new ID as the ID of the entity"
        (get v :account/id) => id
        "Adds default values from attribute declaration to the entity"
        (get v :account/email) => default-email
        "Includes a map with a new tempid for to-one entities that have a default value"
        (some-> (get-in v [:account/spouse :person/id]) (tempid/tempid?)) => true
        "Includes an empty vector for any to-many relation that has no default"
        (get v :account/addresses) => []))))
