# Attribute Options Reference

## Overview

RAD attributes are open maps that accept any namespaced keys. The `com.fulcrologic.rad.attributes-options` namespace
defines the standard options that RAD core recognizes. Database adapters, form plugins, and report plugins define
additional options in their own namespaces. This document catalogs all core attribute options with their types,
behaviors, and usage examples.

**Usage Pattern**:

```clojure
(ns com.example.model
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr name :account/name :string
  {ao/required? true        ; <-- Using options namespace
   ao/label "Account Name"
   ao/identities #{:account/id}})
```

## Core Options

### qualified-key

**Type**: Keyword (qualified)
**Auto-added**: Yes (by `defattr`)
**Namespace**: `:com.fulcrologic.rad.attributes/qualified-key`

From attributes-options.cljc:5-7:
> "A Keyword. This is automatically added by `defattr` and holds the keyword name of the attribute."

**Example**:

```clojure
(defattr id :account/id :uuid {...})
;; Expands to include: ::attr/qualified-key :account/id
```

**Usage**: Rarely used directly. Available for introspection and metaprogramming.

---

### type

**Type**: Keyword
**Auto-added**: Yes (by `defattr`)
**Namespace**: `:com.fulcrologic.rad.attributes/type`

From attributes-options.cljc:9-11:
> "A Keyword. This is automatically added by `defattr` and holds the data type name of the attribute."

**Valid Values**: `:string`, `:uuid`, `:int`, `:long`, `:decimal`, `:instant`, `:boolean`, `:keyword`, `:symbol`,
`:enum`, `:ref`, or custom types defined by plugins.

**Example**:

```clojure
(defattr balance :account/balance :decimal {...})
;; Expands to include: ::attr/type :decimal
```

---

### identity?

**Type**: Boolean
**Required**: For primary/natural keys
**Namespace**: `:com.fulcrologic.rad.attributes/identity?`

From attributes-options.cljc:13-18:
> "Boolean. Indicates that the attribute is used to identify rows/entities/documents in a data store.
>
> Database adapters use this to figure out what the IDs are, and forms/reports use them to understand how to uniquely
> access/address data."

**Example**:

```clojure
(defattr id :account/id :uuid
  {ao/identity? true})
```

**When to Use**:

- Primary keys (UUID, auto-increment IDs)
- Natural keys (email, username if unique)
- Composite keys (rare, but possible with multiple identity attributes)

**Impact**:

- Database adapters recognize these as primary keys
- Forms use these for routing and data loading
- Resolvers use these as lookup keys
- Other attributes use these in `ao/identities`

---

### identities

**Type**: Set of qualified keywords
**Required**: For most persisted attributes (by database adapters)
**Namespace**: `:com.fulcrologic.rad.attributes/identities`

From attributes-options.cljc:20-32:
> "A set of qualified keys of attributes that serve as an identity for an entity/doc/row. This is how a particular
> attribute declares where it \"lives\" in a persistent data model, and is used by database adapters to generate resolvers
> that can find this attribute.
>
> Generally used with a database adapter setting to get the database to generate proper resolvers, and is almost always
> used in tandem with `::attr/schema`.
>
> If this attribute will live as a column on multiple SQL tables, as a fact on many Datomic entities, etc., then you
> should list all of those identities under this key."

**Example**:

```clojure
;; Belongs to one entity
(defattr name :account/name :string
  {ao/identities #{:account/id}
   ao/schema :production})

;; Belongs to multiple entities
(defattr created-at :timestamp/created-at :instant
  {ao/identities #{:account/id :invoice/id :item/id}
   ao/schema :production})
```

**Impact**:

- Database adapters add this attribute to the appropriate tables/entities
- Resolvers are generated to find this attribute via those IDs
- Pathom can "connect the dots" through the graph

---

### schema

**Type**: Keyword
**Required**: For persisted attributes (by database adapters)
**Namespace**: `:com.fulcrologic.rad.attributes/schema`

