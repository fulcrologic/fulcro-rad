# Relationships and Cardinality

## Overview

RAD relationships connect entities through reference attributes (type `:ref`). A relationship's nature is defined by its
**cardinality** (to-one or to-many), **directionality** (which entity "owns" the edge), and **ownership** (whether the
parent exclusively owns the target). These declarations drive resolver generation, save middleware behavior, and
form/report UI generation.

## Reference Attributes Fundamentals

From DevelopersGuide.adoc:473-494 and attributes.cljc:38-49:

```clojure
;; When type is :ref, specify target(s)
(when (= :ref type)
  (when-not (or (contains? m ::targets) (contains? m ::target))
    (log/warn "Reference attribute" kw "does not list target(s)")))
```

**Reference attributes** represent edges in your data graph. They can be:

- **Concrete**: Actually stored in the database (foreign keys, join tables, nested maps)
- **Virtual**: Computed by resolvers (derived relationships)

## Cardinality: To-One vs To-Many

Cardinality indicates how many targets a reference can point to (from attributes-options.cljc:102-110):

```clojure
ao/cardinality
  "OPTIONAL. Default `:one`. Can also be `:many`.

   This option indicates that this attribute either has a single value or a homogeneous set of values."
```

### To-One Relationship (Default)

**Cardinality**: `:one` (default, can be omitted)

```clojure
(defattr primary-address :account/primary-address :ref
  {ao/target :address/id
   ao/identities #{:account/id}
   ao/schema :production})
   ;; ao/cardinality :one  <-- implicit
```

An account has ONE primary address.

**Checking in Code** (attributes.cljc:100-104):

```clojure
(attr/to-one? primary-address)  ; => true
```

### To-Many Relationship

**Cardinality**: `:many`

```clojure
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many
   ao/identities #{:account/id}
   ao/schema :production})
```

An account has MANY addresses.

**Checking in Code** (attributes.cljc:94-98):

```clojure
(attr/to-many? addresses)  ; => true
```

## Target Specification

### Single Target (Monomorphic)

Most relationships point to a single entity type (from attributes-options.cljc:84-90):

```clojure
ao/target
  "REQUIRED for `:ref` attributes (unless you specify `ao/targets`). A qualified keyword
   of an `identity? true` attribute that identifies the entities/rows/docs to which
   this attribute refers."
```

**Example**:

```clojure
(defattr invoice :line-item/invoice :ref
  {ao/target :invoice/id
   ao/identities #{:line-item/id}
   ao/schema :production})
```

The `ao/target` value must be an identity attribute (one with `ao/identity? true`).

### Multiple Targets (Polymorphic)

Added in v1.3.10+ (from attributes-options.cljc:92-100):

```clojure
ao/targets
  "ALTERNATIVE to `ao/target` for `:ref` attributes.

   A SET of qualified keyword of an `identity? true` attribute that identifies the
   entities/rows/docs to which this attribute can refer."
```

**Example**:

```clojure
(defattr items :order/items :ref
  {ao/targets #{:product/id :service/id}  ; <-- SET
   ao/cardinality :many
   ao/identities #{:order/id}
   ao/schema :production})
```

An order can contain products, services, or both.

**EQL Generation** (attributes.cljc:116-129):

```clojure
(attr/attributes->eql [items-attr])
; With ao/target:  [{:order/items [:product/id]}]
; With ao/targets: [{:order/items {:product/id [:product/id]
;                                   :service/id [:service/id]}}]
```

## Ownership and Lifecycle

From DevelopersGuide.adoc:1331-1345:

> "One of the core questions in any relation is: does the referring entity/table/document 'own' the target? In other
> words does it create and destroy it?"

### Owned (Component) Relationships

**Concept**: Parent exclusively owns children. When parent is deleted, children are deleted (cascade).

**Indicator** (from attributes-options.cljc:354-365):

```clojure
ao/component?
  "Used on `:ref` attributes. An indicator the reference edge points to entities that
  are *exclusively owned* by the parent. A boolean or `(fn [owner] boolean?)`.

  This *could* be used to:
  * Generate schema auto-delete rules in database plugins.
  * Check for dropped edges during save middleware to auto-delete orphans."
```

**Example - Owned To-Many**:

```clojure
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many
   ao/component? true    ; <-- Parent owns these
   ao/identities #{:account/id}
   ao/schema :production})
```

Account owns its addresses. Deleting an account should delete its addresses.

