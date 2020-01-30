(ns com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls
  "This ns requires all semantic UI renderers for form controls and includes a map that can be installed to
   set SUI as the default control set."
  (:require
    [com.fulcrologic.rad.rendering.semantic-ui.form :as sui-form]
    [com.fulcrologic.rad.rendering.semantic-ui.report :as sui-report]
    [com.fulcrologic.rad.rendering.semantic-ui.boolean-field :as boolean-field]
    [com.fulcrologic.rad.rendering.semantic-ui.money-field :as money-field]
    [com.fulcrologic.rad.rendering.semantic-ui.int-field :as int-field]
    [com.fulcrologic.rad.rendering.semantic-ui.boolean-input :as boolean-input]
    [com.fulcrologic.rad.rendering.semantic-ui.instant-field :as instant]
    [com.fulcrologic.rad.rendering.semantic-ui.enumerated-field :as enumerated-field]
    [com.fulcrologic.rad.rendering.semantic-ui.text-field :as text-field]))

(def all-controls
  {;; Form-related UI
   :com.fulcrologic.rad.form/style->layout                  {:default sui-form/ui-render-layout}
   :com.fulcrologic.rad.form/type->style->control           {:layout  {:default sui-form/ui-render-layout}
                                                             :text    {:default text-field/render-field}
                                                             :enum    {:default enumerated-field/render-field}
                                                             :string  {:default text-field/render-field}
                                                             :int     {:default int-field/render-field}
                                                             :money   {:default money-field/render-field}
                                                             :boolean {:default boolean-field/render-field}
                                                             :instant {:default instant/render-field}
                                                             :entity-picker {:default sui-form/ui-render-entity-picker}}

   ;; Report-related controls
   :com.fulcrologic.rad.report/style->layout                {:default sui-report/ui-render-layout}
   :com.fulcrologic.rad.report/parameter-type->style->input {:boolean {:default boolean-input/render-input}}})

