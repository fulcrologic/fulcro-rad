# Form Relationships

## Overview

RAD forms handle relationships between entities through subforms and pickers. The key distinction is **ownership**: does
the parent entity create/destroy the related entity (owned), or does it simply reference pre-existing entities (
non-owned)? This document covers how to configure and render to-one and to-many relationships in forms.

## Core Concepts

From DevelopersGuide.adoc:1331-1344:

**Ownership** determines UI and lifecycle:

- **Owned relationships**: Parent creates/destroys children. Rendered as editable subforms with add/delete controls.
- **Non-owned relationships**: Parent references existing entities. Rendered as pickers (dropdowns, autocomplete).

**Database adapters** handle cascading deletes for owned relationships (e.g., `CASCADE` in SQL, `isComponent` in
Datomic).

**Form configuration** is where you specify:

- Whether a relationship is rendered as a subform or picker
- What component renders the child
- Add/delete permissions for owned relationships
- Picker options for non-owned relationships

## To-One Relationships

### To-One Owned (Subform)

From DevelopersGuide.adoc:1346-1356:

When a child entity is **created by and exclusively owned** by the parent, render it as a subform. The child gets a
tempid on parent creation, and should be deleted if the parent drops the reference.

**Example**: Account has one Address (owned)

```clojure
;; Model definition
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr name :account/name :string
  {ao/identities #{:account/id}
   ao/schema     :production})

(defattr address :account/address :ref
  {ao/target      :address/id
   ao/cardinality :one
   ao/identities  #{:account/id}
   ao/schema      :production})
```

```clojure
;; Address model
(ns com.example.model.address
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :address/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr street :address/street :string
  {ao/identities #{:address/id}
   ao/schema     :production})

(defattr city :address/city :string
  {ao/identities #{:address/id}
   ao/schema     :production})

(defattr state :address/state :string
  {ao/identities #{:address/id}
   ao/schema     :production})

(defattr zip :address/zip :string
  {ao/identities #{:address/id}
   ao/schema     :production})
```

```clojure
;; Forms
(ns com.example.ui.forms
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.address :as addr]
    [com.example.model.account :as acct]))

(defsc-form AddressForm [this props]
  {fo/id         addr/id
   fo/attributes [addr/street addr/city addr/state addr/zip]})

(defsc-form AccountForm [this props]
  {fo/id         acct/id
   fo/attributes [acct/name acct/address]
   fo/subforms   {:account/address {fo/ui AddressForm}}})
```

**Default Values for To-One Refs**:

From form-options.cljc:186-189:

> "NOTE: For to-one :ref types there will be NO default unless you specify one. This is desirable because sometimes you
> have a 0-or-1 relation. In order to ensure that a ref auto-creates a child with a tempid, add a `default-value` of an
> empty map."

To **ensure** a child is created with the parent:

```clojure
(defattr address :account/address :ref
  {ao/target       :address/id
   ao/cardinality  :one
   ao/identities   #{:account/id}
   ao/schema       :production
   fo/default-value {}})  ; Forces child creation with tempid
```

Or on the form:

```clojure
(defsc-form AccountForm [this props]
  {fo/id            acct/id
   fo/attributes    [acct/name acct/address]
   fo/subforms      {:account/address {fo/ui AddressForm}}
   fo/default-values {:account/address {}}})  ; Overrides attribute default
```

**Alternative**: If you don't want the complexity of a relationship, flatten the attributes onto the parent:

```clojure
;; Instead of :account/address -> :address/street
;; Just use:
(defattr primary-street :account/primary-street :string ...)
(defattr primary-city :account/primary-city :string ...)
```

### To-One Non-Owned (Picker)

From DevelopersGuide.adoc:1357-1491:

When a child entity **already exists** and the parent just selects it, use a picker (`:pick-one` field style).

**Status Note**: From DevelopersGuide.adoc:1359-1361:
> "NOTE: This use-case is partially implemented. It will work well when selecting from a relatively small set of
> targets, but will not currently perform well if the list of potential targets is many thousands or greater."

