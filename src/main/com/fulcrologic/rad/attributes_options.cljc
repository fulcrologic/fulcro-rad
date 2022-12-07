(ns com.fulcrologic.rad.attributes-options
  "symbolic names for attribute options, so you can use these instead of keywords so that doc strings work."
  (:refer-clojure :exclude [type]))

(def qualified-key
  "A Keyword. This is automatically added by `defattr` and holds the keyword name of the attribute."
  :com.fulcrologic.rad.attributes/qualified-key)

(def type
  "A Keyword. This is automatically added by `defattr` and holds the data type name of the attribute."
  :com.fulcrologic.rad.attributes/type)

(def identity?
  "Boolean. Indicates that the attribute is used to identify rows/entities/documents in a data store.

  Database adapters use this to figure out what the IDs are, and forms/reports use them to understand how to uniquely
  access/address data."
  :com.fulcrologic.rad.attributes/identity?)

(def identities
  "OPTIONAL/REQUIRED. Database adapters usually require this option for persisted attributes.

  A set of qualified keys of attributes that serve as an identity for an entity/doc/row. This is how a particular
  attribute declares where it \"lives\" in a persistent data model, and is used by database adapters to generate
  resolvers that can find this attribute.

  Generally used with a database adapter setting to get the database to generate proper resolvers, and is almost
  always used in tandem with `::attr/schema`.

  If this attribute will live as a column on multiple SQL tables, as
  a fact on many Datomic entities, etc., then you should list all of those identities under this key."
  :com.fulcrologic.rad.attributes/identities)

(def enumerated-values
  "REQUIRED For data type :enum. A `set` of keywords that define the complete list of allowed values in an
   enumerated attribute. See `enumerated-labels`."
  :com.fulcrologic.rad.attributes/enumerated-values)

(def enumerated-labels
  "RECOMMENDED For data type :enum. A map from enumeration keyword to string (or a `(fn [] string?)` that defines
   the label that should be used for the given enumeration (for example in dropdowns). See `enumerated-values`.

   Labels default to a capitalized version of their keyword name."
  :com.fulcrologic.rad.attributes/enumerated-labels)

(def schema
  "OPTIONAL. A keyword.

   Abstractly names a schema on which this attribute lives. Schemas are just names you make up that allow you to
   organize the physical location of attributes in databases. This option is typically used with some database-specific
   adapter option(s) to fully specify the details of storage."
  :com.fulcrologic.rad.attributes/schema)

(def required?
  "OPTIONAL. Boolean. Defaults to false.

  Indicates that this attribute should always be present when it is placed on an entity/row/document. Database adapters
  may or may not be able to enforce this, so consider this option a hint to the db and UI layer. (i.e. You may need to
  write and install additional save middleware to enforce this constraint, if you need that level of validation)."
  :com.fulcrologic.rad.attributes/required?)

;; FIXME: Signature should be `(fn [value form k] boolean?)` so attributes can use
;; form context (e.g. type of tax) to determine how to check.
(def valid?
  "OPTIONAL. A `(fn [value props qualified-key] boolean?)`.

   IMPORTANT: This is ONLY used in the UI when you create a validator using `attr/make-attribute-validator`,
   AND you set that as the form validator with the `fo/validator` option.

   You can combined the two by writing a custom validator like so:

   ```
   (def my-validator
     (let [all-attributes-validator (attr/make-attribute-validator model/all-attributes)]
       (fs/make-validator (fn [form field]
                           (case field
                             ;; override how :some/field is checked
                             :some/field (boolean-predicate form field)
                             ;; Default is to use the ones defined on the RAD model
                             (= :valid (all-attributes-validator form field)))))))
   ```

   DB adapters and other utilities *may* use it, but that is up to the author of that code."
  :com.fulcrologic.rad.attributes/valid?)


(def target
  "REQUIRED for `:ref` attributes (unless you specify `ao/targets`). A qualified keyword of an `identity? true` attribute that identifies the entities/rows/docs
   to which this attribute refers.

   If this attribute is a persisted edge (complex references can be resolved by resolvers and need not be reified in a database)
   then your database adapter will likely require other details so it can properly generate resolvers and save functionality."
  :com.fulcrologic.rad.attributes/target)

