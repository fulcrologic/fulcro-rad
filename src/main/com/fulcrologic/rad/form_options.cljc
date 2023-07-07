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

   A boolean or `(fn [form-instance attr] boolean?)`. `attr` will be the full attribute that this option is attached to.

   An attribute-level key that can be used on an attribute to define the default visibility for an attribute on
   forms.  Forms may override the attribute-specific key with `fields-visible?`."
  :com.fulcrologic.rad.form/field-visible?)

(def fields-visible?
  "OPTIONAL: A map from *qualified keyword* to a boolean or a `(fn [this])`. Makes fields statically or dynamically
   visible on the form. May be given a default on the attribute definition using `::form/field-visible?`"
  :com.fulcrologic.rad.form/fields-visible?)

(def field-style
  "ATTRIBUTE KEY. OPTIONAL: A *keyword* (or `(fn [form-instance] kw)`)
   that changes the style of the control that is rendered for the given field. If not found, the renderer will
   revert to `:default`. If the attribute has a `::attr/style` then that will be attempted as a backup to this
   option.

   Forms can override this with `::form/field-styles`."
  :com.fulcrologic.rad.form/field-style)

(def field-styles
  "OPTIONAL: A map from *qualified keyword* of the attribute to the *style* (a keyword) desired for the renderer of that
  attribute (a keyword defined by your rendering plugin).

  The values in this map can be `(fn [form-instance] keyword)`.

   Changes the style of the control that is rendered for the given field. If not found, the renderer will
   revert to `:default`.

   Attributes can set a default for this with ::form/field-style, and an attribute's `ao/style` will be treated as
   a last resort place to find a style.

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
  "OPTIONAL: A vector of strings, a route target class (recommended), a map with :route/:target and :params keys,
   or `:back` (default).

   The route to go to on cancel.

   A vector of strings or a map will ignore history, a route target class is recommended when using history, and
   `:back` requires route history to be installed. You may also specify `:none` if you do not want to route away from
   the form after cancelling, and instead want to do something different (e.g. via the :on-cancel event).

   If you return a map is must have ONE OF :route (a vector of strings) or :target (a route target class). The :params
   is an optional map. A `:route` will not end up in history, so `:target` is preferred.

   You can also supply a `(fn [app form-props] ...)` for this option that returns any of the above."
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
  "A vector of EQL that will be appended to the component's query. Usually used to add `:ui/???` props for use with
   hand-rendered UI. See `initialize-ui-props`.

   NOTES:
   * The query elements will be included in the server query to LOAD existing items; however, NO I/O is done on these
     during create, since new items do not come from the server! If you always need to load something in your
     query inclusion (e.g. options needed to render the form) you will need to augment the form state machine with
     `fo/machine`.
   * If you use joins in the query inclusion, they will not support dynamic queries.
   "
  :com.fulcrologic.rad.form/query-inclusion)

(def default-value
  "ATTRIBUTE KEY. The default value for this attribute when created in a new
  form. Can be a literal value or a `(fn [] value)`. Placed on an attribute to specify a default value."
  :com.fulcrologic.rad.form/default-value)

(def default-values
  "A map from qualified key to a value, or a `(fn [] {k v})`.

   Overrides the ::form/default-value that can be placed on an attrubute."
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

  * `:derive-fields` - A `(fn [props] new-props)` that can rewrite any of the props on the form (as a tree). This
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

  * `:started` - A (fn [uism-env ident]). Called after the form has been initialized. Note, new instances (create) will
    have a tempid. Edits will have issued a load on the form, so if you're trying to load things that are in query
    inclusions (that have a global resolver) then you only need to do so if the entity is new.  Must return UISM env.
  * `:saved` - A (fn [uism-env ident]). Called after a successful save. Must return a UISM env.
  * `:save-failed` - A (fn [uism-env ident]). Called after a failed save. Must return a UISM env.
  * `:started` - A `(fn [uism-env ident])`. Called as a form starts (state machine started, but load may still be in progress).
  "
  :com.fulcrologic.rad.form/triggers)

(def enumerated-labels
  "A map from qualified key of a form field to the string to use for it. May be a `(fn [] string?)`.
   Overrides ::attr/enumerated-labels."
  :com.fulcrologic.rad.form/enumerated-labels)

(def field-label
  "ATTRIBUTE OPTION. String or `(fn [form-instance] string-or-element)`. Rendering plugins may require a string return
  value.

  Placing this on an attribute indicates a default for the label for the attribute on forms. The default is a
  capitalized version of the attribute's key. See also `field-labels`."
  :com.fulcrologic.rad.form/field-label)

(def ui
  "Used within `subforms`. This should be the Form component that will be used to render instances of
   the subform."
  :com.fulcrologic.rad.form/ui)

(def field-style-config
  "ATTRIBUTE OPTION: A map of options that are used by the rendering plugin to augment the style of a rendered input.
  Such configuration options are really up to the render plugin, but could include things like `:input/props` as
  additional DOM k/v pairs to put on the input."
  :com.fulcrologic.rad.attributes/field-style-config)

