(ns com.fulcrologic.rad.form-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.form :as form]
    [fulcro-spec.core :refer [assertions specification when-mocking! component]]))

(defattr account-id :account/id :uuid {::attr/identity? true})
(defattr email :account/email :string {::attr/required? true})
(defattr addresses :account/addresses :ref {::attr/cardinality :many})
(defattr address-id :address/id :uuid {::attr/identity? true})
(defattr street :address/street :string {::attr/required? true})

(defsc AddressForm [_ _]
  {::form/attributes [street]
   ::form/id         address-id
   :query            [:mocked/query]})

(defsc AccountForm [_ _]
  {::form/attributes [email addresses]
   ::form/id         account-id
   ::form/subforms   {:account/addresses {::form/ui AddressForm}}})

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