**Example**: LineItem references an existing Item from inventory

```clojure
;; Item model (inventory)
(ns com.example.model.item
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :item/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr item-name :item/name :string
  {ao/identities #{:item/id}
   ao/schema     :production})

(defattr price :item/price :decimal
  {ao/identities #{:item/id}
   ao/schema     :production})
```

```clojure
;; LineItem model
(ns com.example.model.line-item
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :line-item/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr item :line-item/item :ref
  {ao/target      :item/id
   ao/required?   true
   ao/cardinality :one
   ao/identities  #{:line-item/id}
   ao/schema      :production})

(defattr quantity :line-item/quantity :int
  {ao/required?  true
   ao/identities #{:line-item/id}
   ao/schema     :production})
```

From DevelopersGuide.adoc:1441-1454 and form-options.cljc:93-106:

```clojure
(ns com.example.ui.forms
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.picker-options :as po]
    [com.example.model.line-item :as line-item]
    [com.example.model.item :as item]
    [com.example.ui.item-forms :as item-forms]))

(defsc-form LineItemForm [this props]
  {fo/id           line-item/id
   fo/attributes   [line-item/item line-item/quantity]

   ;; Indicate this ref field should render as a picker
   fo/field-styles {:line-item/item :pick-one}

   ;; Configure the picker
   fo/field-options {:line-item/item {po/query-key       :item/all-items
                                       po/query-component item-forms/ItemForm
                                       po/options-xform   (fn [normalized-result raw-response]
                                                            (mapv
                                                              (fn [{:item/keys [id name price]}]
                                                                {:text  (str name " - $" price)
                                                                 :value [:item/id id]})
                                                              (sort-by :item/name raw-response)))
                                       po/cache-time-ms   60000}}})
```

**Picker Options** (from picker-options.cljc:69-102 and DevelopersGuide.adoc:1468-1478):

From form-options.cljc:108-121:
> "When used on a form: A map from *qualified keyword* of attributes to a map of options targeted to the specific UI
> control for that field."

Required options for `:pick-one`:

- **`po/query-key`** (picker-options.cljc:199-203): Top-level EDN query key that returns the entities to choose from (
  e.g., `:item/all-items`).

- **`po/query-component`** (picker-options.cljc:206-210): UI component with subquery for normalizing options into the
  Fulcro database. Can be a class, registry key, or `(fn [form-options k] ...)`. If not supplied, options stored only in
  cache.

- **`po/options-xform`** (picker-options.cljc:232-237): A `(fn [normalized-result raw-result] picker-options)` that
  transforms query results into `[{:text "..." :value ident} ...]` format. The `:value` must be an ident like
  `[:item/id uuid]`.

Optional:

- **`po/cache-key`** (picker-options.cljc:226-230): Keyword or `(fn [cls props] keyword?)` for caching options. Defaults
  to `query-key`.

- **`po/cache-time-ms`** (picker-options.cljc:239-244): Cache duration in milliseconds. Defaults to 100ms.

- **`po/query-parameters`** (picker-options.cljc:219-224): Map or `(fn [app cls props] map?)` for query params.

- **`po/remote`** (picker-options.cljc:193-197): Remote name for loading options. Defaults to `:remote`.

**Server-Side Resolver**:

From DevelopersGuide.adoc:1481-1491:

```clojure
(ns com.example.model.item
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.wsscode.pathom.connect :as pc]))

(defattr all-items :item/all-items :ref
  {ao/target   :item/id
   ::pc/output [{:item/all-items [:item/id]}]
   ::pc/resolve (fn [{:keys [query-params] :as env} _]
                  #?(:clj
                     {:item/all-items (queries/get-all-items env query-params)}))})
```

**Field Style Configuration**:

From form-options.cljc:84-106:

You can set `fo/field-styles` on a form **or** use `fo/field-style` on the attribute itself:

```clojure
;; On the attribute (applies to all forms using it)
(defattr item :line-item/item :ref
  {ao/target      :item/id
   ao/cardinality :one
   ao/identities  #{:line-item/id}
   ao/schema      :production
   fo/field-style :pick-one})

;; On the form (overrides attribute default)
(defsc-form LineItemForm [this props]
  {fo/id          line-item/id
   fo/attributes  [line-item/item line-item/quantity]
   fo/field-styles {:line-item/item :pick-one}})
```

## To-Many Relationships

### To-Many Owned (Subforms with Add/Delete)

From DevelopersGuide.adoc:1493-1546:

When the parent **owns** multiple children, render them as a list of subforms with add/delete controls.

**Example**: Account has many Addresses

```clojure
;; Account model
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr name :account/name :string
  {ao/identities #{:account/id}
   ao/schema     :production})

(defattr addresses :account/addresses :ref
  {ao/target      :address/id
   ao/cardinality :many
   ao/identities  #{:account/id}
   ao/schema      :production
   ;; Database-specific markers for ownership (example for Datomic)
   :com.fulcrologic.rad.database-adapters.datomic/entity-ids #{:account/id}})
```

```clojure
;; Address model (same as to-one example)
(ns com.example.model.address
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :address/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr street :address/street :string
  {ao/identities #{:address/id}
   ao/schema     :production})

(defattr city :address/city :string
  {ao/identities #{:address/id}
   ao/schema     :production})

(defattr state :address/state :string
  {ao/identities #{:address/id}
   ao/schema     :production})

(defattr zip :address/zip :string
  {ao/identities #{:address/id}
   ao/schema     :production})
```

From DevelopersGuide.adoc:1523-1534 and form-options.cljc:202-232:

```clojure
(ns com.example.ui.forms
  (:require
    [com.fulcrologic.rad.form :as form :refer [defsc-form]]
    [com.fulcrologic.rad.form-options :as fo]
    [com.example.model.address :as addr]
    [com.example.model.account :as acct]))

(defsc-form AddressForm [this props]
  {fo/id           addr/id
   fo/attributes   [addr/street addr/city addr/state addr/zip]
   fo/cancel-route ["landing-page"]
   fo/route-prefix "address"})

(defsc-form AccountForm [this props]
  {fo/id           acct/id
   fo/attributes   [acct/name acct/addresses]
   fo/cancel-route ["landing-page"]
   fo/route-prefix "account"
   fo/subforms     {:account/addresses {fo/ui          AddressForm
                                         fo/can-delete? (fn [parent-props item-props]
                                                          (< 1 (count (:account/addresses parent-props))))
                                         fo/can-add?    (fn [parent-props]
                                                          (< (count (:account/addresses parent-props)) 2))}}})
```

**Subform Options** (from form-options.cljc:202-232):

From form-options.cljc:203-204:
> "A map from qualified key to a sub-map that describes details for what to use when a form attribute is a ref."

The submap can be a map or a `(fn [form-component-options ref-attr] optionsmap)`.

**`fo/ui`** (form-options.cljc:309-314):
> "Used within `subform` or `subforms`. This should be the Form component that will be used to render instances of the
> subform. Can be one of: A component, component registry key, or `(fn [form-options ref-key] comp-or-reg-key)`"

**`fo/can-delete?`** (form-options.cljc:360-364):
> "Used in `subforms` maps to control when a child can be deleted. This option is a boolean or a
`(fn [parent-form-instance row-props] boolean?)` that is used to determine if the given child can be deleted by the
> user."

From DevelopersGuide.adoc:1540-1541:

- Lambda receives current **parent props** and a **referred item props**
- If returns `true`, item shows a delete button
- Example: Don't allow deletion if it's the only address

**`fo/can-add?`** (form-options.cljc:366-373):
> "Used in `subforms` maps to control when a child of that type can be added across its relation. This option is a
> boolean or a `(fn [form-instance attribute] boolean?)` that is used to determine if the given child (reachable through
`attribute` (a ref attribute)) can be added as a child to `form-instance`.
>
> NOTE: You can return the truthy value `:prepend` from this function to ask the form to put new children at the top of
> the list."