**Example - Owned To-One**:

```clojure
(defattr billing-info :account/billing-info :ref
  {ao/target :billing/id
   ao/component? true
   ao/identities #{:account/id}
   ao/schema :production})
```

Account owns its billing info. One-to-one ownership.

### Referenced (Non-Component) Relationships

**Concept**: Target exists independently. Multiple entities can reference it.

**Indicator**: Omit `ao/component?` or set it to `false`.

**Example - Referenced To-One**:

```clojure
(defattr item :line-item/item :ref
  {ao/target :item/id
   ao/identities #{:line-item/id}
   ao/schema :production})
   ;; ao/component? false  <-- implicit
```

Line items reference inventory items, but don't own them. Many line items can point to the same item.

**Example - Referenced To-Many**:

```clojure
(defattr favorites :account/favorites :ref
  {ao/target :product/id
   ao/cardinality :many
   ao/identities #{:account/id}
   ao/schema :production})
```

Account favorites reference products. Products exist independently.

## Relationship Patterns

### Pattern 1: To-One Owned Relationship

**Use Case**: Parent creates and owns a single child (e.g., Account owns billing info).

```clojure
;; Parent (Account)
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr billing :account/billing :ref
  {ao/target :billing/id
   ao/component? true      ; Owned
   ao/identities #{:account/id}
   ao/schema :production})

;; Child (Billing)
(defattr billing-id :billing/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr card-number :billing/card-number :string
  {ao/identities #{:billing/id}
   ao/schema :production})
```

**Form Behavior**: When editing an account, billing info appears as an embedded subform.

### Pattern 2: To-Many Owned Relationship

**Use Case**: Parent owns multiple children (e.g., Invoice owns line items).

From DevelopersGuide.adoc:1493-1547:

```clojure
;; Parent (Invoice)
(defattr invoice-id :invoice/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr line-items :invoice/line-items :ref
  {ao/target :line-item/id
   ao/cardinality :many
   ao/component? true      ; Invoice owns line items
   ao/identities #{:invoice/id}
   ao/schema :production})

;; Child (LineItem)
(defattr line-item-id :line-item/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr quantity :line-item/quantity :int
  {ao/identities #{:line-item/id}
   ao/schema :production})
```

**Form Behavior**: Line items appear as a list of editable subforms with add/remove controls.

**Form Configuration** (DevelopersGuide.adoc:1527-1534):

```clojure
(form/defsc-form InvoiceForm [this props]
  {fo/id          invoice/invoice-id
   fo/attributes  [invoice/line-items ...]
   fo/subforms    {:invoice/line-items {fo/ui              LineItemForm
                                        fo/can-add-row?    (fn [parent] true)
                                        fo/can-delete-row? (fn [parent item] true)}}})
```

### Pattern 3: To-One Referenced Relationship

**Use Case**: Child references a pre-existing parent (e.g., Line item references inventory item).

From DevelopersGuide.adoc:1357-1491:

```clojure
;; Target (Inventory Item)
(defattr item-id :item/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr item-name :item/name :string
  {ao/identities #{:item/id}
   ao/schema :production})

;; Referrer (Line Item)
(defattr line-item-id :line-item/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr item :line-item/item :ref
  {ao/target :item/id
   ;; NO ao/component? - not owned
   ao/identities #{:line-item/id}
   ao/schema :production})
```

**Form Behavior**: Use a picker/dropdown to select from existing items.

**Form Configuration** (DevelopersGuide.adoc:1441-1454):

```clojure
(form/defsc-form LineItemForm [this props]
  {fo/id           line-item/line-item-id
   fo/attributes   [line-item/item ...]
   fo/field-styles {:line-item/item :pick-one}  ; <-- Picker style
   fo/field-options {:line-item/item {::picker-options/query-key :item/all-items
                                       ::picker-options/options-xform (fn [_ raw]
                                                                        (mapv
                                                                          (fn [{:item/keys [id name]}]
                                                                            {:text name :value [:item/id id]})
                                                                          raw))}}})
```

The `:pick-one` style renders a dropdown/autocomplete instead of a subform.

### Pattern 4: To-Many Referenced Relationship

**Use Case**: Parent references multiple pre-existing children (e.g., Account favorites).