From attributes-options.cljc:46-52:
> "OPTIONAL. A keyword.
>
> Abstractly names a schema on which this attribute lives. Schemas are just names you make up that allow you to organize
> the physical location of attributes in databases. This option is typically used with some database-specific adapter
> option(s) to fully specify the details of storage."

**Example**:

```clojure
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})  ; <-- Schema name

(defattr admin-id :admin/id :uuid
  {ao/identity? true
   ao/schema :admin})       ; <-- Different schema
```

**Common Values**: `:production`, `:admin`, `:audit`, etc. (you choose the names)

**Impact**:

- Attributes with the same schema (and identity) form a logical entity
- Database adapters use this to generate separate tables/collections
- No schema means the attribute is virtual (computed)

---

### required?

**Type**: Boolean
**Default**: `false`
**Namespace**: `:com.fulcrologic.rad.attributes/required?`

From attributes-options.cljc:54-60:
> "OPTIONAL. Boolean. Defaults to false.
>
> Indicates that this attribute should always be present when it is placed on an entity/row/document. Database adapters
> may or may not be able to enforce this, so consider this option a hint to the db and UI layer. (i.e. You may need to
> write and install additional save middleware to enforce this constraint)."

**Example**:

```clojure
(defattr email :account/email :string
  {ao/required? true
   ao/identities #{:account/id}
   ao/schema :production})
```

**Impact**:

- UI validation (marks field as required)
- Form state (field must be complete)
- Database schema generation (may create NOT NULL constraints)
- **Note**: Enforcement varies by adapter

---

### valid?

**Type**: `(fn [value props qualified-key] boolean?)`
**Namespace**: `:com.fulcrologic.rad.attributes/valid?`

From attributes-options.cljc:62-82:
> "OPTIONAL. A `(fn [value props qualified-key] boolean?)`.
>
> IMPORTANT: This is ONLY used in the UI when you create a validator using `attr/make-attribute-validator`, AND you set
> that as the form validator with the `fo/validator` option."

**Example**:

```clojure
(defattr email :account/email :string
  {ao/valid? (fn [value props k]
               (and (string? value)
                    (re-matches #".+@.+\..+" value)))
   ao/identities #{:account/id}
   ao/schema :production})

;; In your model namespace:
(def default-validator (attr/make-attribute-validator all-attributes))

;; In your form:
(defsc-form AccountForm [this props]
  {fo/validator default-validator
   ...})
```

**Parameters**:

- `value`: The current value of the field
- `props`: The full form props (entire entity)
- `qualified-key`: The attribute key (`:account/email`)

**Return**: `true` if valid, `false` if invalid.

**Combining Validators**:

```clojure
(def my-validator
  (let [attr-validator (attr/make-attribute-validator all-attributes)]
    (fs/make-validator
      (fn [form field]
        (case field
          :special/field (custom-check form field)
          (= :valid (attr-validator form field)))))))
```

---

### target

**Type**: Qualified keyword (identity attribute)
**Required**: For `:ref` type (unless using `ao/targets`)
**Namespace**: `:com.fulcrologic.rad.attributes/target`

From attributes-options.cljc:84-90:
> "REQUIRED for `:ref` attributes (unless you specify `ao/targets`). A qualified keyword of an `identity? true`
> attribute that identifies the entities/rows/docs to which this attribute refers."

**Example**:

```clojure
(defattr address :account/address :ref
  {ao/target :address/id  ; <-- Must be an identity attribute
   ao/identities #{:account/id}
   ao/schema :production})
```

**See**: [02-relationships-cardinality.md](02-relationships-cardinality.md) for detailed relationship patterns.

---

### targets

**Type**: Set of qualified keywords (identity attributes)
**Added**: v1.3.10+
**Required**: For polymorphic `:ref` types (alternative to `ao/target`)
**Namespace**: `:com.fulcrologic.rad.attributes/targets`

From attributes-options.cljc:92-100:
> "ALTERNATIVE to `ao/target` for `:ref` attributes.
>
> A SET of qualified keyword of an `identity? true` attribute that identifies the entities/rows/docs to which this
> attribute can refer."

