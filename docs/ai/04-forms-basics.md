# Forms Basics

## Overview

RAD forms are Fulcro components augmented with auto-generated queries, state management, and CRUD operations. The
`defsc-form` macro generates everything needed for loading, editing, validating, and saving entities. Forms handle both
creation and editing, support nested subforms, integrate with routing, and can be fully auto-rendered or manually
controlled.

## The defsc-form Macro

From DevelopersGuide.adoc:1094-1121 and form.cljc:1-40:

```clojure
(ns com.example.ui.account
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.account :as acct]))

(defsc-form AccountForm [this props]
  {fo/id           acct/id
   fo/attributes   [acct/name acct/email acct/enabled?]
   fo/route-prefix "account"})
```

**What defsc-form Does**:

- Generates Fulcro component with `defsc`
- Auto-generates EQL query from attributes
- Creates `:ident` from `fo/id`
- Adds form state machine integration
- Creates routes: `["account" "create" :id]` and `["account" "edit" :id]`
- Wraps with save/load logic

## Required Options

### fo/id

**Type**: Attribute (not keyword)
**Namespace**: `:com.fulcrologic.rad.form/id`

From form-options.cljc:20-22:
> "Form option. REQUIRED: The *attribute* that will act as the primary key for this form."

```clojure
{fo/id acct/id}  ; <-- The attribute definition, not :account/id
```

**Must be**: An identity attribute (with `ao/identity? true`).

### fo/attributes

**Type**: Vector of attributes (not keywords)
**Namespace**: `:com.fulcrologic.rad.form/attributes`

From form-options.cljc:24-27:
> "Form option. REQUIRED: A vector of *attributes* that should be state-managed (should be saved/loaded). If the
> attribute isn't in this list, it will not be managed."

```clojure
{fo/attributes [acct/name acct/email acct/enabled?]}
```

**Important**: Pass attribute definitions, not keywords. All attributes must be resolvable from `fo/id`.

## Core Form Operations

From form.cljc and DevelopersGuide.adoc:1123-1128:

### form/create!

**Signature**: `(form/create! app-ish FormClass)`

Creates a new entity instance.

```clojure
(dom/button {:onClick #(form/create! this AccountForm)}
  "Create Account")
```

**Behavior**:

- Navigates to `["account" "create" temp-id]`
- Generates temp ID
- Initializes empty entity with default values
- Marks form as new (`:ui/new? true`)

### form/edit!

**Signature**: `(form/edit! app-ish FormClass id-value)`

Edits an existing entity.

```clojure
(dom/button {:onClick #(form/edit! this AccountForm [:account/id uuid])}
  "Edit Account")
```

**Behavior**:

- Navigates to `["account" "edit" id]`
- Loads entity from server (if not in client DB)
- Marks all fields as complete
- Enables save/undo buttons

### form/delete!

**Signature**: `(form/delete! app-ish qualified-key id-value)`

Deletes an entity.

```clojure
(dom/button {:onClick #(form/delete! this :account/id [:account/id uuid])}
  "Delete Account")
```

**Important**: Don't call from within the form being deleted without also routing elsewhere.

### form/save!

**Signature**: `(form/save! form-env)`

Saves the current form.

```clojure
;; Usually called by RAD's standard save button
;; Can call manually:
(form/save! {::form/master-form this})
```

**Behavior**:

- Validates form
- Sends `save-form` mutation to server
- Handles tempid remapping
- Updates form state on success/failure

### form/cancel! / undo-all!

**Signatures**:

- `(form/cancel! form-env)`
- `(form/undo-all! form-env)`

Resets form to last saved state.

```clojure
(form/cancel! {::form/master-form this})
(form/undo-all! {::form/master-form this})  ; Same behavior
```

## Form Options

### Routing

#### fo/route-prefix

**Type**: String
**Required**: For routable forms

From DevelopersGuide.adoc:1108-1110:
> "A single string. Every form ends up with two routes: `[prefix \"create\" :id]` and `[prefix \"edit\" :id]`."

```clojure
{fo/route-prefix "account"}
;; Creates routes: ["account" "create" :id] and ["account" "edit" :id]
```

#### fo/cancel-route

**Type**: Vector (route segment)

Where to navigate when form is cancelled.

```clojure
{fo/cancel-route ["landing-page"]}
```