(def targets
  "ALTERNATIVE to `ao/target` for `:ref` attributes.

   A SET of qualified keyword of an `identity? true` attribute that identifies the
   entities/rows/docs to which this attribute can refer.

   If this attribute is a persisted edge (complex references can be resolved by resolvers and need not be reified in a database)
   then your database adapter may require other details so it can properly generate resolvers and save functionality."
  :com.fulcrologic.rad.attributes/targets)

(def cardinality
  "OPTIONAL. Default `:one`. Can also be `:many`.

   This option indicates that that this attribute either has a single value or a homogeneous set of values. It is an
   indication to reports/forms, and an indication to the storage layer about how the attribute will need to be
   stored.

   WARNING: Not all database adapters support `:many` on non-reference types. See your database adapter for details."
  :com.fulcrologic.rad.attributes/cardinality)

(def pc-output
  "ALIAS to :com.wsscode.pathom.connect/output.

  Defines the expected output of an attribute that generates its own
  data. Does nothing by itself, must be used with :com.wsscode.pathom.connect/resolve, and optionally
  :com.wsscode.pathom.connect/input.

  If you are resolving a graph edge, then the attribute must be a `:ref` type and can include a `target`
  that indicates what kind of entities/rows/docs this attribute resolves to.

  ```
  (defattr all-accounts :account/all-accounts :ref
    {ao/target     :account/id
     ao/pc-output  [{:account/all-accounts [:account/id]}]
     ao/pc-resolve (fn [{:keys [query-params] :as env} _]
                    #?(:clj
                       {:account/all-accounts (queries/get-all-accounts env query-params)}))})
  ```

  If you are resolving a scalar, then just specify the correct type.

  Remember that automatic resolver generation in database adapters will be able to resolve any other desired attributes
  of an entity/row/document based on the ID of it. Therefore a ref attribute like this will typically ONLY return the
  IDs of the items (though it may choose to return more if that case is judged to always be more efficient).

  NOTE: Any pathom keys you'd normally put in a pathom resolver options
  map can be included in an attribute that is used to generate a Pathom resolver.
  "
  :com.wsscode.pathom.connect/output)

(def pc-resolve
  "ALIAS to :com.wsscode.pathom.connect/resolve. A `(fn [env input])` that can resolve the `pc-output` of
  an attribute.

  `env` is your Pathom parser's current parsing environment, and will contain things like your active database
  connection, a reference to the parser itself, query parameters, etc. This environment is completely open,
  and is set up when you create the parser. Database adapters will document what they place here.

  Must be used with :com.wsscode.pathom.connect/output, and optionally :com.wsscode.pathom.connect/input.

  See `pc-output`. Also note: Any pathom keys you'd normally put in a pathom resolver options
   map can be included in an attribute that is used to generate a Pathom resolver.
  "
  :com.wsscode.pathom.connect/resolve)

(def pc-input
  "ALIAS to :com.wsscode.pathom.connect/input. A set of qualified keys that are required to be present in the
   `pc-resolve`'s `input` parameter for it to be able to work.

   You can use this to \"connect the dots\" of a data graph that are not normally connected in the database
   (or even your data domain) directly.

   For example, say you can figure out a user's GitHub repositories if you know their email address (and your data
   model already knows how to get to an email address from an account ID). You can specify something like this:

   ```
  (defattr repositories :account.github/repositories :ref
    {ao/pc-output  [{:account.github/repositories [:github.repository/id]}]
     ao/pc-input   #{:account/email}
     ao/pc-resolve (fn [{:keys [query-params] :as env} {:account/keys [email]}]
                      ...code that looks up repos via email ...)})
   ```

   Now, any time a query runs in a context where it can get from where it currently \"is\" in the data graph to
   an account ID, it can use the account-id resolver (generated by the db adapter) to find the `:account/email`, which
   then in turn can be passed to this resolver to get repository IDs. We're assuming here that there is another
   resolver that can get from repository IDs to details.

   A completely virtual resolution like this can also simply be generated with Pathom's `defresolver`, but defining them
   in attributes means you can add additional info to them that is useful to other parts of RAD like form rendering.

   See `pc-resolve` and `pc-output`. Also note: Any pathom keys you'd normally put in a pathom resolver options
   map can be included in an attribute that is used to generate a Pathom resolver.
  "
  :com.wsscode.pathom.connect/input)