**Example**:

```clojure
(defattr items :order/items :ref
  {ao/targets #{:product/id :service/id}  ; <-- Polymorphic
   ao/cardinality :many
   ao/identities #{:order/id}
   ao/schema :production})
```

**Use Case**: When a reference can point to multiple entity types.

**See**: [02-relationships-cardinality.md](02-relationships-cardinality.md) for polymorphic patterns.

---

### cardinality

**Type**: Keyword (`:one` or `:many`)
**Default**: `:one`
**Namespace**: `:com.fulcrologic.rad.attributes/cardinality`

From attributes-options.cljc:102-110:
> "OPTIONAL. Default `:one`. Can also be `:many`.
>
> This option indicates that this attribute either has a single value or a homogeneous set of values. It is an
> indication to reports/forms, and an indication to the storage layer about how the attribute will need to be stored.
>
> WARNING: Not all database adapters support `:many` on non-reference types. See your database adapter for details."

**Example**:

```clojure
;; To-many reference
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many  ; <-- Multiple addresses
   ao/identities #{:account/id}
   ao/schema :production})

;; Many scalar values (adapter-dependent)
(defattr tags :item/tags :string
  {ao/cardinality :many
   ao/identities #{:item/id}
   ao/schema :production})
```

**Helpers** (attributes.cljc:94-104):

```clojure
(attr/to-many? addresses)  ; => true
(attr/to-one? addresses)   ; => false
```

**See**: [02-relationships-cardinality.md](02-relationships-cardinality.md)

---

### enumerated-values

**Type**: Set of keywords
**Required**: For `:enum` type
**Namespace**: `:com.fulcrologic.rad.attributes/enumerated-values`

From attributes-options.cljc:34-37:
> "REQUIRED For data type :enum. A `set` of keywords that define the complete list of allowed values in an enumerated
> attribute. See `enumerated-labels`."

**Example**:

```clojure
(defattr status :order/status :enum
  {ao/enumerated-values #{:status/pending :status/shipped :status/delivered}
   ao/identities #{:order/id}
   ao/schema :production})
```

**Convention**: Use namespaced keywords for enum values (`:status/pending` not just `:pending`).

---

### enumerated-labels

**Type**: Map from keyword to string, or `(fn [value] string?)`
**Recommended**: For `:enum` type
**Namespace**: `:com.fulcrologic.rad.attributes/enumerated-labels`

From attributes-options.cljc:39-44:
> "RECOMMENDED For data type :enum. A map from enumeration keyword to string (or a `(fn [value] string?)`) that defines
> the label that should be used for the given enumeration (for example in dropdowns). See `enumerated-values`.
>
> Labels default to a capitalized version of their keyword name."

**Example**:

```clojure
(defattr role :account/role :enum
  {ao/enumerated-values #{:role/user :role/admin :role/superadmin}
   ao/enumerated-labels {:role/user       "User"
                         :role/admin      "Administrator"
                         :role/superadmin "Super Administrator"}
   ao/identities #{:account/id}
   ao/schema :production})
```

**Dynamic Labels**:

```clojure
{ao/enumerated-labels (fn [val]
                        (case val
                          :status/pending "⏳ Pending"
                          :status/shipped "📦 Shipped"
                          (str val)))}
```

---

## Display Options

### label

**Type**: String or `(fn [this] string?)`
**Namespace**: `:com.fulcrologic.rad.attributes/label`

From attributes-options.cljc:367-372:
> "Attribute option. A default label for any context. Can be overridden by form/column option of the same name, but this
> provides a global default in cases where the label is the same in all contexts.
>
> Can be a string, or a `(fn [this] string?)`, where `this` depends on the context."

**Example**:

```clojure
(defattr name :account/name :string
  {ao/label "Account Name"
   ao/identities #{:account/id}
   ao/schema :production})

;; Dynamic label
(defattr balance :account/balance :decimal
  {ao/label (fn [form-instance]
              (if (admin? form-instance)
                "Balance (Admin View)"
                "Balance"))
   ao/identities #{:account/id}
   ao/schema :production})
```

