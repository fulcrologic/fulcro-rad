# Form Validation

## Overview

RAD forms use Fulcro's form-state validation system to validate user input before submission. Validation can be
specified at the **attribute level** (reusable across forms) or at the **form level** (contextual to specific forms).
Type constraints from attributes (e.g., decimal, integer) provide first-line enforcement, while custom validators handle
business logic.

## Validation Levels

From DevelopersGuide.adoc:1184-1191:

1. **Type-level enforcement**: Attribute types (`:int`, `:decimal`, `:instant`, etc.) and rendering styles automatically
   prevent invalid input (e.g., typing "abc" into a number field).

2. **Attribute-level validation**: General constraints that apply wherever the attribute is used (e.g., "age must be
   0-130").

3. **Form-level validation**: Contextual constraints between multiple fields (e.g., "from-date must be before to-date").

## Fulcro Form-State Validators

From DevelopersGuide.adoc:1188-1190:

RAD uses Fulcro's form-state validators, which return:

- `:valid` - The field passes validation
- `:invalid` - The field fails validation
- `:unknown` - The field isn't ready to be checked yet (e.g., not touched)

Validators are created with `form-state/make-validator` (aliased as `fs/make-validator`), which respects completion
markers to prevent premature error messages.

**Signature**: `(fs/make-validator (fn [form field] validation-result))`

Where:

- `form` is the map of current form props
- `field` is the qualified keyword being validated
- Returns `:valid`, `:invalid`, or `:unknown`

## Attribute-Level Validation

### Using ao/valid?

From DevelopersGuide.adoc:1192-1199 and attributes-options.cljc:62-82:

```clojure
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr name :account/name :string
  {ao/identities #{:account/id}
   ao/schema     :production
   ao/valid?     (fn [value props qualified-key]
                   (boolean (seq value)))})

(defattr age :account/age :int
  {ao/identities #{:account/id}
   ao/schema     :production
   ao/valid?     (fn [value props qualified-key]
                   (and (number? value)
                        (<= 0 value 130)))})
```

**`ao/valid?` Signature**: `(fn [value props qualified-key] boolean?)`

From attributes-options.cljc:62-82:
> "OPTIONAL. A `(fn [value props qualified-key] boolean?)`.
>
> IMPORTANT: This is ONLY used in the UI when you create a validator using `attr/make-attribute-validator`, AND you set
> that as the form validator with the `fo/validator` option."

Parameters:

- `value` - The current value of the attribute
- `props` - The entire form props (allows checking other fields)
- `qualified-key` - The keyword of the attribute being validated (e.g., `:account/name`)

**How valid? Works with required?**:

From attributes.cljc:172-197:

The `attr/valid-value?` function (used internally by `attr/make-attribute-validator`) returns `true` if:

1. The value is `nil` AND the attribute is NOT marked `ao/required?`
2. The attribute defines `ao/valid?` and that predicate returns `true`
3. The attribute has NO `ao/valid?` but IS marked `ao/required?`, and the value is non-nil (and if string, non-blank)

Otherwise returns `false`.

**Important**: `ao/required?` is primarily for **UI display hints**. It does NOT enforce server-side constraints.

From attributes-options.cljc:52-60:
> "Attribute option. OPTIONAL: A boolean or `(fn [this attr] boolean?)`.
>
> A hint to the UI that the given attribute is required. Typically used by default validators generated from attributes.
> Not necessarily a guarantee that you cannot save without it (you may still need to write and install additional save
> middleware to enforce this constraint, if you need that level of validation)."

### Creating an Attribute Validator

From DevelopersGuide.adoc:1201-1210 and attributes.cljc:222-238:

Use `attr/make-attribute-validator` to generate a Fulcro form-state validator from attributes:

```clojure
(ns com.example.model
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.example.model.account :as account]
    [com.example.model.invoice :as invoice]))

(def all-attributes
  (concat account/attributes
          invoice/attributes))

(def all-attribute-validator
  (attr/make-attribute-validator all-attributes))
```

**`attr/make-attribute-validator` Signature**:

- `(attr/make-attribute-validator attributes)` - Validates scalar attributes only
- `(attr/make-attribute-validator attributes include-refs?)` - If `include-refs?` is `true`, validates reference
  attributes too (defaults to `false`)

From attributes.cljc:222-238:
> "Creates a Fulcro form-state validator function that can be used as a form validator for any form that contains the
> given `attributes`.
>
> A field is considered valid in this validator IF AND ONLY IF `attr/valid-value?` returns true. See that function's
> docstring for how that interacts with the `ao/valid?` option of attributes.
>
> If `include-refs?` is true (default false) then references will be included in the validation."

## Form-Level Validation

From DevelopersGuide.adoc:1201-1220 and form-options.cljc:128-131:

Form-level validators **completely override** attribute-level validators. To combine them, use the attribute validator
as a fallback:

```clojure
(ns com.example.ui.account-form
  (:require
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.example.model :as model]
    [com.example.model.account :as acct]
    [clojure.string :as str]))

(def account-validator
  (fs/make-validator
    (fn [form field]
      (case field
        :account/email (str/ends-with? (get form field) "example.com")
        ;; Default: use attribute validators
        (= :valid (model/all-attribute-validator form field))))))

(defsc-form AccountForm [this props]
  {fo/id         acct/id
   fo/attributes [acct/name acct/email acct/age]
   fo/validator  account-validator})
```

From form-options.cljc:128-131:
> "Form option. OPTIONAL: A Fulcro `form-state` validator (see `make-validator`). Will be used to validate all fields on
> this form. Subforms may define a validator, but the master form's validator takes precedence."

### Cross-Field Validation

From DevelopersGuide.adoc:1240-1257:

Form validators can access all form props, enabling validation that depends on multiple fields:

```clojure
(def account-validator
  (fs/make-validator
    (fn [form field]
      (case field
        :account/email
        (let [prefix (or
                       (some-> form
                         (get :account/name)
                         (str/split #"\s")
                         (first)
                         (str/lower-case))
                       "")]
          (str/starts-with? (get form :account/email) prefix))

        ;; Default to attribute validators
        (= :valid (model/all-attribute-validator form field))))))
```

**Example**: Require email to start with lowercase first name from the `:account/name` field.

### Date Range Validation

```clojure
(def report-params-validator
  (fs/make-validator
    (fn [form field]
      (case field
        :report/from-date
        (let [from (get form :report/from-date)
              to   (get form :report/to-date)]
          (or (nil? to) (nil? from)
              (dt/before? from to)))

        :report/to-date
        (let [from (get form :report/from-date)
              to   (get form :report/to-date)]
          (or (nil? to) (nil? from)
              (dt/before? from to)))

        :valid))))
```

## Validation Messages

### Attribute-Level Messages

From DevelopersGuide.adoc:1222-1230 and form-options.cljc:265-268:

```clojure
(defattr age :thing/age :int
  {ao/identities         #{:thing/id}
   ao/schema             :production
   ao/valid?             (fn [value _ _]
                           (and (number? value)
                                (<= 0 value 130)))
   fo/validation-message (fn [value]
                           (str "Age must be between 0 and 130."))})
```

**`fo/validation-message` on Attribute**: `(fn [value] string?)`

From form-options.cljc:265-268:
> "Attribute option. Specify a default validation message for an attribute. Can either be a string or a
`(fn [value] string?)`."

### Form-Level Messages

From DevelopersGuide.adoc:1222-1238 and form-options.cljc:259-263:

Form-level messages **override** attribute-level messages:

```clojure
(defsc-form ThingForm [this props]
  {fo/id                  thing/id
   fo/attributes          [thing/name thing/age]
   fo/validator           thing-validator
   fo/validation-messages {:thing/age (fn [form-props k]
                                         (str (get form-props k) " is an invalid age."))}})
```

**`fo/validation-messages`**: A map from qualified keyword to string or `(fn [props qualified-key] string?)`

From form-options.cljc:259-263:
> "Form option. A map whose keys are qualified-keys, and whose values are strings or
`(fn [props qualified-key] string?)` to generate the validation message. A default value for these can be given by
> putting ::form/validation-message on the attribute itself, which has a different signature."

**Note**: Form message functions receive **`form-props`** and **`qualified-key`**, while attribute message functions
receive only **`value`**.

This allows form-level messages to incorporate context from other fields:

```clojure
{fo/validation-messages
 {:account/email (fn [props k]
                   (let [name (get props :account/name)]
                     (str "Email must start with " (str/lower-case name))))}}
```

## Common Patterns

### Pattern 1: Composing Attribute and Form Validators

```clojure
(ns com.example.ui.forms
  (:require
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model :as model]
    [com.example.model.account :as acct]))

(def account-validator
  (fs/make-validator
    (fn [form field]
      (case field
        ;; Override specific field
        :account/email (custom-email-check form field)

        ;; Use attribute validators for everything else
        (= :valid (model/all-attribute-validator form field))))))

(defsc-form AccountForm [this props]
  {fo/id        acct/id
   fo/attributes [acct/name acct/email acct/age]
   fo/validator account-validator})
```

### Pattern 2: Conditional Validation

```clojure
(def invoice-validator
  (fs/make-validator
    (fn [form field]
      (case field
        :invoice/tax-id
        ;; Only require tax ID for business customers
        (if (= :business (get form :invoice/customer-type))
          (boolean (seq (get form :invoice/tax-id)))
          :valid)

        (= :valid (model/all-attribute-validator form field))))))
```

### Pattern 3: Async Validation (Server Check)

RAD's built-in validation is **synchronous only**. For async validation (e.g., checking username availability on the
server), use Fulcro's mutation system:

