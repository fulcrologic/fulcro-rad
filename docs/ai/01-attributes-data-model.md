# Attributes and Data Model

## Overview

Fulcro RAD is **attribute-centric**. An attribute is an RDF-style description of a single fact about your domain,
defined as an open map with a qualified keyword name and a type. Attributes are the foundation of RAD - they define your
data model, generate resolvers, drive form/report behavior, and enable database schema generation. Unlike rigid
class/table schemas, RAD's graph-based approach allows attributes to exist across multiple entities and be resolved from
various sources.

## The defattr Macro

Attributes are defined using the `defattr` macro from `com.fulcrologic.rad.attributes`:

**Signature** (from attributes.cljc:54-92):

```clojure
(defattr symbol qualified-keyword data-type options-map)
```

**Minimal Example**:

```clojure
(ns com.example.model.item
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defattr id :item/id :uuid
  {::attr/identity? true
   ::attr/schema :production})
```

This expands to:

```clojure
(def id {::attr/qualified-key :item/id
         ::attr/type :uuid
         ::attr/identity? true
         ::attr/schema :production})
```

**Key Points**:

- `symbol`: The var name (e.g., `id`)
- `qualified-keyword`: Must be fully-qualified (e.g., `:item/id`)
- `data-type`: One of `:string`, `:uuid`, `:int`, `:long`, `:decimal`, `:instant`, `:boolean`, `:keyword`, `:symbol`,
  `:ref`, `:enum`
- `options-map`: Open map for additional configuration
- **IMPORTANT**: For full-stack apps, attributes MUST be defined in `.cljc` files

## Required Attribute Properties

Only two things are required (automatically added by `defattr`):

1. `::attr/qualified-key` - The attribute's keyword name
2. `::attr/type` - The data type

Everything else is optional, though database adapters typically require additional options like `::attr/schema` or
`::attr/identities`.

## Identity Attributes

Identity attributes act as primary keys for entities. They are marked with `::attr/identity? true` (from
attributes-options.cljc:13-18):

```clojure
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})
```

**What identity? Does**:

- Marks the attribute as a primary/natural key
- Used by database adapters to identify rows/entities/documents
- Used by forms/reports to uniquely access data
- Becomes the lookup key for other attributes (via `ao/identities`)

**Multiple Identities**: An entity can have multiple identity attributes (e.g., `:account/id` and `:account/email` could
both be identities).

## Scalar Attributes

Most attributes hold simple values. Here's a complete entity example:

```clojure
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

;; Identity
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})

;; Scalars
(defattr name :account/name :string
  {ao/required? true
   ao/identities #{:account/id}
   ao/schema :production})

(defattr email :account/email :string
  {ao/required? true
   ao/identities #{:account/id}
   ao/schema :production})

(defattr active? :account/active? :boolean
  {ao/identities #{:account/id}
   ao/schema :production})

(defattr balance :account/balance :decimal
  {ao/identities #{:account/id}
   ao/schema :production})

(defattr created-at :account/created-at :instant
  {ao/identities #{:account/id}
   ao/schema :production})

;; Collection of all attributes in this namespace
(def attributes [id name email active? balance created-at])
```

## Supported Data Types

From attributes.cljc comments and DevelopersGuide.adoc:495-545:

| Type       | Description                 | Example Usage                   |
|------------|-----------------------------|---------------------------------|
| `:string`  | Variable-length text        | Names, emails, descriptions     |
| `:uuid`    | UUID identifier             | Primary keys, unique IDs        |
| `:int`     | 32-bit integer              | Counts, small numbers           |
| `:long`    | 64-bit integer              | Large numbers, IDs              |
| `:decimal` | Arbitrary-precision number  | Money, precise calculations     |
| `:instant` | UTC timestamp               | Dates, times, created-at        |
| `:boolean` | true/false                  | Flags, switches                 |
| `:keyword` | EDN keyword                 | Enum-like values                |
| `:symbol`  | EDN symbol                  | Rarely used                     |
| `:enum`    | Enumerated values           | Requires `ao/enumerated-values` |
| `:ref`     | Reference to another entity | Relationships (see below)       |