(def pc-transform
  "ALIAS to :com.wsscode.pathom.connect/transform.
   See the pathom transform docs: https://blog.wsscode.com/pathom/#connect-transform

   Allows one to specify a function that receives the full resolver/mutation map and returns the final version.

   Generally used to wrap the resolver/mutation function with some generic operation to augment its data or operations.
  "
  :com.wsscode.pathom.connect/transform)

(def pathom3-output
  "ALIAS to :com.wsscode.pathom3.connect.operation/output.

  Defines the expected output of an attribute that generates its own data. Does nothing by itself,
  must be used with `:com.wsscode.pathom3.connect.operation/output`, and optionally
  `:com.wsscode.pathom3.connect.operation/input`.

  If `pathom3-output` is left unspecified, `pathom3-resolve` will generate the following:
  `[(::attr/qualified-key attr)]`

  If you are resolving a graph edge, then the attribute must be a `:ref` type and can include a `target`
  that indicates what kind of entities/rows/docs this attribute resolves to.

  ```
  (defattr all-accounts :account/all-accounts :ref
    {ao/target          :account/id
     ao/pathom3-output  [{:account/all-accounts [:account/id]}]
     ao/pathom3-resolve (fn [{:keys [query-params] :as env} _]
                         #?(:clj
                           {:account/all-accounts (queries/get-all-accounts env query-params)}))})
  ```

  If you are resolving a scalar, then just specify the correct type.

  The output will be post-processed by `com.fulcrologic.rad.authorization/redact`, which takes its cue from:
  `:com.fulcrologic.rad.authorization/permissions`:

  ```
  (defattr secret-attr :account/secret :text
    {::auth/permissions (fn [_] #{}) ; No permissions granted
     ::pco/resolve      (fn [_env _input] {:account/secret \"very secret\"})})
  ```

  `:account/secret` will then output as `{:account/secret ::auth/REDACTED}`.

  Remember that automatic resolver generation in database adapters will be able to resolve any other desired attributes
  of an entity/row/document based on the ID of it. Therefore a ref attribute like this will typically ONLY return the
  IDs of the items (though it may choose to return more if that case is judged to always be more efficient).

  NOTE: Any keys from the `:com.wsscode.pathom3.connect.operation` namespace that you normally put into a `pco/defresolver`
  can be included in an attribute that is used to generate a Pathom3 resolver.

  Also see https://pathom3.wsscode.com/docs/resolvers"
  :com.wsscode.pathom3.connect.operation/output)

(def pathom3-resolve
  "ALIAS to :com.wsscode.pathom3.connect.operation/resolve. A `(fn [env input])` that can resolve the `pathom3-output` of
  an attribute.

  `env` is your Pathom3 processor's current environment, and will contain things like your active database
  connection, processing plugins, query parameters, etc. This environment is completely open,
  and is set up when you create the parser. Database adapters will document what they place here.

  May be used with `:com.wsscode.pathom3.connect.operation/output`, and `::com.wsscode.pathom3.connect.operation/input`.

  The output will be post-processed by `com.fulcrologic.rad.authorization/redact`. See `pathom3-output`.

  Also note: Any keys from the `:com.wsscode.pathom3.connect.operation` namespace that you normally
  put into a `pco/defresolver` can be included in an attribute definition, and will be used for Pathom3 resolver generation.

  Also see https://pathom3.wsscode.com/docs/resolvers"
  :com.wsscode.pathom3.connect.operation/resolve)

