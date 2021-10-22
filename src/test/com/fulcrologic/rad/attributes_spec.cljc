(ns com.fulcrologic.rad.attributes-spec
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [fulcro-spec.core :refer [assertions specification component =>]]
    [taoensso.timbre :as log]))

(specification "valid-value?"
  (let [no-valid-fn     (attr/new-attribute :sample/a :string {})
        w-valid-fn      (attr/new-attribute :sample/b :string {ao/valid? (fn [_ _ _] :computed-boolean)})
        req-no-valid-fn (attr/new-attribute :sample/c :string {ao/required? true})
        req-w-valid-fn  (attr/new-attribute :sample/d :string {ao/required? true
                                                               ao/valid?    (fn [_ _ _] :computed-boolean)})]
    (component "Attribute that ISN'T required"
      (assertions
        "Returns true for missing attributes, when there is no validator"
        (attr/valid-value? no-valid-fn nil {} :sample/a) => true
        "Returns true for empty string value, when there is no validator"
        (attr/valid-value? no-valid-fn "" {} :sample/a) => true
        "Returns true if the value is missing (does not run validation function for missing things)"
        (attr/valid-value? w-valid-fn nil {} :sample/b) => true
        "Returns the value of the validation function when a non-nil value is present"
        (attr/valid-value? w-valid-fn "" {} :sample/b) => :computed-boolean
        (attr/valid-value? w-valid-fn false {} :sample/b) => :computed-boolean
        (attr/valid-value? w-valid-fn "asd" {} :sample/b) => :computed-boolean
        (attr/valid-value? w-valid-fn 55 {} :sample/b) => :computed-boolean))

    (component "Attribute that IS required"
      (assertions
        "Returns false for required, missing attributes, with no validator"
        (attr/valid-value? req-no-valid-fn nil {} :sample/c) => false
        "Returns false for required, empty string value, with no validator"
        (attr/valid-value? req-no-valid-fn "" {} :sample/c) => false
        "Returns validation function value, no matter the value (even if nil)"
        (attr/valid-value? req-w-valid-fn nil {} :sample/d) => :computed-boolean
        (attr/valid-value? req-w-valid-fn "" {} :sample/d) => :computed-boolean
        (attr/valid-value? req-w-valid-fn "asd" {} :sample/d) => :computed-boolean
        (attr/valid-value? req-w-valid-fn 55 {} :sample/d) => :computed-boolean))))