**Type Extensibility**: RAD's type system is open. Database adapters and rendering plugins can add support for custom
types.

## The Schema Concept

The `::attr/schema` option groups attributes into logical entities (tables/documents) (from attributes-options.cljc:
46-52):

```clojure
ao/schema
  "OPTIONAL. A keyword.

   Abstractly names a schema on which this attribute lives. Schemas are just names you make up that allow you to
   organize the physical location of attributes in databases."
```

**How Schema Works**:

1. All attributes with the same `::attr/schema` value form a logical entity
2. Database adapters use schema to generate tables/collections/entities
3. Schema names are arbitrary - you choose them
4. Attributes with different schemas are separate entities

**Example - Two Entities**:

```clojure
;; Account entity
(defattr account-id :account/id :uuid
  {ao/identity? true
   ao/schema :production})  ; <-- schema

(defattr account-name :account/name :string
  {ao/identities #{:account/id}
   ao/schema :production})  ; <-- same schema

;; Address entity
(defattr address-id :address/id :uuid
  {ao/identity? true
   ao/schema :production})  ; <-- different identity, forms separate entity

(defattr street :address/street :string
  {ao/identities #{:address/id}
   ao/schema :production})
```

Here we have **two entities**: Account and Address, both in the `:production` schema namespace but distinguished by
their identity attributes.

## The Identities Option

Non-identity attributes use `::attr/identities` to declare which entities they belong to (from attributes-options.cljc:
20-32):

```clojure
ao/identities
  "OPTIONAL/REQUIRED. Database adapters usually require this option for persisted attributes.

  A set of qualified keys of attributes that serve as an identity for an entity/doc/row. This is how a particular
  attribute declares where it \"lives\" in a persistent data model..."
```

**Key Insight**: An attribute can belong to MULTIPLE entities by listing multiple identity keys:

```clojure
;; Password hash lives on multiple entity types
(defattr password-hash :password/hash :string
  {ao/required? true
   ao/identities #{:account/id :file/id :sftp-endpoint/id}
   ao/schema :production})
```

This tells RAD:

- `:password/hash` can be found via `:account/id` (stored on Account table)
- It can also be found via `:file/id` (stored on File table)
- It can also be found via `:sftp-endpoint/id` (stored on SFTP Endpoint table)

Database adapters use this to:

- Add columns to the right SQL tables
- Generate resolvers that can find `:password/hash` from any of those IDs

## Reference Attributes (Introduction)

Attributes with type `:ref` connect entities. They represent edges in your data graph (from DevelopersGuide.adoc:
473-494).

**Basic To-One Reference**:

```clojure
(defattr address :account/address :ref
  {ao/target :address/id
   ao/identities #{:account/id}
   ao/schema :production})
```

This creates:

- A reference from Account to Address
- An account can have ONE address (cardinality defaults to `:one`)
- The target is identified by `:address/id`

**To-Many Reference**:

```clojure
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many
   ao/identities #{:account/id}
   ao/schema :production})
```

Now an account can have MANY addresses.

**Polymorphic References (v1.3.10+)**:

```clojure
(defattr items :order/items :ref
  {ao/targets #{:product/id :service/id}  ; <-- SET of targets
   ao/cardinality :many
   ao/identities #{:order/id}
   ao/schema :production})
```

An order can contain products OR services (or both).

**Important Options for References** (from attributes-options.cljc:84-110):

- `ao/target` - REQUIRED (or use `ao/targets`). The identity keyword of the target entity
- `ao/targets` - Alternative to `target` for polymorphic refs. A SET of identity keywords
- `ao/cardinality` - `:one` (default) or `:many`
- `ao/component?` - Boolean. Indicates exclusive ownership (may enable cascade deletes)

