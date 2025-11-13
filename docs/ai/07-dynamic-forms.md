# Dynamic Forms

## Overview

RAD forms support three types of dynamic behavior: **computed UI fields** (display-only calculations), **derived stored
fields** (spreadsheet-style formulas that persist), and **on-change triggers** (field change handlers with side
effects). These features enable interdependent calculations, cascading dropdowns, and complex form interactions.

## Three Types of Dynamism

From DevelopersGuide.adoc:1554-1558:

1. **Computed UI Fields**: Display-only calculated values that never persist (e.g., subtotal display)
2. **Derived Stored Fields**: Calculated values that ARE stored in state and can be saved (e.g., invoice total)
3. **On-Change Triggers**: Handlers that react to field changes, enabling side effects like loading data or updating
   related fields

## Computed UI Fields

From DevelopersGuide.adoc:1560-1581:

**Computed UI fields** are read-only values calculated from other form data. They exist only during rendering and never
appear in Fulcro state.

### Using ao/computed-value

```clojure
(ns com.example.model.line-item
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.math :as math]))

(defattr subtotal :line-item/subtotal :decimal
  {ao/computed-value (fn [{::fo/keys [props] :as form-env} attr]
                       (let [{:line-item/keys [quantity quoted-price]} props]
                         (math/round (math/* quantity quoted-price) 2)))})
```

**`ao/computed-value` Signature**: `(fn [form-env attr] value)`

Parameters:

- `form-env` - The form rendering environment map, including `::fo/props` (current form props)
- `attr` - The attribute definition of this computed field

Returns:

- The value to display (must match the attribute's declared type)

**Characteristics**:

- Shown as read-only fields in forms
- Recomputed on every render
- Never appear in Fulcro state or database
- Can access all form props via `::fo/props`

From DevelopersGuide.adoc:1579-1581:
> "You actually have access to the entire set of props in the form, but you should note that other computed fields are
> not in the data model. So if you have data dependencies across computed fields you'll end up re-computing intermediate
> results."

**Use Cases**:

- Display calculations that don't need persistence (formatted totals, status indicators)
- Temporary UI hints (e.g., "X characters remaining")
- Derived read-only values (full name from first/last name)

## Derived Stored Fields

From DevelopersGuide.adoc:1582-1643:

**Derived fields** are calculated values that ARE stored in Fulcro state and can be saved to the database. They use the
`fo/triggers` mechanism with `:derive-fields`.

### The :derive-fields Trigger

From form-options.cljc:270-276:
> "* `:derive-fields` - A `(fn [props] new-props)` that can rewrite any of the props on the form (as a tree). This
> function is allowed to look into subforms, and even generate new members (though it must be careful to add form config
> if it does so). The `new-props` must be a tree of props that matches the correct shape of the form and is
> non-destructive to the form config and other non-field attributes on that tree."

**Signature**: `(fn [form-tree] updated-form-tree)`

Parameters:

- `form-tree` - Denormalized tree of form props (includes nested subforms)

Returns:

- Updated tree with derived values computed

**Key Properties**:

- Referentially transparent (pure function)
- Receives denormalized tree (easy to reason about nested data)
- Must return tree with same shape
- Values ARE stored in Fulcro state
- Can be included in saves to database

### Basic Example

From DevelopersGuide.adoc:1589-1598:

```clojure
(ns com.example.ui.line-item
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.line-item :as line-item]
    [com.example.math :as math]))

(defn add-subtotal* [{:line-item/keys [quantity quoted-price] :as item}]
  (assoc item :line-item/subtotal (math/* quantity quoted-price)))

(defsc-form LineItemForm [this props]
  {fo/id         line-item/id
   fo/attributes [line-item/item line-item/quantity line-item/quoted-price line-item/subtotal]
   fo/triggers   {:derive-fields (fn [form-tree] (add-subtotal* form-tree))}})
```

### Nested Forms with Derive-Fields

From DevelopersGuide.adoc:1603-1609:

When both master and child forms have `:derive-fields`:

1. **Attribute change**: Triggers `:derive-fields` on the form where the attribute lives
2. **Master form always runs**: Master form's `:derive-fields` runs AFTER nested form's
3. **Row add/delete**: Triggers only the master form's `:derive-fields`

From DevelopersGuide.adoc:1610:
> "Note: Deeply nested forms do *not* run `:derive-fields` for forms *between* the master and the form on which the
> attribute changed."

### Master Form Example (Invoice Total)

From DevelopersGuide.adoc:1616-1633:

```clojure
(ns com.example.ui.invoice
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.invoice :as invoice]
    [com.example.ui.line-item :refer [LineItemForm]]
    [com.example.math :as math]))

(defn sum-subtotals* [{:invoice/keys [line-items] :as invoice}]
  (assoc invoice :invoice/total
    (reduce
      (fn [t {:line-item/keys [subtotal]}]
        (math/+ t subtotal))
      (math/zero)
      line-items)))

(defsc-form InvoiceForm [this props]
  {fo/id         invoice/id
   fo/attributes [invoice/customer invoice/date invoice/line-items invoice/total]
   fo/subforms   {:invoice/line-items {fo/ui LineItemForm}}
   fo/triggers   {:derive-fields (fn [form-tree] (sum-subtotals* form-tree))}})
```

**Flow**:

1. User changes `quantity` on a line item
2. `LineItemForm`'s `:derive-fields` runs → updates `:line-item/subtotal`
3. `InvoiceForm`'s `:derive-fields` runs → updates `:invoice/total`

### Important Constraint: Query Inclusion

From DevelopersGuide.adoc:1637-1642:

> "WARNING: It may be tempting to use this mechanism to invent values that are unrelated to the form and put them into
> the state. This is legal, but placing data in Fulcro's state database does *not* guarantee they will show up in rendered
> props."

If you add arbitrary keys to form state, they won't appear in props unless you:

1. Add them to `fo/query-inclusion`
2. Define them as attributes (even "no-op" attributes with just a type)

**Use Cases**:

- Spreadsheet-style calculations (subtotals, totals, averages)
- Aggregate values from child collections
- Cross-field derived data that needs persistence

## On-Change Triggers

From DevelopersGuide.adoc:1644-1700 and form-options.cljc:278-285:

**On-change triggers** handle user-driven field changes and enable side effects like loading data or conditionally
updating fields.

### The :on-change Trigger

From form-options.cljc:278-285:
> "* `:on-change` - Called when an individual field changes. A
`(fn [uism-env form-ident qualified-key old-value new-value] uism-env)`. The change handler has access to the UISM env (
> which contains `::uism/fulcro-app` and `::uism/state-map`). This function is allowed to side-effect (trigger loads for
> dependent dropdowns, etc.). It must return the (optionally updated) `uism-env`."

**Signature**: `(fn [uism-env form-ident k old-value new-value] uism-env-or-nil)`

Parameters:

- `uism-env` - Fulcro UI State Machine environment (contains `::uism/state-map`, `::uism/fulcro-app`)
- `form-ident` - Ident of the form being modified (e.g., `[:line-item/id uuid]`)
- `k` - Qualified keyword of the attribute that changed (e.g., `:line-item/item`)
- `old-value` - Previous value of the attribute
- `new-value` - New value of the attribute

Returns:

- Updated `uism-env` OR `nil` (nil means "do nothing")

From DevelopersGuide.adoc:1669-1671:
> "IMPORTANT: Handlers *must* either return an updated `env` or `nil` (which means \"do nothing\"). Returning anything
> else is an error."

### Characteristics

From DevelopersGuide.adoc:1646-1655:

- Triggered by **user-driven** changes only (via `form/input-changed!`)
- Does NOT cascade (changing a field in `:on-change` won't trigger another `:on-change`)
- Side-effect capable (can trigger loads, navigate, etc.)
- Must use UISM API (immutable, threaded operations)
- Runs BEFORE `:derive-fields` triggers

From DevelopersGuide.adoc:1699-1700:
> "The `:on-change` triggers *always* precede `:derive-fields` triggers, so that the global derivation can depend upon
> values pushed from one field to another."

### UISM Pattern

From DevelopersGuide.adoc:1657-1667:

```clojure
(fn [env]
  (-> env
    (uism/apply-action ...)
    (some-helper-you-wrote)
    (cond->
      condition? (optional-thing))))
```

Common UISM operations:

- `uism/apply-action` - Update Fulcro state (like `swap!` but returns env)
- `uism/trigger` - Trigger loads, mutations
- `uism/store` - Store data in the state machine's actor storage

### Example: Auto-Fill Price on Item Selection

From DevelopersGuide.adoc:1675-1697:

```clojure
(ns com.example.ui.line-item
  (:require
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.line-item :as line-item]))

(defsc-form LineItemForm [this props]
  {fo/id         line-item/id
   fo/attributes [line-item/item line-item/quantity line-item/quoted-price line-item/subtotal]
   fo/triggers   {:on-change (fn [{::uism/keys [state-map] :as uism-env}
                                  form-ident k old-value new-value]
                                (case k
                                  ;; When item changes, auto-fill quoted-price from inventory
                                  :line-item/item
                                  (let [item-price  (get-in state-map (conj new-value :item/price))
                                        target-path (conj form-ident :line-item/quoted-price)]
                                    ;; apply-action works like (update state-map assoc-in ...)
                                    (uism/apply-action uism-env assoc-in target-path item-price))

                                  ;; Default: do nothing
                                  nil))

                  :derive-fields (fn [form-tree] (add-subtotal* form-tree))}})
```

**Flow**:

1. User selects an item from dropdown
2. `:on-change` fires with `new-value` as item ident (e.g., `[:item/id uuid]`)
3. Handler reads `:item/price` from normalized state
4. Handler writes price to `:line-item/quoted-price`
5. `:derive-fields` fires → recalculates `:line-item/subtotal`

## Other Trigger Types

From form-options.cljc:287-293:

### :started

**Signature**: `(fn [uism-env ident] uism-env)`

Called after form initialization (state machine started, but load may still be in progress).

- For **new entities**: Will have a tempid
- For **edits**: Load will have been issued
- Useful for loading data needed in `fo/query-inclusion` (only needed for new entities)

**Use Cases**:

- Load options for pickers when form is created
- Initialize UI state not covered by `fo/default-values`

### :saved

**Signature**: `(fn [uism-env ident] uism-env)`

Called after a successful save.

**Use Cases**:

- Show success notification
- Navigate to another route
- Refresh related data
- Clear temporary UI state

### :save-failed

**Signature**: `(fn [uism-env ident] uism-env)`

Called after a failed save.

**Use Cases**:

- Show custom error messages
- Log errors
- Retry logic

## Common Patterns

### Pattern 1: Cascading Dropdowns

```clojure
{fo/triggers
 {:on-change (fn [{::uism/keys [fulcro-app] :as env} form-ident k old new]
               (case k
                 :address/country
                 (-> env
                   ;; Clear dependent field
                   (uism/apply-action assoc-in (conj form-ident :address/state) nil)
                   ;; Load states for selected country
                   (uism/trigger :actor/form
                     `load-states
                     {:country new}))

                 nil))}}