(def input-props
  "A key that can be included WITHIN `field-style-config`(s).  A HINT to the field renderer that the given additional
  props should be included on the low-level input.  Renderers may define additional keys for further customization. In
  general you should *not* use this to attempt to mess with the input's value of value event handlers. The value of
  this option can be a map or a `(fn [form-env] map?)`. In general `:value`, `:onBlur`, `:onChange`, and `:onFocus`
  will be ignored by the underlying renderer, since it will likely provide its own setting for those."
  :input/props)

(def field-style-configs
  "A map from field *keyword* to a map of options that are used by the rendering plugin to augment the style of a rendered input.
  Such configuration options are really up to the render plugin, but could include things like `:input/props` as
  additional DOM k/v pairs to put on the input."
  :com.fulcrologic.rad.form/field-style-configs)

(def sort-children
  "This option goes *within* ::subforms and defines how to sort those subform UI components when there are more
  than one. It is a `(fn [denormalized-children] sorted-children)`.

  For example, a form might have:

  ```
  {fo/subforms {:person/children {fo/ui PersonForm
                                  fo/sort-children (fn [children] (sort-by :person/name children))}
  ```
  "
  :com.fulcrologic.rad.form/sort-children)

(def read-only-fields
  "A set of keywords, or a `(fn [form-instance] set?)`. Any form fields that appear in the set will be shown
   as read-only values in the form"
  :com.fulcrologic.rad.form/read-only-fields)

(def read-only?
  "Used to indicate whether *everything* in the form should be treated as read-only.
   This option is a boolean or a `(fn [form-instance] boolean?)`.
   If true, `read-only-fields` will be ignored."
  :com.fulcrologic.rad.form/read-only?)

(def can-delete?
  "Used in `subforms` maps to control when a child can be deleted.
   This option is a boolean or a `(fn [parent-form-instance row-props] boolean?)`.
   that is used to determine if the given child can be deleted by the user."
  :com.fulcrologic.rad.form/can-delete?)

(def can-add?
  "Used in `subforms` maps to control when a child of that type can be added across its relation.
   This option is a boolean or a `(fn [form-instance attribute] boolean?)` that is used to determine if the
   given child (reachable through `attribute` (a ref attribute)) can be added as a child to `form-instance`.

   NOTE: You can return the truthy value `:prepend` from this function to ask the form to put new children at the top
   of the list."
  :com.fulcrologic.rad.form/can-add?)

(def machine
  "Override the state machine definition that is used to control this form. Defaults to form/form-machine, which
   you can use as a basis of your replacement (a state machine definition is just a map)."
  :com.fulcrologic.rad.form/machine)

(def debug?
  "Indicate a desire to show debug information about the form. Requires support from the rendering plugin."
  :com.fulcrologic.rad.form/debug?)

(def initialize-ui-props
  "Form-only option, meant for setting up additinal component state (particularly `:ui/???` props that were added
   in a query inclusion) when the form is created or loaded.

   Either a map or a `(fn [FormClass props] props)` that, when present, is called when an instance of this
   form is either loaded or created. NOTE: The resulting merge is SHALLOW, and cannot mess with subforms in *any* way.
   If you want to affect a subform, then you MUST include this option on that subform (instead of trying to reach deeper in props).

   NOTE: It is possible for this function to be called more than once on a given form, so it should not side-effect.

   See `query-inclusion`.
   "
  :com.fulcrologic.rad.form/initialize-ui-props)

(def save-mutation
  "A *symbol* that represents the server-side mutation that will be invoked on a save. Defaults to
   `com.fulcrologic.rad.form/save-form`. Used in cases where you want to bypass the default RAD form save handler
   and middleware in order to do something non-standard with the data from the form."
  :com.fulcrologic.rad.form/save-mutation)

(def save-params
  "A map of data OR a `(fn [form-rendering-env] map?)`. This data will be included in the form parameters sent
   to the save mutation on the remote. These will be merged with the default parameters such as `::form/delta`."
  :com.fulcrologic.rad.form/save-params)

(def silent-abandon?
  "Form-only option. A boolean or `(fn [master-form-instance] boolean?)`. This is checked when navigation attempts
   to move away from the form. If it returns true, then any unsaved changes will be abandoned (reset in Fulcro state to
   their original values) and the navigation will be allowed."
  :com.fulcrologic.rad.form/silent-abandon?)

(def add-label
  "SUBFORM option (placed on any form that might be used as a subform). A string or
   `(fn [ui-cls add-child!] string-or-dom-element)` that represents the label to use when an add button is generated
   for to-one or to-many relations of that form.

   The `ui-cls` will be the component class for which a label is desired, and the `add-child!` is a no-arg function to
   call when you want to add a child.

   IF your function returns a DOM element, then that DOM element must call the provided (no-arg) `add-child!` function when it is
   triggered.
  "
  :com.fulcrologic.rad.form/add-label)

(def show-header?
  "Form-only option. A boolean or `(fn [master-form-instance] boolean?)`. Default true. Turns on/off the title/controls.
   Useful when embedding a form in some other container. Can also be passed in the computed props of a top-level form
   factory for contextual hiding of the header."
  :com.fulcrologic.rad.form/show-header?)