**For detailed relationship patterns, see**: [02-relationships-cardinality.md](02-relationships-cardinality.md)

## Model Organization

Recommended file structure (from DevelopersGuide.adoc:331-365):

```
src/main/com/example/
├── model/
│   ├── account.cljc       ; :account/* attributes
│   ├── address.cljc       ; :address/* attributes
│   ├── invoice.cljc       ; :invoice/* attributes
│   ├── item.cljc          ; :item/* attributes
│   └── line_item.cljc     ; :line-item/* attributes
└── model.cljc             ; Combines all attributes
```

**Each model namespace** (e.g., `model/account.cljc`):

```clojure
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})

(defattr name :account/name :string
  {ao/required? true
   ao/identities #{:account/id}
   ao/schema :production})

;; Export all attributes
(def attributes [id name])

;; Export resolvers (if any custom resolvers defined here)
(def resolvers [])
```

**Central model namespace** (`model.cljc`):

```clojure
(ns com.example.model
  (:require
    [com.example.model.account :as account]
    [com.example.model.address :as address]
    [com.example.model.invoice :as invoice]
    [com.fulcrologic.rad.attributes :as attr]))

;; Combine all attributes
(def all-attributes (vec (concat
                           account/attributes
                           address/attributes
                           invoice/attributes)))

;; Lookup map (attribute keyword -> attribute)
(def key->attribute (attr/attribute-map all-attributes))

;; Form validator based on attributes
(def default-validator (attr/make-attribute-validator all-attributes))
```

**Why this pattern?**