```

### Pattern 2: Conditional Derivation

```clojure
(defn update-tax* [{:invoice/keys [subtotal customer-type tax-rate] :as invoice}]
  (if (= :business customer-type)
    (assoc invoice :invoice/tax (math/* subtotal tax-rate))
    (assoc invoice :invoice/tax 0)))

{fo/triggers {:derive-fields update-tax*}}
```

### Pattern 3: Validate on Change

```clojure
{fo/triggers
 {:on-change (fn [{::uism/keys [fulcro-app] :as env} form-ident k old new]
               (case k
                 :account/username
                 (do
                   ;; Side effect: trigger async validation mutation
                   (comp/transact! fulcro-app [(check-username-available {:username new})])
                   ;; Return unchanged env (side effect only)
                   env)

                 nil))}}
```

### Pattern 4: Compute Multiple Derived Fields

```clojure
(defn update-financials* [{:invoice/keys [subtotal tax-rate discount] :as invoice}]
  (let [tax   (math/* subtotal tax-rate)
        total (math/- (math/+ subtotal tax) discount)]
    (assoc invoice
      :invoice/tax tax
      :invoice/total total)))

{fo/triggers {:derive-fields update-financials*}}
```

### Pattern 5: Load Data on Form Start

```clojure
{fo/triggers
 {:started (fn [{::uism/keys [fulcro-app] :as env} form-ident]
             (let [[_ id] form-ident
                   new?   (tempid/tempid? id)]
               (if new?
                 ;; Load options needed for new forms
                 (-> env
                   (uism/trigger :actor/form `load-picker-options {:cache-key :all-customers}))
                 ;; Existing form - options loaded via query inclusion
                 env)))}}
```

## Execution Order

Understanding when triggers fire is critical:

1. **User changes field** → `form/input-changed!` called
2. **`:on-change` trigger fires** (on the form where field lives)
3. **`:derive-fields` triggers fire**:
    - First on child form (if attribute changed there)
    - Then on master form (always)
4. **UI re-renders** with updated values

**Note**: `:on-change` does NOT cascade. If you modify a field within `:on-change`, it won't trigger another
`:on-change`.

## Important Notes

### 1. Computed vs. Derived

**Computed Fields** (`ao/computed-value`):

- Never in state
- Recomputed on render
- Read-only display
- Cannot be saved

**Derived Fields** (`:derive-fields`):

- Stored in Fulcro state
- Updated on any change
- Can be saved to database
- Part of the data model

### 2. On-Change Does Not Cascade

From DevelopersGuide.adoc:1646-1649:
> "The next dynamic support feature is the `:on-change` trigger. This trigger happens due to a *user-driven* change of
> an attribute on the form. Such triggers do *not* cascade."

If you update a field in `:on-change`, it won't trigger another `:on-change`. Use `:derive-fields` for cascading
updates.

### 3. UISM Handlers Must Return env or nil

From DevelopersGuide.adoc:1669-1671:

Returning anything other than an updated `uism-env` or `nil` is an error. The system will log console errors if you make
this mistake.

### 4. Derive-Fields Execution Order

From DevelopersGuide.adoc:1603-1609:

- Child form's `:derive-fields` runs first
- Master form's `:derive-fields` runs second (always)
- Intermediate forms in deeply nested structures do NOT run

### 5. Side Effects Must Use UISM API

`:on-change` handlers have access to the UISM environment and can:

- Update state via `uism/apply-action`
- Trigger loads via `uism/trigger`
- Access the app via `::uism/fulcro-app`

Never use `swap!` or `transact!` directly within the handler function body. Use the UISM API to thread operations.

### 6. Query Inclusion Required for Arbitrary Keys

From DevelopersGuide.adoc:1637-1642:

If you add keys to form state that aren't defined as attributes in `fo/attributes`, you must also add them to
`fo/query-inclusion`. Otherwise, they won't appear in component props.

## Related Topics

- **Forms Basics** (04-forms-basics.md): Understanding `form/input-changed!` and form lifecycle
- **Form Validation** (06-form-validation.md): Combining validation with `:on-change` triggers
- **Form Relationships** (05-form-relationships.md): Deriving fields from subforms
- **Attributes and Data Model** (01-attributes-data-model.md): Understanding `ao/computed-value`
- Fulcro Developer's Guide: UISM (UI State Machines) API documentation

## Source References

- DevelopersGuide.adoc:1552-1700 (Dynamic Forms section)
- form-options.cljc:270-294 (`fo/triggers` with all trigger types)
- attributes-options.cljc (ao/computed-value definition)
- Fulcro UISM: https://book.fulcrologic.com (State Machine chapter)