**Context**: The parameter varies by usage:

- In forms: Form component instance
- In reports: Report component instance

---

### style

**Type**: Keyword or `(fn [context] keyword)`
**Namespace**: `:com.fulcrologic.rad.attributes/style`

From attributes-options.cljc:319-329:
> "A keyword or `(fn [context] keyword)`, where context will typically be a component instance that is attempting to
> apply the style.
>
> Indicates the general style for formatting this particular attribute in forms and reports. Forms and reports include
> additional more fine-grained options for customizing formatting, but this setting can be used as a hint to all plugins
> and libraries as to how you'd like the value to be styled.
>
> Examples that might be defined include `:USD`, `:password`, `:currency`, etc. Support for this attribute will depend
> on the specific RAD artifact, and may have no effect at all."

**Example**:

```clojure
(defattr password :account/password :string
  {ao/style :password  ; <-- Renders as password input
   ao/identities #{:account/id}
   ao/schema :production})

(defattr amount :invoice/amount :decimal
  {ao/style :USD
   ao/identities #{:invoice/id}
   ao/schema :production})
```

**Plugin-Dependent**: Check your rendering plugin docs for supported styles.

---

### field-style-config

**Type**: Map
**Namespace**: `:com.fulcrologic.rad.attributes/field-style-config`

From attributes-options.cljc:331-335:
> "A map of options that are used by the rendering plugin to augment the style of a rendered input. Such configuration
> options are really up to the render plugin, but could include things like `:input/props` as additional DOM k/v pairs to
> put on the input."

**Example**:

```clojure
(defattr email :account/email :string
  {ao/field-style-config {:input/props {:autoComplete "email"
                                         :placeholder "user@example.com"}}
   ao/identities #{:account/id}
   ao/schema :production})
```

**Plugin-Specific**: Configuration depends on your rendering plugin (Semantic UI, Material UI, etc.).

---

### computed-options

**Type**: Vector of `{:text string :value any}` maps, or `(fn [env] [{:text :value} ...])`
**Namespace**: `:com.fulcrologic.rad.attributes/computed-options`

From attributes-options.cljc:337-352:
> "A vector of {:text/:value} maps that indicate the options available for an attribute's value, which may be dependent
> on other attributes of the entity (e.g. to populate a cascading dropdown). This can also be a (
> fn [env] [{:text ... :value ...} ...]), where `env` is defined by the context of usage (for example in a form this will
> be the form-env which contains the master form and current form instance, allowing you to examine the entire nested
> form).
>
> Generally this option will do nothing unless a renderer supports it directly.
>
> For example: The Semantic UI Rendering plugin supports this option on strings and other types when you set `ao/style`
> to :picker."

**Example**:

```clojure
;; Static options
(defattr size :product/size :string
  {ao/computed-options [{:text "Small" :value "S"}
                        {:text "Medium" :value "M"}
                        {:text "Large" :value "L"}]
   ao/identities #{:product/id}
   ao/schema :production})

;; Dynamic (cascading dropdown)
(defattr city :address/city :string
  {ao/computed-options (fn [{:keys [form-instance]}]
                         (let [state (get-in form-instance [:address/state])]
                           (cities-for-state state)))
   ao/identities #{:address/id}
   ao/schema :production})
```

**Rendering Plugin Required**: Most plugins don't support this automatically. Check docs or use with `ao/style :picker`.

---

## Reference Options

### component?

**Type**: Boolean or `(fn [owner] boolean?)`
**Namespace**: `:com.fulcrologic.rad.attributes/component?`
**Applies To**: `:ref` attributes

From attributes-options.cljc:354-365:
> "Used on `:ref` attributes. An indicator the reference edge points to entities that are *exclusively owned* by the
> parent. A boolean or `(fn [owner] boolean?)`, where owner is the ID key of the entity type (because an attribute can
> belong to multiple via `identities`).
>
> This *could* be used to:
> * Generate schema auto-delete rules in database plugins.
> * Check for dropped edges during save middleware to auto-delete orphans.
>
> Check your plugin documentation (or source) to see if it supports this flag."