### Display

#### fo/title

**Type**: String or `(fn [form-instance props] string)`

From form-options.cljc:66-68:
> "Form option. OPTIONAL: The title for the form. Can be a string or a `(fn [form-instance form-props])`."

```clojure
{fo/title "Account Details"}

;; Dynamic
{fo/title (fn [this props]
            (if (:ui/new? props)
              "Create Account"
              "Edit Account"))}
```

#### fo/layout

**Type**: Vector of vectors of qualified keywords

From form-options.cljc:29-42:
> "Form option. OPTIONAL (may not be supported by your rendering plugin): A vector of vectors holding the *qualified
keys* of the editable attributes. This is intended to represent the *desired* layout of the fields on this form."

```clojure
{fo/layout [[:account/name :account/email]  ; Row 1
            [:account/enabled?]              ; Row 2
            [:account/notes]]}               ; Row 3
```

**Plugin-Dependent**: Not all rendering plugins support this.

#### fo/tabbed-layout

**Type**: Vector alternating strings (tab names) and layouts

From form-options.cljc:44-64:

```clojure
{fo/tabbed-layout ["Basic Info"
                   [[:account/name :account/email]
                    [:account/enabled?]]
                   "Security"
                   [[:account/password]
                    [:account/two-factor?]]]}
```

### Field Customization

#### fo/field-styles

**Type**: Map from qualified keyword to style keyword (or fn)

From form-options.cljc:93-100:
> "Form option. OPTIONAL: A map from *qualified keyword* of the attribute to the *style* (a keyword) desired for the
> renderer."

```clojure
{fo/field-styles {:account/password :password
                  :account/address  :pick-one}}  ; Picker instead of subform
```

Common styles: `:default`, `:password`, `:pick-one`, `:pick-many`, `:autocomplete` (plugin-specific).

#### fo/field-options

**Type**: Map from qualified keyword to options map

Additional options per field (often used with pickers).

```clojure
{fo/field-options {:account/role {::picker-options/query-key :role/all-roles
                                   ::picker-options/cache-time-ms 30000}}}
```

#### fo/fields-visible?

**Type**: Map from qualified keyword to boolean or `(fn [this] boolean)`

From form-options.cljc:79-82:
> "Form option. OPTIONAL: A map from *qualified keyword* to a boolean or a `(fn [this])`. Makes fields statically or
> dynamically visible on the form."

```clojure
{fo/fields-visible? {:account/admin-notes (fn [this]
                                             (admin? this))}}
```

### Validation

#### fo/validator

**Type**: `(fn [form field] :valid|:invalid|:unknown)`

Custom form validator. Usually combines attribute validators.

```clojure
(ns com.example.model
  (:require [com.fulcrologic.rad.attributes :as attr]))

(def all-attributes [...])
(def default-validator (attr/make-attribute-validator all-attributes))

;; In form:
{fo/validator default-validator}

;; Or combined:
{fo/validator (fs/make-validator
                (fn [form field]
                  (case field
                    :custom/field (custom-check form field)
                    (= :valid (default-validator form field)))))}
```

### Subforms

#### fo/subforms

**Type**: Map from qualified keyword to subform config

Configures to-many or to-one owned relationships.

```clojure
{fo/subforms {:account/addresses {fo/ui              AddressForm
                                   fo/can-add-row?    (fn [parent] true)
                                   fo/can-delete-row? (fn [parent item] true)}}}
```

**Subform Options**:

- `fo/ui` - REQUIRED. The form component
- `fo/can-add-row?` - `(fn [parent-props] boolean|:prepend|:append)`. Add button control.
- `fo/can-delete-row?` - `(fn [parent-props item-props] boolean)`. Delete button control.
- `fo/order-by` - `(fn [items] sorted-items)`. Custom sorting.

**See**: [05-form-relationships.md](05-form-relationships.md) for detailed patterns.

### Controls

#### fo/action-buttons

**Type**: Vector of control keys

From form.cljc:61-63:
> "The standard ::form/action-buttons button layout. Requires you include standard-controls in your ::control/controls
> key."

```clojure
{fo/action-buttons [::form/done ::form/undo ::form/save]}
```

Standard buttons (form.cljc:65-100):

- `::form/done` - Cancel/Done button
- `::form/undo` - Undo changes button
- `::form/save` - Save button

