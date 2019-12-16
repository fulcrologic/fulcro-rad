(ns com.fulcrologic.rad.attributes-spec
  (:require
    [fulcro-spec.core :refer [assertions specification when-mocking!]]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr id ::id :uuid {::attr/identity? true})
(defattr email ::email :string {::attr/required? true})
(defattr addresses ::addresses :ref {::attr/cardinality :many})
(defattr street ::street :string {::attr/required? true})

(specification "query->eql"
  (let [query [email {addresses [street]}]
        eql   (attr/query->eql query)]
    (assertions
      "Converts all attributes in a query into proper EQL keywords"
      eql => [::email {::addresses [::street]}])))