- Attributes live in namespace matching their keyword (`:account/id` in `model.account`)
- Prevents accidental duplicates
- Explicit combination (no hidden registries)
- Compiler checks requires (won't run if you miss a namespace)

## Using Attribute Options Namespaces

RAD libraries provide `*-options` namespaces with documented vars for all keys (from DevelopersGuide.adoc:208-234):

```clojure
(ns com.example.model.item
  (:require
    [com.fulcrologic.rad.attributes-options :as ao]  ; <-- options namespace
    [com.fulcrologic.rad.attributes :refer [defattr]]))

(defattr id :item/id :uuid
  {ao/identity? true      ; <-- use vars instead of ::attr/identity?
   ao/schema :production})
```

**Benefits**:

- Autocomplete in your IDE
- Docstrings on hover
- Compile-time checking (catch typos)
- Documentation built into the code

**Available options namespaces**:

- `com.fulcrologic.rad.attributes-options` (ao) - Core attribute options
- `com.fulcrologic.rad.form-options` (fo) - Form-specific options
- `com.fulcrologic.rad.report-options` (ro) - Report-specific options
- `com.fulcrologic.rad.picker-options` (po) - Picker/dropdown options

## Common Attribute Options

Key options from attributes-options.cljc (see [03-attribute-options.md](03-attribute-options.md) for complete list):

### Core Options

- `ao/identity?` - Boolean. Marks as primary key
- `ao/identities` - Set of identity keywords. Where this attribute "lives"
- `ao/schema` - Keyword. Logical schema grouping
- `ao/required?` - Boolean. Validation hint (default: false)
- `ao/type` - Auto-added by defattr. The data type
- `ao/qualified-key` - Auto-added by defattr. The attribute name

### Reference Options

- `ao/target` - Keyword. Target identity for `:ref` type
- `ao/targets` - Set. Multiple targets for polymorphic refs
- `ao/cardinality` - `:one` or `:many` (default: `:one`)
- `ao/component?` - Boolean. Indicates exclusive ownership

### Display Options

- `ao/label` - String or `(fn [this] string)`. Display label
- `ao/style` - Keyword or fn. Format hint (e.g., `:USD`, `:password`)
- `ao/field-style-config` - Map. Rendering plugin options

### Validation

- `ao/valid?` - `(fn [value props qualified-key] boolean)`. Custom validator
- `ao/read-only?` - Boolean or fn. Prevents writes

### Enum Support

- `ao/enumerated-values` - Set. Legal values for `:enum` type
- `ao/enumerated-labels` - Map. Keyword -> display string

### Pathom Integration

- `ao/pc-output`, `ao/pc-resolve`, `ao/pc-input` - Pathom 2 resolver
- `ao/pathom3-output`, `ao/pathom3-resolve`, `ao/pathom3-input` - Pathom 3 resolver
- `ao/pc-transform`, `ao/pathom3-transform` - Resolver transformers
- `ao/pathom3-batch?` - Boolean. Batch resolver support

## Complete Entity Example

Here's a real-world Account entity with relationships:

```clojure
(ns com.example.model.account
  (:require
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

;; Identity
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})

;; Scalars
(defattr name :account/name :string
  {ao/required? true
   ao/identities #{:account/id}
   ao/schema :production
   ao/label "Account Name"})

(defattr email :account/email :string
  {ao/required? true
   ao/identities #{:account/id}
   ao/schema :production})

(defattr role :account/role :enum
  {ao/identities #{:account/id}
   ao/schema :production
   ao/enumerated-values #{:role/user :role/admin :role/guest}
   ao/enumerated-labels {:role/user "User"
                         :role/admin "Administrator"
                         :role/guest "Guest"}})

(defattr active? :account/active? :boolean
  {ao/identities #{:account/id}
   ao/schema :production})

;; To-one reference
(defattr primary-address :account/primary-address :ref
  {ao/target :address/id
   ao/identities #{:account/id}
   ao/schema :production})

;; To-many reference
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many
   ao/identities #{:account/id}
   ao/schema :production
   ao/component? true})  ; Addresses are owned by account

;; Export
(def attributes [id name email role active? primary-address addresses])
```

## Enum Attributes

Enums require special configuration (from attributes-options.cljc:34-44):

```clojure
(defattr status :order/status :enum
  {ao/identities #{:order/id}
   ao/schema :production
   ao/enumerated-values #{:status/pending :status/shipped :status/delivered :status/cancelled}
   ao/enumerated-labels {:status/pending "Pending"
                         :status/shipped "Shipped"
                         :status/delivered "Delivered"
                         :status/cancelled "Cancelled"}})
```

If you omit `ao/enumerated-labels`, labels default to capitalized keyword names.

## Computed/Virtual Attributes

Attributes can have custom resolvers for derived data:

```clojure
(defattr full-name :account/full-name :string
  {ao/identities #{:account/id}
   ao/pc-input #{:account/first-name :account/last-name}
   ao/pc-output [:account/full-name]
   ao/pc-resolve (fn [env {:account/keys [first-name last-name]}]
                   {:account/full-name (str first-name " " last-name)})})
```

This attribute:

- Isn't stored (no `ao/schema`)
- Requires `:account/first-name` and `:account/last-name` as input
- Computes the full name dynamically
- Uses Pathom 2 resolver syntax (use `ao/pathom3-*` for Pathom 3)

## Attributes on Multiple Entities

An attribute can exist on multiple entity types:

```clojure
;; Shared across account, file, and SFTP endpoint
(defattr created-at :timestamp/created-at :instant
  {ao/identities #{:account/id :file/id :sftp-endpoint/id}
   ao/schema :production})

;; All three entities get this attribute
;; Database adapters will add it to all three tables
```

This is powerful for:

- Shared concerns (timestamps, audit fields)
- Common attributes (names, descriptions)
- Cross-cutting data

## Important Notes

### CLJC Requirement

**From attributes.cljc:57-59**: "IF YOU ARE DOING FULL-STACK, THEN THESE MUST BE DEFINED IN CLJC FILES FOR RAD TO WORK!"

- Use `.cljc` for full-stack apps
- Use `.clj` for JVM-only rendering plugins
- Use `.cljs` for client-side database adapters (rare)

### Qualified Keywords Required

Attribute names must be fully-qualified (namespace/name). This ensures uniqueness and supports RAD's model organization.

### Schema vs Namespace

- **Namespace** (`:account/id`): The keyword's namespace part
- **Schema** (`ao/schema :production`): Logical grouping for database storage
- They're independent - `:account/id` could have `ao/schema :admin` if desired

### Open Maps

Attributes are open maps. Add your own namespaced keys:

```clojure
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production
   :my.app/audit-log? true           ; <-- custom key
   :my.app/pii-data? true})          ; <-- custom key
```

Database adapters, form plugins, and your own code can read these custom keys.

## Helper Functions

From attributes.cljc:94-129:

```clojure
;; Check cardinality
(attr/to-many? attribute)   ; => true if cardinality is :many
(attr/to-one? attribute)    ; => true if cardinality is not :many

;; Generate EQL for attributes
(attr/attributes->eql [id name addresses])
; => [:account/id :account/name {:account/addresses [:address/id]}]

;; Build attribute map
(attr/attribute-map all-attributes)
; => {:account/id {...} :account/name {...} ...}

;; Create validator
(attr/make-attribute-validator all-attributes)
; => (fn [form field] ...) for use with forms
```

## Hot Code Reload (Development)

From DevelopersGuide.adoc:589-603:

Attributes are normally immutable maps. For faster development, enable mutable attributes:

```bash
# Start JVM with system property
java -Drad.dev=true ...
```

Or in deps.edn:

```clojure
:jvm-opts ["-Drad.dev=true"]
```

With this enabled, re-evaluating a `defattr` in the REPL updates ALL closures over that attribute immediately. You still
need to reload namespaces when adding/removing attributes, but changes to existing attributes are instant.

## Related Topics

- **Relationships in Depth**: [02-relationships-cardinality.md](02-relationships-cardinality.md) - To-one, to-many,
  ownership, polymorphic refs
- **Complete Options Reference**: [03-attribute-options.md](03-attribute-options.md) - Every available option documented
- **Using Attributes in Forms**: [04-forms-basics.md](04-forms-basics.md) - How forms read/write attributes
- **Using Attributes in Reports**: [08-reports-basics.md](08-reports-basics.md) - How reports query attributes
- **Server Setup**: [10-server-setup.md](10-server-setup.md) - Configuring resolvers and middleware
- **Database Adapters**: [11-database-adapters.md](11-database-adapters.md) - How adapters use attributes

## Source References

### Primary Source Files

- **Macro Definition**: `com.fulcrologic.rad.attributes/defattr` (attributes.cljc:54-92)
- **Attribute Creation**: `com.fulcrologic.rad.attributes/new-attribute` (attributes.cljc:29-51)
- **Options Documentation**: `com.fulcrologic.rad.attributes-options` (attributes-options.cljc:1-373)
- **Type Predicates**: `to-many?`, `to-one?` (attributes.cljc:94-104)
- **Utilities**: `attributes->eql`, `attribute-map`, `make-attribute-validator` (attributes.cljc:116-129)

### DevelopersGuide.adoc Sections

- **Attribute-Centric Concept**: Lines 168-240
- **Attributes Chapter**: Lines 331-603
- **Model Namespaces**: Lines 366-393
- **Identity Attributes**: Lines 394-413
- **Data Types**: Lines 414-420
- **Scalar Attributes**: Lines 421-436
- **Attribute Clusters**: Lines 437-472
- **Referential Attributes**: Lines 473-494
- **All Attributes**: Lines 546-588
- **Hot Code Reload**: Lines 589-603

### Key Specs

- `::attr/attribute` spec (attributes.cljc:23-24): Requires `::type` and `::qualified-key`
- `::attr/qualified-key` (attributes.cljc:20): Must be a qualified keyword
- `::attr/type` (attributes.cljc:21): Must be a keyword