From DevelopersGuide.adoc:1542-1544:

- Lambda receives current **parent props** (Note: guide says this, but source says `form-instance` and `attribute`)
- If returns `true`, UI includes an add control
- Can return `:append` (default) or `:prepend` to control where new items appear
- Example: Limit to 2 addresses maximum

**`fo/sort-children`** (form-options.cljc:336-347):
> "This option goes *within* ::subforms and defines how to sort those subform UI components when there are more than
> one. It is a `(fn [denormalized-children] sorted-children)`."

Example:

```clojure
{fo/subforms {:person/children {fo/ui           PersonForm
                                 fo/sort-children (fn [children]
                                                    (sort-by :person/name children))}}}
```

**Alternative Subform Specification**:

From form-options.cljc:234-250:

You can also place subform options **on the attribute** using `fo/subform` (singular):

```clojure
(defattr person-address :person/address :ref
  {fo/subform {fo/ui AddressForm}})

;; Or with a function
(defattr person-address :person/address :ref
  {fo/subform (fn [form-instance ref-attr]
                {fo/ui AddressForm})})
```

Use `fo/subform-options` helper (form-options.cljc:464-485) to properly retrieve subform options in code.

### To-Many Non-Owned (Multi-Picker)

From DevelopersGuide.adoc:1548-1551:

> "NOTE: This use-case is not yet implemented."

**Current Status**: RAD does not yet provide built-in support for selecting multiple pre-existing entities in a to-many
relationship. You would need to implement custom UI controls for this scenario.

## Common Patterns

### Conditional Subform Rendering

Use `fo/fields-visible?` to conditionally show/hide relationship fields:

```clojure
(defsc-form AccountForm [this props]
  {fo/id              acct/id
   fo/attributes      [acct/name acct/addresses acct/type]
   fo/fields-visible? {:account/addresses (fn [form-instance]
                                             (let [{:account/keys [type]} (comp/props form-instance)]
                                               (= type :business)))}
   fo/subforms        {:account/addresses {fo/ui AddressForm}}})
```

### Dynamic Add/Delete Logic

```clojure
{fo/subforms {:invoice/line-items
              {fo/ui          LineItemForm
               fo/can-delete? (fn [parent-props item-props]
                                ;; Can't delete if it's the only item
                                ;; OR if the invoice is already submitted
                                (and (< 1 (count (:invoice/line-items parent-props)))
                                     (not= (:invoice/status parent-props) :submitted)))
               fo/can-add?    (fn [parent-props]
                                ;; Can add unless invoice is submitted
                                (not= (:invoice/status parent-props) :submitted))}}}
```

### Nested Subforms (Subforms within Subforms)

```clojure
;; Invoice -> LineItem -> Item selection
(defsc-form LineItemForm [this props]
  {fo/id           line-item/id
   fo/attributes   [line-item/item line-item/quantity]
   fo/field-styles {:line-item/item :pick-one}
   fo/field-options {:line-item/item {po/query-key :item/all-items
                                       ...}}})

(defsc-form InvoiceForm [this props]
  {fo/id         invoice/id
   fo/attributes [invoice/number invoice/date invoice/line-items]
   fo/subforms   {:invoice/line-items {fo/ui          LineItemForm
                                        fo/can-delete? (constantly true)
                                        fo/can-add?    (constantly true)}}})
```

### Default Values for Relationships

To initialize a new form with related entities:

```clojure
;; On the attribute
(defattr addresses :account/addresses :ref
  {ao/target       :address/id
   ao/cardinality  :many
   ao/identities   #{:account/id}
   fo/default-value [{}]})  ; Start with one empty address

;; Or on the form
(defsc-form AccountForm [this props]
  {fo/id            acct/id
   fo/attributes    [acct/name acct/addresses]
   fo/default-values {:account/addresses [{}]}  ; Overrides attribute
   fo/subforms      {:account/addresses {fo/ui AddressForm}}})
```