```clojure
;; Target (Product)
(defattr product-id :product/id :uuid
  {ao/identity? true
   ao/schema :production})

;; Referrer (Account)
(defattr account-id :account/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr favorites :account/favorites :ref
  {ao/target :product/id
   ao/cardinality :many
   ;; NO ao/component?
   ao/identities #{:account/id}
   ao/schema :production})
```

**Form Behavior**: Use a multi-select picker or tag-style UI.

**Note**: As of writing, this pattern requires custom UI (DevelopersGuide.adoc:1548-1550).

### Pattern 5: Polymorphic Relationship

**Use Case**: Reference can point to multiple entity types.

```clojure
;; Target 1 (Product)
(defattr product-id :product/id :uuid
  {ao/identity? true
   ao/schema :production})

;; Target 2 (Service)
(defattr service-id :service/id :uuid
  {ao/identity? true
   ao/schema :production})

;; Referrer (Order)
(defattr items :order/items :ref
  {ao/targets #{:product/id :service/id}  ; <-- Multiple targets
   ao/cardinality :many
   ao/identities #{:order/id}
   ao/schema :production})
```

**Database Adapter Note**: Support varies. Some adapters may require additional configuration. Check your adapter docs.

## Bidirectional Relationships

RAD attributes are **unidirectional** by default. If you need bidirectional traversal, define attributes on both sides:

**Example - Invoice ↔ Line Items**:

```clojure
;; Forward: Invoice -> Line Items
(defattr line-items :invoice/line-items :ref
  {ao/target :line-item/id
   ao/cardinality :many
   ao/component? true
   ao/identities #{:invoice/id}
   ao/schema :production})

;; Reverse: Line Item -> Invoice
(defattr invoice :line-item/invoice :ref
  {ao/target :invoice/id
   ao/identities #{:line-item/id}
   ao/schema :production})
```

Now you can navigate both directions:

- From invoice: Query `{:invoice/line-items [:line-item/id ...]}`
- From line item: Query `{:line-item/invoice [:invoice/id ...]}`

**Database Adapter Note**: Some adapters (e.g., Datomic) can auto-generate reverse attributes. Check adapter docs.

## Relationship Configuration Matrix

| Pattern            | Cardinality      | Component? | Form Rendering            | Example                    |
|--------------------|------------------|------------|---------------------------|----------------------------|
| Owned To-One       | `:one` (default) | `true`     | Embedded subform          | Account → Billing Info     |
| Owned To-Many      | `:many`          | `true`     | List of subforms          | Invoice → Line Items       |
| Referenced To-One  | `:one` (default) | `false`    | Picker/Dropdown           | Line Item → Inventory Item |
| Referenced To-Many | `:many`          | `false`    | Multi-select (custom)     | Account → Favorites        |
| Polymorphic        | `:many` usually  | varies     | Custom or enhanced picker | Order → Products/Services  |

## Database Adapter Integration

Reference attributes work with database adapters to generate:

1. **Schema**: Foreign keys, join tables, nested structures
2. **Resolvers**: Pathom resolvers that traverse relationships
3. **Save Middleware**: Logic to save/update related entities
4. **Cascade Deletes**: Auto-delete owned children (if `ao/component? true`)

**Adapter-Specific Options**: Database adapters add their own namespaced keys.

**Example with Datomic** (from DevelopersGuide.adoc:1505-1509):

```clojure
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many
   :com.fulcrologic.rad.database-adapters.datomic/schema     :production
   :com.fulcrologic.rad.database-adapters.datomic/entity-ids #{:account/id}})
```

The `:com.fulcrologic.rad.database-adapters.datomic/*` keys tell the Datomic adapter how to map this relationship.

**See**: [11-database-adapters.md](11-database-adapters.md) for adapter-specific details.

## Form Subform Configuration

When using owned relationships in forms, configure subforms with `fo/subforms` (from form-options namespace):

```clojure
{fo/subforms {:attribute/name {fo/ui              SubformComponent
                                fo/can-add-row?    (fn [parent-props] boolean)
                                fo/can-delete-row? (fn [parent-props item-props] boolean)
                                fo/order-by        (fn [items] sorted-items)}}}
```

### Subform Options

**`fo/ui`** - REQUIRED. The form component for editing the target entity.

```clojure
fo/ui AddressForm
```

**`fo/can-add-row?`** - `(fn [parent-props] boolean-or-keyword)`. Controls add button visibility.

```clojure
fo/can-add-row? (fn [account]
                  (< (count (:account/addresses account)) 5))
```

Return values:

