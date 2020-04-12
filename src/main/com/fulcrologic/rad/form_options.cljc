(ns com.fulcrologic.rad.form-options
  "Documented definitions of the standard form options. These provide easy access to documentation for the options,
  along with preventing spelling errors when using the keys in definitions. Plugin authors are encouraged to
  write their own options files to get the same benefits.

  NOTE to maintainers and Plugin authors: These files must be CLJC, and MUST do a require-macros of themselves.
  to make sure the symbols are resolvable at *compile* time. No dynamic tricks allowed. The form and report macros must be able to resolve the option
  symbols during evaluation. Also, DO NOT require *any* other files in your option files. This ensures
  you don't get circular references, and compile times stay light.")

(def id
  "REQUIRED: The *attribute* that will act as the primary key for this form."
  :com.fulcrologic.rad.form/id)

(def attributes
  "REQUIRED: A vector of *attributes* that should be state-managed (should be saved/loaded). If the attribute
  isn't in this list, it will not be managed."
  :com.fulcrologic.rad.form/attributes)

(def layout
  "OPTIONAL (and advisory): A vector of vectors holding the *qualified keys* of the editable attributes.

   This is intended to represent the *desired* layout of the fields on this form. The inner vectors loosely
   represent rows of the form. UI plugins may choose to ignore this, or may define alternate
   keys to support more complex layout.

   ```
   [[:item/name] [:item/enabled?]
    [:item/description]]
   ```
   "
  :com.fulcrologic.rad.form/layout)

(def title
  "OPTIONAL: The title for the form. Can be a string or a `(fn [form-props])`."
  :com.fulcrologic.rad.form/title)

(def field-visible?
  "ATTRIBUTES KEY. OPTIONAL.

   A boolean or `(fn [this] boolean?)`.

   An attribute-level key that can be used on an attribute to define the default visibility for an attribute on
   forms.  Forms may override the attribute-specific key with `fields-visible?`."
  :com.fulcrologic.rad.form/fields-visible?)

(def fields-visible?
  "OPTIONAL: A map from *qualified keyword* to a boolean or a `(fn [this])`. Makes fields statically or dynamically
   visible on the form. May be given a default on the attribute definition using `::form/field-visible?`"
  :com.fulcrologic.rad.form/fields-visible?)

(def field-style
  "ATTRIBUTE KEY. OPTIONAL: A *qualified keyword*
   that changes the style of the control that is rendered for the given field. If not found, the renderer will
   revert to `:default`.

   Forms can override this with `::form/field-styles`."
  :com.fulcrologic.rad.form/field-style)

(def field-styles
  "OPTIONAL: A map from *qualified keyword* to pick an input style (keyword defined by your rendering plugin).

   Changes the style of the control that is rendered for the given field. If not found, the renderer will
   revert to `:default`.

   Attributes can set a default for this with ::form/field-style."
  :com.fulcrologic.rad.form/field-styles)

(def field-options
  "OPTIONAL: A map from *qualified keyword* to a map of options targeted to the specific UI control for that
  field. The content of the map will be defined by the control in question."
  :com.fulcrologic.rad.form/field-options)

(def validator
  "OPTIONAL: A Fulcro `form-state` validator (see `make-validator`). Will be used to validate all fields on this
  form. Subforms may define a validator, but the master form's validator takes precedence."
  :com.fulcrologic.rad.form/validator)

(def route-prefix
  "OPTIONAL: A string. The string to use as this form's route prefix. If you do not provide this key then the router
   will primarily be usable as a subform, since it will not support routing.
   "
  :com.fulcrologic.rad.form/route-prefix)