```clojure
(defmutation check-username-available
  [{:keys [username]}]
  (action [{:keys [state]}]
    ;; Optimistically mark as checking
    (swap! state assoc-in [:ui/username-check] :checking))
  (remote [env]
    true)
  (ok-action [{:keys [state result]}]
    (swap! state assoc-in [:ui/username-check]
      (if (:available? result) :available :taken)))
  (error-action [{:keys [state]}]
    (swap! state assoc-in [:ui/username-check] :error)))
```

Then in your form, trigger the mutation on field blur and display results in custom rendering.

### Pattern 4: Required Field with Custom Message

```clojure
(defattr email :account/email :string
  {ao/identities         #{:account/id}
   ao/schema             :production
   ao/required?          true
   ao/valid?             (fn [value _ _]
                           ;; More than just non-empty: must be valid email format
                           (and (seq value)
                                (re-matches #".+@.+\..+" value)))
   fo/validation-message "Please enter a valid email address."})
```

### Pattern 5: No Validation on Subforms

From form-options.cljc:130-131:

> "Subforms may define a validator, but the master form's validator takes precedence."

If you want subforms to have their own validators, you must explicitly call them from the parent validator:

```clojure
(def parent-validator
  (fs/make-validator
    (fn [form field]
      ;; Validate parent fields
      (if (parent-field? field)
        (validate-parent-field form field)
        ;; Let subform validators handle their fields
        :valid))))
```