## Important Notes

### 1. Ownership vs. Reference

**Ownership** is primarily a **rendering and UX concern**. Database adapters handle cascading deletes, but you need to
configure them appropriately:

- **Datomic**: Use `:db/isComponent true`
- **SQL**: Use `CASCADE` on foreign key constraints
- **Custom adapters**: Implement appropriate save middleware

From DevelopersGuide.adoc:1349:
> "You may need to add save middleware" if your database adapter doesn't implement cascading deletes.

### 2. No Default for To-One Refs

From form-options.cljc:186-189:

**To-one ref attributes do NOT auto-create children** unless you explicitly set `fo/default-value` to `{}`. This is
intentional to support optional (0-or-1) relationships.

### 3. Subform Queries Are Auto-Generated

You don't need to manually compose queries. RAD automatically:

- Generates the parent query including ref attributes
- Recursively includes subform queries
- Handles normalization via Fulcro

### 4. Picker Performance

From DevelopersGuide.adoc:1359-1361:

The current `:pick-one` implementation **pre-loads all options**. This works well for small sets (< 1000s) but not for
large datasets. For large option sets, you'll need to implement custom controls with search/pagination.

### 5. Form State Machine Integration

Subforms participate in the parent form's state machine. When you save a parent:

- RAD collects deltas from all subforms
- Sends them as a single transaction to the server
- Database adapters handle cascading saves

### 6. Routing for Subforms

From DevelopersGuide.adoc:1519-1520:

Subforms can have their own routes (`fo/route-prefix`, `fo/cancel-route`), but when used as subforms, they're rendered
inline. The routing configuration is useful if the same form is also used as a standalone routed form.

### 7. Server Query for Picker Options

From DevelopersGuide.adoc:1485-1491:

Your server must provide a resolver for `po/query-key`. If using RAD's attribute-to-resolver generator, define an
attribute with `::pc/output` and `::pc/resolve`:

```clojure
(defattr all-items :item/all-items :ref
  {ao/target   :item/id
   ::pc/output [{:item/all-items [:item/id]}]
   ::pc/resolve (fn [env _] {:item/all-items (fetch-all-items env)})})
```

## Related Topics

- **Attributes and Data Model** (01-attributes-data-model.md): How to define `:ref` attributes with `ao/target` and
  `ao/cardinality`.
- **Relationships and Cardinality** (02-relationships-cardinality.md): Deep dive into to-one, to-many, owned vs.
  referenced relationships.
- **Forms Basics** (04-forms-basics.md): Core form operations (`form/create!`, `form/edit!`, `form/save!`).
- **Form Validation** (06-form-validation.md): Validating relationship fields.
- **Dynamic Forms** (07-dynamic-forms.md): Using `fo/triggers` for relationship-driven behavior.
- **Database Adapters** (11-database-adapters.md): How adapters handle relationship saves and cascading deletes.

## Source References

- DevelopersGuide.adoc:1331-1551 (Relationship Lifecycle section)
- form-options.cljc:202-232 (`fo/subforms`)
- form-options.cljc:234-250 (`fo/subform`)
- form-options.cljc:309-314 (`fo/ui`)
- form-options.cljc:360-364 (`fo/can-delete?`)
- form-options.cljc:366-373 (`fo/can-add?`)
- form-options.cljc:336-347 (`fo/sort-children`)
- form-options.cljc:84-106 (`fo/field-styles`)
- form-options.cljc:108-121 (`fo/field-options`)
- form-options.cljc:186-200 (`fo/default-value`, `fo/default-values`)
- picker-options.cljc:1-288 (Complete picker options API)
- picker-options.cljc:46-102 (`load-picker-options!`)
- picker-options.cljc:199-244 (Picker option keys: `query-key`, `query-component`, `options-xform`, `cache-key`,
  `cache-time-ms`)