(def pathom3-input
  "ALIAS to :com.wsscode.pathom3.connect.operation/input. A vector of qualified keys that are required to be present in the
   `pathom3-resolve`'s `input` parameter for it to be able to work.

   You can use this to \"connect the dots\" of a data graph that are not normally connected in the database
   (or even your data domain) directly.

   For example, say you can figure out a user's GitHub repositories if you know their email address (and your data
   model already knows how to get to an email address from an account ID). You can specify something like this:

   ```
  (defattr repositories :account.github/repositories :ref
    {ao/pathom3-output  [{:account.github/repositories [:github.repository/id]}]
     ao/pathom3-input   [:account/email]
     ao/pathom3-resolve (fn [{:keys [query-params] :as env} {:account/keys [email]}]
                          ...code that looks up repos via email ...)})
   ```

   Now, any time a query runs in a context where it can get from where it currently \"is\" in the data graph to
   an account ID, it can use the account-id resolver (generated by the db adapter) to find the `:account/email`, which
   then in turn can be passed to this resolver to get repository IDs. We're assuming here that there is another
   resolver that can get from repository IDs to details.

   A completely virtual resolution like this can also simply be generated with Pathom3's `pco/defresolver`, but defining
   them in attributes means you can add additional info to them that is useful to other parts of RAD like form rendering.

   See `pathom3-resolve` and `pathom3-output`. Also note: Any keys from the `:com.wsscode.pathom3.connect.operation`
   namespace that you normally put into a `pco/defresolver` can be included in an attribute that is used to generate a
   Pathom3 resolver.

   Also see https://pathom3.wsscode.com/docs/resolvers"
  :com.wsscode.pathom3.connect.operation/input)

(def pathom3-transform
  "ALIAS to :com.wsscode.pathom3.connect.operation/transform.
   See the pathom3 transform docs: https://pathom3.wsscode.com/docs/resolvers/#resolver-transform

   Allows one to specify a function that receives the full resolver/mutation map and allow it to
   transform the map before the resolver's instantiation.

   Generally used to wrap the resolver/mutation function with some generic operation to augment its data or operations.
  "
  :com.wsscode.pathom3.connect.operation/transform)

(def read-only?
  "Boolean or `(fn [form-instance attribute] boolean?)`. If true it indicates to the form and db layer that writes
   of this value should not be allowed.  Enforcement is an optional feature. See you database adapter and rendering
   plugin for details."
  :com.fulcrologic.rad.attributes/read-only?)

(def style
  "A keyword or `(fn [context] keyword)`, where context will typically be a component instance that is attempting
   to apply the style.

   Indicates the general style for formatting this particular attribute in forms and reports. Forms
   and reports include additional more fine-grained options for customizing formatting, but this setting
   can be used as a hint to all plugins and libraries as to how you'd like the value to be styled.

   Examples that might be defined include `:USD`, `:password`, `:currency`, etc. Support for this attribute
   will depend on the specific RAD artifact, and may have no effect at all."
  :com.fulcrologic.rad.attributes/style)

(def field-style-config
  "A map of options that are used by the rendering plugin to augment the style of a rendered input.
  Such configuration options are really up to the render plugin, but could include things like `:input/props` as
  additional DOM k/v pairs to put on the input."
  :com.fulcrologic.rad.attributes/field-style-config)

(def computed-options
  "A vector of {:text/:value} maps that indicate the options available for an attribute's value,
  which may be dependent on other attributes of the entity (e.g. to populate a cascading dropdown). This can also be a
  (fn [env] [{:text ... :value ...} ...]), where `env` is defined by the context of usage (for example in a form
  this will be the form-env which contains the master form and current form instance, allowing you to examine the
  entire nested form).

  Various plugins *may* support this option in various ways, so see the documentation of your
  UI/database plugin for more information. You must ensure that `:text` is always a string, and
  that your `:value` is a legal value of the attribute.

  Generally this option will do nothing unless a renderer supports it directly.

  For example: The Semantic UI Rendering plugin supports this option on strings and other types when you set
  `ao/style` to :picker."
  :com.fulcrologic.rad.attributes/computed-options)