**Example**:

```clojure
(defattr addresses :account/addresses :ref
  {ao/target :address/id
   ao/cardinality :many
   ao/component? true  ; <-- Account owns addresses
   ao/identities #{:account/id}
   ao/schema :production})

;; Function form
(defattr metadata :entity/metadata :ref
  {ao/target :metadata/id
   ao/component? (fn [owner-id]
                   ;; Only owned by accounts, not by items
                   (= owner-id :account/id))
   ao/identities #{:account/id :item/id}
   ao/schema :production})
```

**See**: [02-relationships-cardinality.md](02-relationships-cardinality.md) for ownership patterns.

---

### read-only?

**Type**: Boolean or `(fn [form-instance attribute] boolean?)`
**Namespace**: `:com.fulcrologic.rad.attributes/read-only?`

From attributes-options.cljc:313-317:
> "Boolean or `(fn [form-instance attribute] boolean?)`. If true it indicates to the form and db layer that writes of
> this value should not be allowed. Enforcement is an optional feature. See you database adapter and rendering plugin for
> details."

**Example**:

```clojure
(defattr created-at :account/created-at :instant
  {ao/read-only? true  ; <-- Never editable
   ao/identities #{:account/id}
   ao/schema :production})

;; Conditional
(defattr status :order/status :enum
  {ao/read-only? (fn [form-instance attr]
                   ;; Can't edit status after shipping
                   (= :status/shipped (:order/status form-instance)))
   ao/enumerated-values #{:status/pending :status/shipped}
   ao/identities #{:order/id}
   ao/schema :production})
```

**Enforcement**: Varies by plugin. Some rendering plugins disable the input, some hide it.

---

## Pathom 2 Resolver Options

For custom resolvers using Pathom 2. See Pathom docs: https://blog.wsscode.com/pathom/

### pc-output

**Type**: Pathom output spec
**Namespace**: `:com.wsscode.pathom.connect/output`
**Alias**: `ao/pc-output`

From attributes-options.cljc:112-140:
> "ALIAS to :com.wsscode.pathom.connect/output.
>
> Defines the expected output of an attribute that generates its own data. Does nothing by itself, must be used with :
> com.wsscode.pathom.connect/resolve, and optionally :com.wsscode.pathom.connect/input."

**Example**:

```clojure
(defattr all-accounts :account/all-accounts :ref
  {ao/target    :account/id
   ao/pc-output [{:account/all-accounts [:account/id]}]
   ao/pc-resolve (fn [env _]
                   #?(:clj {:account/all-accounts (db/get-all-accounts env)}))})
```

### pc-resolve

**Type**: `(fn [env input] output-map)`
**Namespace**: `:com.wsscode.pathom.connect/resolve`
**Alias**: `ao/pc-resolve`

From attributes-options.cljc:142-155:
> "ALIAS to :com.wsscode.pathom.connect/resolve. A `(fn [env input])` that can resolve the `pc-output` of an attribute.
>
> `env` is your Pathom parser's current parsing environment, and will contain things like your active database
> connection, a reference to the parser itself, query parameters, etc."

**Example**:

```clojure
(defattr full-name :account/full-name :string
  {ao/pc-input #{:account/first-name :account/last-name}
   ao/pc-output [:account/full-name]
   ao/pc-resolve (fn [env {:account/keys [first-name last-name]}]
                   {:account/full-name (str first-name " " last-name)})})
```

### pc-input

**Type**: Set of qualified keywords
**Namespace**: `:com.wsscode.pathom.connect/input`
**Alias**: `ao/pc-input`

From attributes-options.cljc:157-186:
> "ALIAS to :com.wsscode.pathom.connect/input. A set of qualified keys that are required to be present in the
`pc-resolve`'s `input` parameter for it to be able to work.
>
> You can use this to \"connect the dots\" of a data graph that are not normally connected in the database (or even your
> data domain) directly."

