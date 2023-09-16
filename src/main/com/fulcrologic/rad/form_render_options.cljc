(ns com.fulcrologic.rad.form-render-options
  (:require
    [com.fulcrologic.rad.options-util :refer [defoption]]))

(defoption style
  "ID Attribute or FORM option. The style of form to render. This can affect things like rendering,
   state machine behavior, etc. Can be a keyword or a `(fn [attr rendering-env] keyword?)`.
   (rendering-env e.g. contains ::form/form-instance, parent-relation, etc)."
)

(defoption header-style
  "Attribute option. The style of header to generate when rendering the attribute. Not all attributes
   have headers. Usually just forms (id attributes) and refs. Can also be a
   (fn [attr rendering-env] keyword?)")

(defoption footer-style "Attribute option. The style of footer to generate when rendering the attribute. Not all attributes
   have footers. Usually just forms (id attributes) and refs. Can also be a
   (fn [attr rendering-env] keyword?)")

(defoption layout "ID Attribute option. The layout of the fields on the generated form.")

(defoption options "ID Attribute option. A map of style-specific form generation options.")
