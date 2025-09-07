(ns com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls
  "This ns requires all semantic UI renderers for form controls and includes a map that can be installed to
   set SUI as the default control set."
  (:require
    [com.fulcrologic.rad.rendering.semantic-ui.form :as sui-form]
    [com.fulcrologic.rad.rendering.semantic-ui.container :as sui-container]
    [com.fulcrologic.rad.rendering.semantic-ui.entity-picker :as entity-picker]
    [com.fulcrologic.rad.rendering.semantic-ui.report :as sui-report]
    [com.fulcrologic.rad.rendering.semantic-ui.boolean-field :as boolean-field]
    [com.fulcrologic.rad.rendering.semantic-ui.decimal-field :as decimal-field]
    [com.fulcrologic.rad.rendering.semantic-ui.int-field :as int-field]
    [com.fulcrologic.rad.rendering.semantic-ui.double-field :as double-field]
    [com.fulcrologic.rad.rendering.semantic-ui.controls.boolean-control :as boolean-input]
    [com.fulcrologic.rad.rendering.semantic-ui.controls.action-button :as action-button]
    [com.fulcrologic.rad.rendering.semantic-ui.controls.text-input :as text-input]
    [com.fulcrologic.rad.rendering.semantic-ui.controls.instant-inputs :as instant-input]
    [com.fulcrologic.rad.rendering.semantic-ui.controls.pickers :as picker-controls]
    [com.fulcrologic.rad.rendering.semantic-ui.instant-field :as instant]
    [com.fulcrologic.rad.rendering.semantic-ui.enumerated-field :as enumerated-field]
    [com.fulcrologic.rad.rendering.semantic-ui.blob-field :as blob-field]
    [com.fulcrologic.rad.rendering.semantic-ui.autocomplete :as autocomplete]
    [com.fulcrologic.rad.rendering.semantic-ui.text-field :as text-field]
    [com.fulcrologic.rad.rendering.semantic-ui.currency-field :as currency-field]))

(def all-controls
  {;; Form-related UI
   ;; completely configurable map...element types are malleable as are the styles. Plugins will need to doc where
   ;; they vary from the "standard" set.
   :com.fulcrologic.rad.form/element->style->layout
   {:form-container      {:default      sui-form/standard-form-container
                          :file-as-icon sui-form/file-icon-renderer}
    :form-body-container {:default sui-form/standard-form-layout-renderer}
    :ref-container       {:default sui-form/standard-ref-container
                          :file    sui-form/file-ref-container}
    ;; If you set `fo/confirm :async` on a form, this UI is used for confirmation instead:
    :async-abandon-modal {:default sui-form/standard-abandon-modal}}

   :com.fulcrologic.rad.form/type->style->control
   {:text    {:default text-field/render-field}
    :enum    {:default      enumerated-field/render-field
              :autocomplete autocomplete/render-autocomplete-field}
    :string  {:default                               text-field/render-field
              :picker                                enumerated-field/render-field
              :multi-line                            text-field/render-multi-line
              :autocomplete                          autocomplete/render-autocomplete-field
              :viewable-password                     text-field/render-viewable-password
              :password                              text-field/render-password
              :sorted-set                            text-field/render-dropdown
              :com.fulcrologic.rad.blob/file-upload  blob-field/render-file-upload
              :com.fulcrologic.rad.blob/image-upload blob-field/render-image-upload}
    :int     {:default int-field/render-field
              :picker  enumerated-field/render-field}
    :keyword {:default enumerated-field/render-field}
    :long    {:default int-field/render-field
              :picker  enumerated-field/render-field}
    :decimal {:default decimal-field/render-field
              :USD     currency-field/render-field}
    :double  {:default double-field/render-field}
    :boolean {:default boolean-field/render-field}
    :instant {:default      instant/render-field
              :picker       enumerated-field/render-field
              :date-at-noon instant/render-date-at-noon-field}
    :ref     {:pick-one  entity-picker/to-one-picker
              :pick-many entity-picker/to-many-picker}}

   ;; Report-related controls
   :com.fulcrologic.rad.report/style->layout
   {:default sui-report/render-table-report-layout
    :list    sui-report/render-list-report-layout}

   :com.fulcrologic.rad.report/control-style->control
   {:default sui-report/render-standard-controls}

   :com.fulcrologic.rad.report/row-style->row-layout
   {:default sui-report/render-table-row
    :list    sui-report/render-list-row}

   :com.fulcrologic.rad.container/style->layout
   {:default sui-container/render-container-layout}

   :com.fulcrologic.rad.control/type->style->control
   {:boolean {:toggle  boolean-input/render-control
              :default boolean-input/render-control}
    :string  {:default text-input/render-control
              :search  text-input/render-control}
    :instant {:default       instant-input/date-time-control
              :starting-date instant-input/midnight-on-date-control
              :ending-date   instant-input/midnight-next-date-control
              :date-at-noon  instant-input/date-at-noon-control}
    :picker  {:default picker-controls/render-control}
    :button  {:default action-button/render-control}}})