**Example**:

```clojure
(defattr repositories :account.github/repositories :ref
  {ao/pc-output [{:account.github/repositories [:github.repository/id]}]
   ao/pc-input  #{:account/email}  ; <-- Requires email to find repos
   ao/pc-resolve (fn [env {:account/keys [email]}]
                   {:account.github/repositories (github-api/get-repos email)})})
```

### pc-transform

**Type**: `(fn [resolver-map] transformed-resolver-map)`
**Namespace**: `:com.wsscode.pathom.connect/transform`
**Alias**: `ao/pc-transform`

From attributes-options.cljc:188-196:
> "ALIAS to :com.wsscode.pathom.connect/transform. See the pathom transform
> docs: https://blog.wsscode.com/pathom/#connect-transform
>
> Allows one to specify a function that receives the full resolver/mutation map and returns the final version. Generally
> used to wrap the resolver/mutation function with some generic operation to augment its data or operations."

**Example**:

```clojure
(defattr secure-data :account/secure-data :ref
  {ao/pc-output [{:account/secure-data [:secure/id]}]
   ao/pc-resolve (fn [env input] ...)
   ao/pc-transform (fn [resolver]
                     (update resolver ::pc/resolve
                             (fn [resolve-fn]
                               (fn [env input]
                                 (when (authorized? env)
                                   (resolve-fn env input))))))})
```

---

## Pathom 3 Resolver Options

For custom resolvers using Pathom 3. See Pathom 3 docs: https://pathom3.wsscode.com/docs/resolvers

### pathom3-output

**Type**: Pathom 3 output spec
**Namespace**: `:com.wsscode.pathom3.connect.operation/output`
**Alias**: `ao/pathom3-output`

From attributes-options.cljc:198-241:
> "ALIAS to :com.wsscode.pathom3.connect.operation/output.
>
> Defines the expected output of an attribute that generates its own data. Does nothing by itself, must be used with
`:com.wsscode.pathom3.connect.operation/resolve`, and optionally `:com.wsscode.pathom3.connect.operation/input`.
>
> If `pathom3-output` is left unspecified, `pathom3-resolve` will generate the following:
`[(::attr/qualified-key attr)]`"

**Example**:

```clojure
(defattr all-accounts :account/all-accounts :ref
  {ao/target          :account/id
   ao/pathom3-output  [{:account/all-accounts [:account/id]}]
   ao/pathom3-resolve (fn [env _]
                        #?(:clj {:account/all-accounts (db/get-all-accounts env)}))})
```

**Authorization** (from attributes-options.cljc:222-231):
> "The output will be post-processed by `com.fulcrologic.rad.authorization/redact`, which takes its cue from:
`:com.fulcrologic.rad.authorization/permissions`"

```clojure
(defattr secret :account/secret :string
  {::auth/permissions (fn [_] #{})  ; No permissions = redacted
   ao/pathom3-resolve (fn [_env _input] {:account/secret "secret value"})})
;; Output: {:account/secret ::auth/REDACTED}
```

### pathom3-resolve

**Type**: `(fn [env input] output-map)`
**Namespace**: `:com.wsscode.pathom3.connect.operation/resolve`
**Alias**: `ao/pathom3-resolve`

From attributes-options.cljc:243-259:
> "ALIAS to :com.wsscode.pathom3.connect.operation/resolve. A `(fn [env input])` that can resolve the `pathom3-output`
> of an attribute.
>
> `env` is your Pathom3 processor's current environment, and will contain things like your active database connection,
> processing plugins, query parameters, etc."

**Example**:

```clojure
(defattr total :invoice/total :decimal
  {ao/pathom3-input  [:invoice/subtotal :invoice/tax]
   ao/pathom3-resolve (fn [env {:invoice/keys [subtotal tax]}]
                        {:invoice/total (+ subtotal tax)})})
```

### pathom3-input

