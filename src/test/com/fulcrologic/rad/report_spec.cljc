(ns com.fulcrologic.rad.report-spec
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.report-options :as ro]
    [fulcro-spec.core :refer [=> assertions component specification]]))

(def seen (atom nil))

(defattr attr :object/x :string
  {ao/identity? true})

(report/defsc-report A [this props]
  {ro/columns             [attr]
   ro/source-attribute    :foo/bar
   ro/row-pk              attr
   ro/initialize-ui-props (fn [cls params] (reset! seen [cls params])
                            {:user-add-on 42})})
(report/defsc-report B [this props]
  {ro/columns             [attr]
   ro/source-attribute    :foo/bar
   ro/row-pk              attr
   ro/initialize-ui-props {:user-add-on 43}})

(specification "Initial state (user-provided fn)"
  (component "user-provided fn"
    (let [is (comp/get-initial-state A {:params 1})]
      (assertions
        "Adds ui controls"
        (:ui/controls is) => []
        "Adds ui current-rows"
        (:ui/current-rows is) => []
        "Adds false busy flag"
        (false? (:ui/busy? is)) => true
        "Adds current page"
        (:ui/current-page is) => 1
        "Adds page count"
        (:ui/page-count is) => 1
        "Adds parameters map"
        (:ui/parameters is) => {}
        "User initialize ui props sees the class and the params"
        (= A (first @seen)) => true
        (= {:params 1} (second @seen)) => true
        "Includes user add-ins"
        (:user-add-on is) => 42)))

  (component "user-provided fn"
    (let [is (comp/get-initial-state B {})]
      (assertions
        "Adds ui controls"
        (:ui/controls is) => []
        "Adds ui current-rows"
        (:ui/current-rows is) => []
        "Adds false busy flag"
        (false? (:ui/busy? is)) => true
        "Adds current page"
        (:ui/current-page is) => 1
        "Adds page count"
        (:ui/page-count is) => 1
        "Adds parameters map"
        (:ui/parameters is) => {}
        "Includes user add-ins"
        (:user-add-on is) => 43))))

(specification "report helper"
  (component "literal map in initialize ui props"
    (let [C  (report/report ::C
               {ro/columns             [attr]
                ro/source-attribute    :foo/bar
                ro/row-pk              attr
                ro/initialize-ui-props {:user-add-on 44}})
          is (comp/get-initial-state C {})]
      (assertions
        "Adds ui controls"
        (:ui/controls is) => []
        "Adds ui current-rows"
        (:ui/current-rows is) => []
        "Adds false busy flag"
        (false? (:ui/busy? is)) => true
        "Adds current page"
        (:ui/current-page is) => 1
        "Adds page count"
        (:ui/page-count is) => 1
        "Adds parameters map"
        (:ui/parameters is) => {}
        "Includes user add-ins"
        (:user-add-on is) => 44)))
  (component "fn in initialize ui props"
    (let [D  (report/report ::D
               {ro/columns             [attr]
                ro/source-attribute    :foo/bar
                ro/row-pk              attr
                ro/initialize-ui-props (fn [cls params] {:user-add-on 44
                                                         :seen        [cls params]})})
          is (comp/get-initial-state D {:x 1})]
      (assertions
        "Adds ui controls"
        (:ui/controls is) => []
        "Adds ui current-rows"
        (:ui/current-rows is) => []
        "Adds false busy flag"
        (false? (:ui/busy? is)) => true
        "Adds current page"
        (:ui/current-page is) => 1
        "Adds page count"
        (:ui/page-count is) => 1
        "Adds parameters map"
        (:ui/parameters is) => {}
        "Includes user add-ins"
        (:user-add-on is) => 44
        (:seen is) => [D {:x 1}]))))

(defsc Obj [this props]
  {:query [:obj/id :obj/a :obj/b]
   :ident :obj/id})

(declare C-Row)
(report/defsc-report C [this props]
  {ro/columns             [attr]
   ro/columns-EQL         {:object/x (comp/get-query Obj)}
   ro/source-attribute    :foo/bar
   ro/row-pk              attr
   ro/initialize-ui-props {:user-add-on 43}})

(declare D-Row)
(report/defsc-report D [this props]
  {ro/columns             [attr]
   ro/columns-EQL         {:object/x [:baz]}
   ro/source-attribute    :foo/bar
   ro/row-pk              attr
   ro/initialize-ui-props {:user-add-on 43}})

(specification "Row query column EQL overrides"
  (let [c-row-options (comp/component-options C-Row)
        d-row-options (comp/component-options D-Row)]
    (assertions
      "Gathers an EQL override from the columns-EQL option"
      ((:query c-row-options) C-Row) => [{:object/x [:obj/id :obj/a :obj/b]}]
      ((:query d-row-options) D-Row) => [{:object/x [:baz]}]
      "If a subquery component is used, the metadata is maintained for normalization"
      (-> ((:query c-row-options) C-Row)
        first
        :object/x
        meta
        :component) => Obj)))
