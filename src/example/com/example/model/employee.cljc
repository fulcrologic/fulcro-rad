(ns com.example.model.employee
  (:refer-clojure :exclude [name])
  (:require
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.entity :as entity :refer [defentity]]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.entity :as entity]
    [com.fulcrologic.rad.validation :as validation]
    [com.fulcrologic.rad.authorization :as authorization]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr id :uuid
  ::attr/unique :identity
  ::attr/required? true
  ::db/id :production)

(defattr account :ref
  ::attr/cardinality :one
  ::attr/target :com.example.model.account/id
  ::attr/component? false
  ::db/id :production)

(defattr first-name :string
  ::attr/index? true
  ::attr/required? true
  ::db/id :production
  ::form/label "First Name"
  ::validation/validator (fn [v] (and (string? v) (seq v)))
  ::validation/error-message "First Name must not be empty")

(defattr last-name :string
  ::attr/index? true
  ::attr/required? true
  ::db/id :production
  ::form/label "Last Name"
  ::validation/validator (fn [v] (and (string? v) (seq v)))
  ::validation/error-message "Last Name must not be empty")

(defattr full-name :string
  ::form/label "Name"
  ::attr/virtual? true
  ::pc/input #{::first-name ::last-name}
  ::attr/resolver (fn [_ {::keys [first-name last-name]}]
                    (str first-name " " last-name)))

(defattr hours-worked-today :int
  ::form/label "Hours Worked Today"
  ::attr/virtual? true
  ::pc/input #{::id :local/date}
  ;; Authorization policy can be at attribute or entity level
  ;; Asking for the employee ID at the *context* level means that the attribute has to be part of the auth context
  ;; (it isn't the id of the entity being evaluated). So, in this case we're saying that when an employee logs into
  ;; the system (which will involve an account), then something allows us to calculate (on login) that their employee
  ;; ID should be associated with the auth context. The optional nature means that the context item(s) are inserted
  ;; if available, but will not block UI updates waiting for authorization to resolve. In this attribute's case the
  ;; requirement for a role ensures they have logged in, and the login process would add employee id to the auth
  ;; context iff they are an employee.
  ::authorization/policy {::authorization/required-contexts #{:com.example.model.account/role}
                          ::authorization/optional-contexts #{::id}
                          ::authorization/permissions       (fn [{:com.example.model.account/keys [role] :as context} entity]
                                                              (when (or (#{:manager :admin} role) (= (id context) (id entity)))
                                                                #{:read}))}
  ::attr/resolver (fn [{:keys [db]} {::keys      [id]
                                     :local/keys [date]}]
                    ;; code to figure out hours based on inputs
                    9))

(defentity employee [id account first-name last-name full-name hours-worked-today])