Or rely on the default behavior where the master form validator handles all fields.

## Validation and Form State

### Completion Markers

Fulcro form-state tracks which fields have been "touched" (interacted with) using **completion markers**. Validators
created with `fs/make-validator` automatically respect these markers:

- Fields not yet touched return `:unknown` (no error shown)
- Fields that have been touched are validated
- All fields are validated on save attempt

### Triggering Validation

Validation runs automatically:

1. **On field blur** (when user leaves a field)
2. **On form submission** (before `form/save!` is called)
3. **On programmatic `form/mark-complete!`** calls

### Preventing Save on Invalid

RAD forms automatically prevent saving if the form has validation errors. The save button will be disabled or the save
will be blocked if validation fails.

## Server-Side Validation

RAD's `ao/valid?` is **client-side only**. For server-side validation:

1. **Save Middleware**: Add custom middleware to your RAD save pipeline to validate before database writes.

2. **Mutation Return Values**: Return error information from your save mutation, which RAD will display.

3. **Database Constraints**: Rely on database-level constraints (e.g., NOT NULL, CHECK constraints, foreign keys) to
   enforce data integrity.

Example save middleware (conceptual):

```clojure
(defn validation-middleware
  [handler]
  (fn [pathom-env params]
    (let [{::form/keys [delta]} params
          errors (validate-delta delta)]
      (if (empty? errors)
        (handler pathom-env params)
        {:error-messages errors}))))
```

## Important Notes

### 1. Form Validator Overrides Attribute Validators

From DevelopersGuide.adoc:1201-1203:

> "If there are validators at both layers then the form one *completely overrides all attribute validators*."

This is intentional - form validators have complete control. Always use the pattern of calling
`model/all-attribute-validator` as a fallback if you want to preserve attribute validation.

### 2. Required? Is a UI Hint Only

From attributes-options.cljc:59-60:

> "Not necessarily a guarantee that you cannot save without it (you may still need to write and install additional save
> middleware to enforce this constraint, if you need that level of validation)."

The `ao/required?` option:

- Adds visual indicators (e.g., asterisks) in the UI
- Used by `attr/make-attribute-validator` for validation
- Does **NOT** enforce server-side constraints

### 3. Validation Runs in the Client

All `ao/valid?` and `fo/validator` checks run **client-side only**. Never rely on them for security or data integrity -
always validate on the server.

### 4. Subform Validation Precedence

From form-options.cljc:130-131:

If both a master form and subform define validators, the **master form's validator takes precedence**. This means the
master form is responsible for validating all fields, including those in subforms.

### 5. Validation Messages Are Optional

If you don't specify a validation message, RAD will not display one. The field will simply be marked invalid (typically
with visual styling like a red border).

### 6. Reference Attributes and Validation

By default, `attr/make-attribute-validator` does **not** validate reference (`:ref`) attributes. Pass `true` as the
second argument to include them:

```clojure
(def all-attribute-validator
  (attr/make-attribute-validator all-attributes true))
```

From attributes.cljc:229-230:
> "If `include-refs?` is true (default false) then references will be included in the validation."

## Related Topics

- **Forms Basics** (04-forms-basics.md): Core form operations, `form/save!` behavior
- **Attributes and Data Model** (01-attributes-data-model.md): Understanding `ao/required?` and attribute types
- **Form Relationships** (05-form-relationships.md): Validating relationship fields
- **Dynamic Forms** (07-dynamic-forms.md): Triggering validation on field changes using `fo/triggers`
- **Server Setup** (10-server-setup.md): Implementing server-side validation middleware

## Source References

- DevelopersGuide.adoc:1182-1258 (UI Validation section)
- attributes-options.cljc:52-60 (`ao/required?`)
- attributes-options.cljc:62-82 (`ao/valid?`)
- attributes.cljc:172-197 (`attr/valid-value?` function)
- attributes.cljc:222-238 (`attr/make-attribute-validator` function)
- form-options.cljc:128-131 (`fo/validator`)
- form-options.cljc:259-263 (`fo/validation-messages`)
- form-options.cljc:265-268 (`fo/validation-message`)
- Fulcro Book: http://book.fulcrologic.com/#CustomValidators (form-state validation system)
