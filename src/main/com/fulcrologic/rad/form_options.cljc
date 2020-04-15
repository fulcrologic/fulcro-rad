(ns com.fulcrologic.rad.form-options
  "Documented definitions of the standard form options. These provide easy access to documentation for the options,
  along with preventing spelling errors when using the keys in definitions. Plugin authors are encouraged to
  write their own options files to get the same benefits.

  Forms currently require a minimum of two options:

  * `id`
  * `attributes`

  NOTE to maintainers and Plugin authors: These files must be CLJC to make sure the symbols are resolvable
  at *compile* time. No dynamic tricks please. The form and report macros must be able to resolve the option
  symbols during evaluation.")

(def id
  "REQUIRED: The *attribute* that will act as the primary key for this form."
  :com.fulcrologic.rad.form/id)

(def attributes
  "REQUIRED: A vector of *attributes* that should be state-managed (should be saved/loaded). If the attribute
  isn't in this list, it will not be managed."
  :com.fulcrologic.rad.form/attributes)

(def layout
  "OPTIONAL (may not be supported by your rendering plugin): A vector of vectors holding the
  *qualified keys* of the editable attributes.

   This is intended to represent the *desired* layout of the fields on this form. The inner vectors loosely
   represent rows of the form. UI plugins may choose to ignore this, or may define alternate
   keys to support more complex layout.

   ```
   [[:item/name] [:item/enabled?]
    [:item/description]]
   ```
   "
  :com.fulcrologic.rad.form/layout)

(def tabbed-layout
  "OPTIONAL, may not be supported by your rendering plugin.

  A description of a layout that will place fields in tabs to reduce visual clutter. The layout
  specification is:

  ```
  [\"Basic Info\"
   [[:employee/first-name :employee/last-name :employee/email]
    [:employee/address]
    [:employee/code :employee/enabled?]
    [:employee/start-date :employee/hourly-wage :employee/ssn]]
   \"Notes\"
   [[:employee/notes]]
   \"Permissions\"
   [[:employee/permissions]]]
  ```

  Where the top-level vector is a sequence of strings interposed with field layouts.
  "
  :com.fulcrologic.rad.form/tabbed-layout)

(def title
  "OPTIONAL: The title for the form. Can be a string or a `(fn [form-instance form-props])`."
  :com.fulcrologic.rad.form/title)

(def field-visible?
  "ATTRIBUTES KEY. OPTIONAL.

   A boolean or `(fn [this] boolean?)`.

   An attribute-level key that can be used on an attribute to define the default visibility for an attribute on
   forms.  Forms may override the attribute-specific key with `fields-visible?`."
  :com.fulcrologic.rad.form/field-visible?)

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

   Attributes can set a default for this with ::form/field-style.

   See also `field-options`."
  :com.fulcrologic.rad.form/field-styles)

(def field-options
  "OPTIONAL: A map from *qualified keyword* to a map of options targeted to the specific UI control for that
  field. The content of the map will be defined by the control in question.

  See also the `picker-options` namespace."
  :com.fulcrologic.rad.form/field-options)

(def field-labels
  "OPTIONAL: A map from *qualified keyword* to a string label for that field, or a `(fn [this] string?)` that can
  generate the label. Can be overridden by ::form/field-label on the attribute."
  :com.fulcrologic.rad.form/field-labels)

(def validator
  "OPTIONAL: A Fulcro `form-state` validator (see `make-validator`). Will be used to validate all fields on this
  form. Subforms may define a validator, but the master form's validator takes precedence."
  :com.fulcrologic.rad.form/validator)

(def route-prefix
  "OPTIONAL: A string. The string to use as this form's route prefix. If you do not provide this key then the router
   will primarily be usable as a subform, since it will not support routing.
   "
  :com.fulcrologic.rad.form/route-prefix)

(def cancel-route
  "OPTIONAL: A vector. The route to go to on cancel. Can also be `:back`, which is the default (and takes the user
  back to where they came from.)"
  :com.fulcrologic.rad.form/cancel-route)