- `true`: Show add button, append new items
- `false`: Hide add button
- `:prepend`: Show add button, prepend new items
- `:append`: Show add button, append new items (same as `true`)

**`fo/can-delete-row?`** - `(fn [parent-props item-props] boolean)`. Controls delete button per item.

```clojure
fo/can-delete-row? (fn [account address]
                     (not (:address/primary? address)))
```

**`fo/order-by`** - `(fn [items] sorted-items)`. Custom sorting for subform items.

```clojure
fo/order-by (fn [addresses]
              (sort-by :address/created-at addresses))
```

### Complete Subform Example

From DevelopersGuide.adoc:1516-1534:

```clojure
(ns com.example.ui.account-forms
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.account :as acct]
    [com.example.model.address :as addr]
    [com.example.ui.address-forms :refer [AddressForm]]))

;; Child form
(defsc-form AddressForm [this props]
  {fo/id          addr/id
   fo/attributes  [addr/street addr/city addr/state addr/zip]})

;; Parent form with subform
(defsc-form AccountForm [this props]
  {fo/id          acct/id
   fo/attributes  [acct/name acct/addresses]
   fo/subforms    {:account/addresses {fo/ui              AddressForm
                                       fo/can-add-row?    (fn [acct] (< (count (:account/addresses acct)) 2))
                                       fo/can-delete-row? (fn [acct addr] (< 1 (count (:account/addresses acct))))}}})
```

**Behavior**:

- Can't delete if only one address remains
- Can't add more than 2 addresses
- Addresses appear as embedded forms with add/delete controls

## Picker Configuration

For referenced (non-owned) relationships, use pickers (from DevelopersGuide.adoc:1441-1479):

```clojure
{fo/field-styles  {:attribute/name :pick-one}  ; or :pick-many
 fo/field-options {:attribute/name {::picker-options/query-key       ...
                                    ::picker-options/query-component ...
                                    ::picker-options/options-xform   ...
                                    ::picker-options/cache-time-ms   ...}}}
```

### Picker Options

**`::picker-options/query-key`** - REQUIRED. Top-level EQL key that returns candidate entities.

```clojure
::picker-options/query-key :item/all-items
```

Server must have a resolver:

```clojure
(defattr all-items :item/all-items :ref
  {ao/target :item/id
   ao/pc-output [{:item/all-items [:item/id]}]
   ao/pc-resolve (fn [env _]
                   #?(:clj {:item/all-items (db/get-all-items env)}))})
```

**`::picker-options/query-component`** - OPTIONAL. UI component for normalization.

```clojure
::picker-options/query-component ItemForm
```

Allows picker options to be normalized into app database.

**`::picker-options/options-xform`** - `(fn [normalized-result raw-result] options)`. Transforms results into picker
options.

```clojure
::picker-options/options-xform (fn [normalized raw]
                                 (mapv
                                   (fn [{:item/keys [id name price]}]
                                     {:text  (str name " - $" price)
                                      :value [:item/id id]})
                                   (sort-by :item/name raw)))
```

Must return `[{:text "..." :value ident-or-value} ...]`.

**`::picker-options/cache-key`** - OPTIONAL. Key for caching options (defaults to query-key).

**`::picker-options/cache-time-ms`** - OPTIONAL. Cache duration in milliseconds (default: 100ms).

```clojure
::picker-options/cache-time-ms 60000  ; Cache for 1 minute
```

### Complete Picker Example

From DevelopersGuide.adoc:1406-1454:

```clojure
(ns com.example.ui.line-item-forms
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.picker-options :as picker-options]
    [com.example.model.line-item :as line-item]
    [com.example.ui.item-forms :refer [ItemForm]]))

(defsc-form LineItemForm [this props]
  {fo/id            line-item/id
   fo/attributes    [line-item/item line-item/quantity]

   ;; item is a ref, but render as picker
   fo/field-styles  {:line-item/item :pick-one}

   fo/field-options {:line-item/item
                     {::picker-options/query-key       :item/all-items
                      ::picker-options/query-component ItemForm
                      ::picker-options/options-xform   (fn [_ raw]
                                                         (mapv
                                                           (fn [{:item/keys [id name]}]
                                                             {:text name :value [:item/id id]})
                                                           (sort-by :item/name raw)))
                      ::picker-options/cache-time-ms   60000}}})
```

**Behavior**: `:line-item/item` renders as a dropdown of inventory items instead of a subform.