**Type**: Vector of qualified keywords
**Namespace**: `:com.wsscode.pathom3.connect.operation/input`
**Alias**: `ao/pathom3-input`

From attributes-options.cljc:261-292:
> "ALIAS to :com.wsscode.pathom3.connect.operation/input. A vector of qualified keys that are required to be present in
> the `pathom3-resolve`'s `input` parameter for it to be able to work."

**Note**: Pathom 3 uses **vectors** for input, Pathom 2 uses **sets**.

**Example**:

```clojure
(defattr age :person/age :int
  {ao/pathom3-input  [:person/birth-date]
   ao/pathom3-resolve (fn [env {:person/keys [birth-date]}]
                        {:person/age (calculate-age birth-date)})})
```

### pathom3-transform

**Type**: `(fn [resolver-map] transformed-resolver-map)`
**Namespace**: `:com.wsscode.pathom3.connect.operation/transform`
**Alias**: `ao/pathom3-transform`

From attributes-options.cljc:294-303:
> "ALIAS to :com.wsscode.pathom3.connect.operation/transform. See the pathom3 transform
> docs: https://pathom3.wsscode.com/docs/resolvers/#resolver-transform
>
> Allows one to specify a function that receives the full resolver/mutation map and allow it to transform the map before
> the resolver's instantiation."

**Example**:

```clojure
(defattr data :entity/data :ref
  {ao/pathom3-resolve (fn [env input] ...)
   ao/pathom3-transform (fn [resolver]
                          (assoc resolver ::pco/cache? true))})
```

### pathom3-batch?

**Type**: Boolean
**Namespace**: `:com.wsscode.pathom3.connect.operation/batch?`
**Alias**: `ao/pathom3-batch?`

From attributes-options.cljc:305-311:
> "ALIAS to :com.wsscode.pathom3.connect.operation/batch?
> See the pathom3 batch resolver docs: https://pathom3.wsscode.com/docs/resolvers/#batch-resolvers
>
> Indicates to pathom3 that the resolver supports being called with a batch of inputs to work around the N+1 problem."

**Example**:

```clojure
(defattr user-info :user/info :ref
  {ao/pathom3-batch? true
   ao/pathom3-resolve (fn [env inputs]
                        ;; inputs is a collection
                        (let [ids (map :user/id inputs)]
                          (bulk-fetch-users env ids)))})
```

---

## Option Summary Table

| Option               | Type       | Purpose               | Required For               |
|----------------------|------------|-----------------------|----------------------------|
| `qualified-key`      | keyword    | Attribute name        | Auto-added                 |
| `type`               | keyword    | Data type             | Auto-added                 |
| `identity?`          | boolean    | Primary key marker    | Identity attributes        |
| `identities`         | set        | Where attribute lives | Persisted attributes       |
| `schema`             | keyword    | Storage grouping      | Persisted attributes       |
| `required?`          | boolean    | Validation hint       | Optional                   |
| `valid?`             | function   | Custom validator      | Optional                   |
| `target`             | keyword    | Reference target      | `:ref` type                |
| `targets`            | set        | Polymorphic targets   | Polymorphic `:ref`         |
| `cardinality`        | keyword    | :one/:many            | To-many refs               |
| `enumerated-values`  | set        | Enum values           | `:enum` type               |
| `enumerated-labels`  | map/fn     | Enum labels           | `:enum` type (recommended) |
| `label`              | string/fn  | Display label         | Optional                   |
| `style`              | keyword/fn | Format hint           | Optional                   |
| `field-style-config` | map        | Rendering config      | Optional                   |
| `computed-options`   | vector/fn  | Dropdown options      | Optional                   |
| `component?`         | boolean/fn | Ownership flag        | Owned refs                 |
| `read-only?`         | boolean/fn | Edit prevention       | Optional                   |
| `pc-output`          | spec       | Pathom 2 output       | Custom resolvers           |
| `pc-resolve`         | function   | Pathom 2 resolver     | Custom resolvers           |
| `pc-input`           | set        | Pathom 2 input        | Custom resolvers           |
| `pc-transform`       | function   | Pathom 2 transform    | Optional                   |
| `pathom3-output`     | spec       | Pathom 3 output       | Custom resolvers           |
| `pathom3-resolve`    | function   | Pathom 3 resolver     | Custom resolvers           |
| `pathom3-input`      | vector     | Pathom 3 input        | Custom resolvers           |
| `pathom3-transform`  | function   | Pathom 3 transform    | Optional                   |
| `pathom3-batch?`     | boolean    | Batch resolver        | Optional                   |

