(ns com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls
  "This ns requires all semantic UI renderers for form controls and includes a map that can be installed to
   set SUI as the default control set."
  (:require
    [com.fulcrologic.rad.rendering.semantic-ui.form :as sui-form]
    [com.fulcrologic.rad.rendering.semantic-ui.report :as sui-report]
    [com.fulcrologic.rad.rendering.semantic-ui.boolean-input :as boolean-input]
    [com.fulcrologic.rad.rendering.semantic-ui.instant-field :as instant]
    [com.fulcrologic.rad.rendering.semantic-ui.text-field :as text-field]))

(def all-controls
  {:com.fulcrologic.rad.form/style->layout        {:default sui-form/ui-render-layout}
   :com.fulcrologic.rad.form/type->style->control {:layout  {:default sui-form/ui-render-layout}
                                                   :text    {:default text-field/render-field}
                                                   :instant {:default instant/render-field}}
   :com.fulcrologic.rad.input/type->style->input  {:boolean {:default boolean-input/render-input}}})

