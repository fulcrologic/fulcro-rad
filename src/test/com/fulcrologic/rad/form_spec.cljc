(ns com.fulcrologic.rad.form-spec
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.test :refer [use-fixtures]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.form-options :as fo]
    [fulcro-spec.core :refer [assertions specification behavior when-mocking! component]]
    [taoensso.timbre :as log]))

(declare =>)

(def default-street "111 Main")
(def default-email "nobody@example.net")

(defattr person-id :person/id :uuid {::attr/identity? true})
(defattr person-name :person/name :string {})
(defattr account-id :account/id :uuid {::attr/identity? true})
(defattr spouse :account/spouse :ref {::attr/cardinality :one
                                      ::attr/target      :person/id})
(defattr email :account/email :string {::attr/required?     true
                                       ::form/default-value default-email})
(defattr addresses :account/addresses :ref {::attr/target      :address/id
                                            ::attr/cardinality :many})
(defattr address-id :address/id :uuid {::attr/identity? true})
(defattr street :address/street :string {::attr/required? true})

(defsc AddressForm [_ _]
  {::form/attributes     [street]
   ::form/default-values {:address/street default-street}
   ::attr/target         :address/id
   ::form/id             address-id
   :query                [:mocked/query]})

(defsc PersonForm [_ _]
  {::form/attributes [person-name]
   ::form/id         person-id})

(defsc AccountForm [_ _]
  {::form/attributes     [email addresses spouse]
   ::form/id             account-id
   ::form/default-values {:account/spouse {}}
   ::form/subforms       {:account/addresses {::form/ui AddressForm}
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

(specification "New entity initial state"
  (component "simple entity"
    (let [id (tempid/tempid)
          v  (form/default-state AddressForm id)]
      (assertions
        "Includes the new ID as the ID of the entity"
        (get v :address/id) => id
        "Adds any default fields to the entity"
        (get v :address/street) => default-street)))
  (component "nested entity"
    (let [id (tempid/tempid)
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

(defattr test-id :test/id :uuid {ao/identity? true})
(defattr test-name :test/name :string {ao/identities #{:test/id} ao/required? true})
(defattr test-note :test/note :string {ao/identities #{:test/id}})
(defattr test-marketing :test/marketing? :boolean {ao/identities #{:test/id}})
(defattr test-agree :test/agree? :boolean {ao/identities #{:test/id} ao/required? true})
(defattr test-children :test/children :ref {ao/identities #{:child/id} ao/target :child/id ao/cardinality :many})
(defattr child-id :child/id :uuid {ao/identity? true})
(defattr child-a :child/a :string {ao/identities #{:child/id} ao/required? true})
(defattr child-b :child/b :string {ao/identities #{:child/id}})
(defattr child-node :child/node :ref {ao/identities #{:child/id} ao/target :subchild/id})
(defattr subchild-id :subchild/id :uuid {ao/identity? true})
(defattr subchild-x :subchild/x :string {ao/identities #{:subchild/id}})
(defattr subchild-y :subchild/y :string {ao/identities #{:subchild/id} ao/required? true})

(form/defsc-form SubChildForm [this props]
  {fo/attributes [subchild-x subchild-y]
   fo/id         subchild-id})
(form/defsc-form ChildForm [this props]
  {fo/attributes [child-a child-b child-node]
   fo/id         child-id
   fo/subforms   {:child/node {fo/ui SubChildForm}}})
(form/defsc-form TestForm [this props]
  {fo/attributes [test-name test-note test-marketing test-agree test-children]
   fo/id         test-id
   fo/subforms   {:test/children {fo/ui ChildForm}}})

(specification "find-fields"
  (let [fields (form/find-fields TestForm #(#{:ref :boolean} (get % ::attr/type)))]
    (assertions
      "Finds all of the fields (recursively) that match the predicate."
      fields => #{:test/children :test/marketing? :test/agree? :child/node})))

(specification "optional-fields"
  (let [fields (form/optional-fields TestForm)]
    (assertions
      "Finds all of the fields (recursively) that are used in forms but are not required by the data model."
      ;; Booleans??? What if we just want to leave false == nil?
      fields => #{:test/note :test/children :test/marketing? :child/b :child/node :subchild/x})))

(specification "Form state initialization" :focus
  (component "NEW entities"
    (let [id              (tempid/tempid)
          state-map       {:test/id      {id {}}
                           ::uism/asm-id {:id (uism/new-asm {::uism/asm-id                :id
                                                             ::uism/state-machine-id      (::uism/state-machine-id form/form-machine)
                                                             ::uism/event-data            {}
                                                             ::uism/actor->component-name {:actor/form ::TestForm}
                                                             ::uism/actor->ident          {:actor/form [:test/id id]}})}}
          uism-env        (uism/state-machine-env state-map :id)
          {::uism/keys [state-map] :as after-setup} (#'form/start-create uism-env {:initial-state {:test/name "Bob"}})
          complete-fields (fns/get-in-graph state-map
                            [:test/id id ::fs/config ::fs/complete?])]
      (behavior "Required fields without an initial value are NOT complete"
        (assertions
          (contains? complete-fields :test/agree?) => false))
      (behavior "Optional fields are marked complete"
        (assertions
          (contains? complete-fields :test/note) => true
          (contains? complete-fields :test/marketing?) => true))
      (behavior "Fields with default values are marked complete"
        (assertions
          (contains? complete-fields :test/name) => true)))))