#### fo/read-only?

**Type**: Boolean or `(fn [form-instance] boolean)`

Makes entire form read-only.

```clojure
{fo/read-only? true}

;; Or dynamic
{fo/read-only? (fn [this]
                 (not (can-edit? this)))}
```

**View Mode**: Use `form/view!` instead of `form/edit!` for read-only viewing (form.cljc:48-59).

### Server Integration

#### fo/save-mutation

**Type**: Symbol (mutation name)

Custom save mutation (default: `com.fulcrologic.rad.form/save-form`).

```clojure
{fo/save-mutation 'com.example.api/custom-save}
```

#### fo/delta

**Type**: `(fn [form-instance props] delta-map)`

Custom logic to compute what changed.

```clojure
{fo/delta (fn [form props]
            ;; Return map of changes
            ...)}
```

## Form Lifecycle

**1. Creation** (`form/create!`):

```
User clicks → Navigate to create route → Generate temp ID →
Initialize entity → Mark new → Show form
```

**2. Editing** (`form/edit!`):

```
User clicks → Navigate to edit route → Load entity (if needed) →
Mark complete → Show form
```

**3. User Edits**:

```
Field change → Form state updates → Dirty flag set →
Save button enabled → Validation on blur
```

**4. Save** (`form/save!`):

```
Validate → Send mutation → Wait for response →
Remap tempids → Update state → Navigate (optional)
```

**5. Cancel** (`form/cancel!`):

```
Undo changes → Restore pristine → Navigate to cancel-route
```

## Form State Machine

Forms use Fulcro's UISM (UI State Machine) under the hood (form.cljc:17, 46):

**State**: Tracked at `::uism/asm-id` in props.

**Actions**:

- `form/view!` - View mode (read-only)
- `form/create!` - Create mode (new entity)
- `form/edit!` - Edit mode (existing entity)

**Flags in Props**:

- `:ui/new?` - True if creating new entity
- Form state keys from `fs/*` namespace

## Complete Example

```clojure
(ns com.example.ui.account-forms
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [com.example.model.account :as acct]
    [com.example.model.address :as addr]
    [com.example.model :as model]))

;; Subform for addresses
(defsc-form AddressForm [this props]
  {fo/id         addr/id
   fo/attributes [addr/street addr/city addr/state addr/zip]})

;; Main form
(defsc-form AccountForm [this props]
  {fo/id           acct/id
   fo/attributes   [acct/name acct/email acct/enabled?
                    acct/role acct/addresses]

   fo/route-prefix  "account"
   fo/cancel-route  ["landing-page"]

   fo/title         (fn [_ props]
                      (if (:ui/new? props)
                        "Create New Account"
                        "Edit Account"))

   fo/validator     model/default-validator

   fo/tabbed-layout ["Basic Info"
                     [[:account/name :account/email]
                      [:account/enabled? :account/role]]
                     "Addresses"
                     [[:account/addresses]]]

   fo/field-styles  {:account/role :pick-one}

   fo/field-options {:account/role
                     {::picker-options/query-key :role/all-roles
                      ::picker-options/options-xform
                      (fn [_ roles]
                        (mapv (fn [{:role/keys [id name]}]
                                {:text name :value [:role/id id]})
                              roles))}}

   fo/subforms      {:account/addresses
                     {fo/ui              AddressForm
                      fo/can-add-row?    (fn [_] :append)
                      fo/can-delete-row? (fn [_ _] true)}}})
```

## Minimal Complete Client

From DevelopersGuide.adoc:1130-1180:

```clojure
(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]))

;; Form
(defsc-form AccountForm [this props]
  {fo/id          acct/id
   fo/attributes  [acct/name]
   fo/route-prefix "account"})

;; Landing page
(defsc LandingPage [this props]
  {:query         ['*]
   :ident         (fn [] [:component/id ::LandingPage])
   :initial-state {}
   :route-segment ["landing-page"]}
  (div
    (dom/button {:onClick #(form/create! this AccountForm)}
      "Create Account")))

;; Router
(defrouter MainRouter [this props]
  {:router-targets [LandingPage AccountForm]})

(def ui-main-router (comp/factory MainRouter))

;; Root
(defsc Root [this {:keys [router]}]
  {:query         [{:router (comp/get-query MainRouter)}]
   :initial-state {:router {}}}
  (div
    (ui-main-router router)))
```