## Plugin-Specific Options

Database adapters and rendering plugins add their own namespaced options. Common patterns:

**Database Adapter Options**:

```clojure
;; Datomic
:com.fulcrologic.rad.database-adapters.datomic/schema
:com.fulcrologic.rad.database-adapters.datomic/entity-ids

;; SQL
:com.fulcrologic.rad.database-adapters.sql/schema
:com.fulcrologic.rad.database-adapters.sql/column-name
```

**Form Options** (see form-options namespace):

```clojure
;; Defined in form-options.cljc
fo/default-value
fo/field-style
fo/field-options
```

**Report Options** (see report-options namespace):

```clojure
;; Defined in report-options.cljc
ro/column-formatter
ro/column-heading
```

**Check documentation for**:

- Your database adapter
- Your rendering plugin
- Form/report plugins

## Examples by Use Case

### Basic Persisted Scalar

```clojure
(defattr name :account/name :string
  {ao/required? true
   ao/label "Account Name"
   ao/identities #{:account/id}
   ao/schema :production})
```

### Identity Attribute

```clojure
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema :production})
```

### Enum with Labels

```clojure
(defattr status :order/status :enum
  {ao/enumerated-values #{:status/pending :status/shipped :status/delivered}
   ao/enumerated-labels {:status/pending   "Pending"
                         :status/shipped   "Shipped"
                         :status/delivered "Delivered"}
   ao/identities #{:order/id}
   ao/schema :production})
```

### To-Many Owned Relationship

```clojure
(defattr line-items :invoice/line-items :ref
  {ao/target :line-item/id
   ao/cardinality :many
   ao/component? true
   ao/identities #{:invoice/id}
   ao/schema :production})
```

### To-One Referenced Relationship

```clojure
(defattr item :line-item/item :ref
  {ao/target :item/id
   ;; omit ao/component? - not owned
   ao/identities #{:line-item/id}
   ao/schema :production})
```

### Computed Attribute (Virtual)

```clojure
(defattr full-name :person/full-name :string
  {ao/pathom3-input [:person/first-name :person/last-name]
   ao/pathom3-resolve (fn [_ {:person/keys [first-name last-name]}]
                        {:person/full-name (str first-name " " last-name)})})
```

### Multi-Entity Attribute

```clojure
(defattr created-at :timestamp/created-at :instant
  {ao/identities #{:account/id :invoice/id :item/id}
   ao/schema :production
   ao/read-only? true})
```

### Validated Attribute

```clojure
(defattr age :person/age :int
  {ao/required? true
   ao/valid? (fn [value _ _]
               (and (int? value)
                    (>= value 0)
                    (<= value 150)))
   ao/identities #{:person/id}
   ao/schema :production})
```

## Related Topics

- **Attributes Fundamentals**: [01-attributes-data-model.md](01-attributes-data-model.md) - Core concepts and defattr
- **Relationships**: [02-relationships-cardinality.md](02-relationships-cardinality.md) - target, targets, cardinality,
  component?
- **Forms**: [04-forms-basics.md](04-forms-basics.md) - How forms use attribute options
- **Reports**: [08-reports-basics.md](08-reports-basics.md) - How reports use attribute options

## Source References

- **Complete Options**: `com.fulcrologic.rad.attributes-options` (attributes-options.cljc:1-373)
- **Usage in Attributes**: `com.fulcrologic.rad.attributes` (attributes.cljc)
- **DevelopersGuide**: Lines 495-588 (Attribute Types and Details, All Attributes)
