(ns com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls
  "This ns requires all semantic UI renderers for form controls and includes a map that can be installed to
   set SUI as the default control set."
  (:require
    [com.fulcrologic.rad.rendering.semantic-ui.form :as sui-form]
    [com.fulcrologic.rad.rendering.semantic-ui.report :as sui-report]
    [com.fulcrologic.rad.rendering.semantic-ui.boolean-field :as boolean-field]
    [com.fulcrologic.rad.rendering.semantic-ui.decimal-field :as decimal-field]
    [com.fulcrologic.rad.rendering.semantic-ui.int-field :as int-field]
    [com.fulcrologic.rad.rendering.semantic-ui.boolean-input :as boolean-input]
    [com.fulcrologic.rad.rendering.semantic-ui.instant-field :as instant]
    [com.fulcrologic.rad.rendering.semantic-ui.enumerated-field :as enumerated-field]
    [com.fulcrologic.rad.rendering.semantic-ui.blob-field :as blob-field]
    [com.fulcrologic.rad.rendering.semantic-ui.text-field :as text-field]))

(def all-controls
  {;; Form-related UI
   ;; completely configurable map...element types are malleable as are the styles. Plugins will need to doc where
   ;; they vary from the "standard" set.
   :com.fulcrologic.rad.form/element->style->layout
   {:form-container      {:default sui-form/standard-form-container
                          :file-as-icon sui-form/file-icon-renderer}
    :form-body-container {:default sui-form/standard-form-layout-renderer}
    :ref-container       {:default sui-form/standard-ref-container
                          :file    sui-form/file-ref-container}}

   :com.fulcrologic.rad.form/type->style->control
   {
    :text          {:default text-field/render-field}
    :enum          {:default enumerated-field/render-field}
    :string        {:default                              text-field/render-field
                    :viewable-password                    text-field/render-viewable-password
                    :password                             text-field/render-password
                    :sorted-set                           text-field/render-dropdown
                    #_#_:com.fulcrologic.rad.blob/image-upload blob-field/render-image-upload
                    :com.fulcrologic.rad.blob/file-upload blob-field/render-file-upload
                    }
    :int           {:default int-field/render-field}
    :decimal       {:default decimal-field/render-field}
    :boolean       {:default boolean-field/render-field}
    :instant       {:default      instant/render-field
                    :date-at-noon instant/render-date-at-noon-field}
    :entity-picker {:default sui-form/ui-render-entity-picker}}

   ;; Report-related controls
   :com.fulcrologic.rad.report/style->layout
   {:default sui-report/ui-render-layout}

   :com.fulcrologic.rad.report/parameter-type->style->input
   {:boolean {:default boolean-input/render-input}}})