(def controls
  "ALIAS to com.fulcrologic.rad.control/controls, which is a map from a made-up control key to a control definition
  (e.g. a button). See the control ns. Forms have a standard map of controls, and if you set this you should
  merge `form/standard-controls` with your new controls, unless you want to completely redefined the controls."
  :com.fulcrologic.rad.control/controls)

(def action-buttons
  "A vector of action button keys (see controls). Specifies the layout order of action buttons in the form header.
   Forms have a built-in standard set of buttons, so if you modify them you should also specify this option."
  :com.fulcrologic.rad.form/action-buttons)

(def query-inclusion
  "A vector of EQL that will be appended to the component's query."
  :com.fulcrologic.rad.form/query-inclusion)

(def default-value
  "ATTRIBUTE KEY. Placed on an attribute to specify a default value."
  :com.fulcrologic.rad.form/default-value)

(def default-values
  "A map from qualified key to a value. Overrides the ::form/default-value that can be placed on an attrubute."
  :com.fulcrologic.rad.form/default-values)

(def subforms
  "A map from qualified key to a sub-map that describes details for what to use when a form attribute is a ref.

  Typical entries include:

  * `::form/ui` - A form class that will be used to render the subform

  Other entries are plugin-dependent. See `picker-options` for cases where a relationship is one where the parent
  form simply picks pre-existing things.
  "
  :com.fulcrologic.rad.form/subforms)

(def layout-styles
  "A map whose keys name a container element and whose value indicates a desired style.

   Render plugins (and your own customizations) can customize the elements and styles available, so
   see your rendering plugin for details."
  :com.fulcrologic.rad.form/layout-styles)

(def validation-messages
  "A map whose keys are qualified-keys, and whose values are strings or `(fn [props qualified-key] string?)` to generate
  the validation message.  A default value for these can be given by putting ::form/validation-message
  on the attribute itself, which has a different signature."
  :com.fulcrologic.rad.form/validation-messages)

(def validation-message
  "ATTRIBUTE KEY. Specify a default validation message for an attribute.
  Can either be a string or a `(fn [value] string?)`."
  :com.fulcrologic.rad.form/validation-message)

(def triggers
  "Custom handlers in the form state lifecycle that can do tasks at particular times and affect form state.

  * `:derive-fields` - A `(fn [props] new-props)` that can rewrite and of the props on the form (as a tree). This
  function is allowed to look into subforms, and even generate new members (though it must be careful to add
  form config if it does so). The `new-props` must be a tree of props that matches the correct shape of the form
  and is non-destructive to the form config and other non-field attributes on that tree.

  * `:on-change` - Called when an individual field changes. A `(fn [uism-env form-ident qualified-key old-value new-value] uism-env)`.
  The change handler has access to the UISM env (which contains `::uism/fulcro-app` and `::uism/state-map`). This
  function is allowed to side-effect (trigger loads for dependent dropdowns, etc.). It must return
  the (optionally updated) `uism-env`. This means you can trigger state machine events, and use the various
  facilities of UISM to accomplish your tasks. If you define your own custom state machine this can be useful for
  triggering very complex behavior. Typically you'll do something like
  `(uism/apply-action uism-env assoc-in (conj form-ident :line-item/quantity) 1)` to update the form state. See UISM
  documentation for more details.
  "
  :com.fulcrologic.rad.form/triggers)

(def enumerated-labels
  "A map from qualified key of a form field to the string to use for it. May be a `(fn [] string?)` (e.g. for i18n).
   Overrides ::attr/enumerated-labels."
  :com.fulcrologic.rad.form/enumerated-labels)

(def field-label
  "ATTRIBUTE OPTION. String or `(fn [form-instance] string-or-element)`. Rendering plugins may require a string return
  value.

  Placing this on an attribute indicates a default for the label for the attribute on forms. The default is a
  capitalized version of the attribute's key. See also `field-labels`."
  :com.fulcrologic.rad.form/field-label)