## Default Values

Set default values for new entities:

**On Attribute** (form-options namespace):

```clojure
(defattr enabled? :account/enabled? :boolean
  {fo/default-value true
   ao/identities #{:account/id}
   ao/schema :production})
```

**On Form**:

```clojure
{fo/default-values {:account/enabled? true
                    :account/created-at (datetime/now)}}
```

**Via Route Parameters** (for `create!`):

```clojure
(form/create! this AccountForm {:initial-state {:account/name "Preset Name"}})
```

## Validation

From DevelopersGuide.adoc:1182-1192 and attributes options:

**Attribute-Level**:

```clojure
(defattr age :person/age :int
  {ao/valid? (fn [value _ _]
               (and (>= value 0) (<= value 150)))
   ao/identities #{:person/id}
   ao/schema :production})
```

**Form-Level**:

```clojure
{fo/validator (fs/make-validator
                (fn [form field]
                  (if (and (= field :date/end)
                           (< (:date/end form) (:date/start form)))
                    :invalid
                    :valid)))}
```

**Built-In Validation**:

- Data type enforcement (can't type "abc" into decimal field)
- `ao/required?` - Field required check
- `ao/valid?` - Custom predicate
- Form state marks incomplete until user touches field

## Common Patterns

### Conditional Field Visibility

```clojure
{fo/fields-visible?
 {:account/admin-panel (fn [this]
                         (admin-user? (comp/props this)))}}
```

### Custom Save Button

```clojure
{fo/action-buttons [::my-save ::form/done]
 ::control/controls {::my-save {:type :button
                                :label "Save & Email"
                                :action (fn [this]
                                          (form/save! {::form/master-form this})
                                          (send-email! this))}}}
```

### Pre-populate on Create

```clojure
(dom/button {:onClick #(form/create! this AccountForm
                                     {:initial-state {:account/type :type/business}})}
  "Create Business Account")
```

### Read-Only Mode

```clojure
;; View mode (no save/edit)
(form/view! this AccountForm [:account/id uuid])

;; Or with read-only flag
{fo/read-only? (fn [this] (not (can-edit? this)))}
```

## Important Notes

### Attributes vs Keywords

**WRONG**:

```clojure
{fo/id :account/id              ; <-- Keyword
 fo/attributes [:account/name]} ; <-- Keywords
```

**CORRECT**:

```clojure
{fo/id acct/id                 ; <-- Attribute definition
 fo/attributes [acct/name]}    ; <-- Attribute definitions
```

### Form State Namespace

Forms use Fulcro's form-state (`:com.fulcrologic.fulcro.algorithms.form-state`). Don't confuse with RAD form namespace.

### Tempids

New entities get tempids. Server save middleware must remap them. RAD handles this automatically if you use standard
patterns.

### Subform vs Picker

- **Subform**: Owned relationships (addresses, line items)
- **Picker**: Referenced relationships (inventory items, categories)

**See**: [05-form-relationships.md](05-form-relationships.md)

## Related Topics

- **Form Relationships**: [05-form-relationships.md](05-form-relationships.md) - Subforms, pickers, ownership
- **Form Validation**: [06-form-validation.md](06-form-validation.md) - Detailed validation patterns
- **Dynamic Forms**: [07-dynamic-forms.md](07-dynamic-forms.md) - Computed fields, derived values
- **Attributes**: [01-attributes-data-model.md](01-attributes-data-model.md) - Understanding attributes
- **Server Setup**: [10-server-setup.md](10-server-setup.md) - Save middleware, resolvers

## Source References

### Primary Sources

- **Forms Overview**: DevelopersGuide.adoc:1094-1181
- **defsc-form**: form.cljc:1-100
- **Form Options**: form-options.cljc:1-100+ (complete namespace)
- **Standard Controls**: form.cljc:61-100
- **Form Lifecycle**: form.cljc (form-machine implementation)

### Key Functions

- `form/create!` - Create new entity
- `form/edit!` - Edit existing entity
- `form/view!` - View mode (read-only)
- `form/save!` - Save form
- `form/cancel!` / `form/undo-all!` - Reset form
- `form/delete!` - Delete entity