## Virtual Relationships

Relationships don't have to be stored. Create virtual edges with custom resolvers (from DevelopersGuide.adoc:481-484):

```clojure
;; Virtual to-one: Customer's most-shipped-to address
(defattr likely-address :customer/most-likely-address :ref
  {ao/target :address/id
   ao/pc-input #{:customer/id}
   ao/pc-output [{:customer/most-likely-address [:address/id]}]
   ao/pc-resolve (fn [env {:customer/keys [id]}]
                   #?(:clj
                      (let [addr-id (calc-most-likely-address env id)]
                        {:customer/most-likely-address [:address/id addr-id]})))})
```

This relationship:

- Isn't stored (no `ao/schema`)
- Computed from order history
- Appears in EQL queries like any other ref
- Can be used in forms/reports

## Important Notes

### Relationship Declaration Location

Declare relationships on the **referrer**, not the target:

```clojure
;; CORRECT: Account declares its relationship to Address
(ns com.example.model.account ...)
(defattr addresses :account/addresses :ref
  {ao/target :address/id ...})

;; INCORRECT: Don't declare this on Address
(ns com.example.model.address ...)
(defattr account :address/account :ref  ; <-- Only if you need reverse nav
  {ao/target :account/id ...})
```

### Target Must Be Identity

The `ao/target` (or `ao/targets`) must reference identity attributes:

```clojure
;; Target
(defattr id :item/id :uuid
  {ao/identity? true ...})  ; <-- MUST be identity

;; Reference
(defattr item :line-item/item :ref
  {ao/target :item/id ...})  ; <-- Points to identity
```

### Cardinality on Non-Ref Types

Some database adapters support `:many` cardinality on scalar types (e.g., a person having multiple email addresses
stored as strings). Check your adapter docs (from attributes-options.cljc:108-109).

### Component Deletion

`ao/component? true` is a **hint**. Whether cascade deletes actually happen depends on:

1. Database adapter support
2. Save middleware implementation
3. Database capabilities

Always test deletion behavior with your specific setup.

## Common Patterns Summary

### Parent-Child Owned

```clojure
{ao/target :child/id
 ao/cardinality :many
 ao/component? true}
```

### Reference Pre-existing

```clojure
{ao/target :entity/id
 ;; omit ao/component?
 fo/field-styles {:attr :pick-one}}
```

### Polymorphic

```clojure
{ao/targets #{:type1/id :type2/id}
 ao/cardinality :many}
```

### Bidirectional

```clojure
;; Define both sides
{ao/target :other/id}  ; forward
{ao/target :this/id}   ; reverse (in other namespace)
```

## Related Topics

- **Attribute Fundamentals**: [01-attributes-data-model.md](01-attributes-data-model.md) - Core attribute concepts
- **Form Relationships**: [05-form-relationships.md](05-form-relationships.md) - Detailed form relationship patterns
- **Attribute Options**: [03-attribute-options.md](03-attribute-options.md) - All available options
- **Forms Basics**: [04-forms-basics.md](04-forms-basics.md) - Form system overview
- **Database Adapters**: [11-database-adapters.md](11-database-adapters.md) - Adapter-specific relationship handling

## Source References

### Primary Sources

- **Referential Attributes**: DevelopersGuide.adoc:473-494
- **Relationship Lifecycle**: DevelopersGuide.adoc:1331-1551
- **To-One Owned**: DevelopersGuide.adoc:1346-1356
- **To-One Referenced**: DevelopersGuide.adoc:1357-1492
- **To-Many Owned**: DevelopersGuide.adoc:1493-1547
- **To-Many Referenced**: DevelopersGuide.adoc:1548-1551

### Code References

- **Target/Targets Options**: attributes-options.cljc:84-100
- **Cardinality Option**: attributes-options.cljc:102-110
- **Component Option**: attributes-options.cljc:354-365
- **Cardinality Helpers**: attributes.cljc:94-104 (to-many?, to-one?)
- **EQL Generation**: attributes.cljc:116-129 (attributes->eql)
- **Reference Validation**: attributes.cljc:46-49

### Form/Picker Options

- **Subform Configuration**: form-options namespace `fo/subforms`, `fo/can-add-row?`, `fo/can-delete-row?`
- **Picker Configuration**: picker-options namespace (see DevelopersGuide.adoc:1466-1478)
- **Field Styles**: form-options namespace `fo/field-styles`
