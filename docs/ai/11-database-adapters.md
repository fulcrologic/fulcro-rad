# Database Adapters

## Overview

Database adapters are optional RAD plugins that automate database interactions. They can auto-generate schemas, generate
resolvers for the network API, and process form saves. Adapters use Ring-style middleware to hook into RAD's form I/O
system and Pathom plugins to provide runtime state (connections, URLs, etc.).

## Core Features

From DevelopersGuide.adoc:873-887:

Database adapters **MAY** provide:

**Primary Features**:

1. **Schema Generation**: Auto-generate database schema from RAD attributes
2. **Resolver Generation**: Create Pathom resolvers for reading entities by ID
3. **Form Save Processing**: Handle form diffs and persist to database

**Additional Features**:

- Schema validation against existing (legacy) databases
- Database sharding across multiple servers
- Connection pooling
- Development mocking (isolate changes from production)

From DevelopersGuide.adoc:888:
> "NOTE: The documentation for the database adapters will contain the most recent details, and should be preferred over
> this book."

## Available Adapters

From DevelopersGuide.adoc:890-905:

### Fulcro RAD Datomic

Features:

- Datomic schema generation (or validation)
- Multiple database schema support
- Form save automation
- Automatic ID-based resolver generation
- Database sharding

From DevelopersGuide.adoc:900-902:
> "See the README of the adapter for information on dependencies and project setup. You will need to add dependencies
> for the version of Datomic you're using and any storage drivers (e.g. PostgreSQL JDBC driver) for the back-end you
> choose."

### Other Adapters

From DevelopersGuide.adoc:903-906:
> "NOTE: Other database adapters are in progress. There is a mostly-working SQL adapter, and a REDIS adapter is also on
> the way. Adapters are not terribly difficult to write, as the data format of RAD and Fulcro is normalized and
> straightforward."

## Server-Side Resolvers

From DevelopersGuide.adoc:907-917:

RAD's network API uses Pathom resolvers to pull data from databases.

**Minimum Requirements**:

- **Forms**: Resolvers that can fetch entities by ID
- **Reports**: Resolvers that provide unique row identification

From DevelopersGuide.adoc:913-917:
> "DB adapters can often automatically generate many of these resolvers, but legacy applications can simply ensure all
> of the attributes a form might need can be resolved via an ident-based Fulcro query against that form (e.g.
`[{[:account/id id] [:account/name]}]`).
>
> Fulcro and EQL defines the read/write model, and RAD just leverages it. You can use as much or as little RAD
> automation as you want. It is just doing what you would do for Fulcro applications."

**Resolver Types**:

1. **ID-based resolvers**: `{:account/id id} -> {:account/name ...}`
2. **Global resolvers**: `:account/all-accounts -> [{:account/id ...} ...]`
3. **Custom resolvers**: Any business logic or computed values

## Form Middleware

From DevelopersGuide.adoc:919-927:

Adapters provide middleware to handle form save/delete operations.

From DevelopersGuide.adoc:922-923:
> "This allows RAD plugins to be inserted into the processing chain to do things like save form data to a particular
> database. They use a pattern similar to Ring middleware."

### The Parser Environment

From DevelopersGuide.adoc:928-938:

Form save/delete runs in Pathom context. The `env` contains:

**Runtime State** (from Pathom plugins):

- Database connections
- Configuration URLs
- Authentication/session data
- Any other namespaced runtime data

From DevelopersGuide.adoc:930-938:
> "Form save/delete is run in the context of Pathom, meaning that the `env` that is available to any plugin is whatever
> is configured for Pathom itself. *All middleware should leverage this in order to provide runtime information*.
>
> Database plugins should require that you add some kind of plugin to your parser. Mostly what these plugs are doing is
> adding content to the `env` under namespaced keys: database connections, URLs, etc. Whatever is necessary to accomplish
> the real task at runtime will be in `env`.
>
> The save and delete middlware that you install in the parser is the *logic* for accomplishing a save or delete.
>
> The `env` in pathom is the *state* necessary for it to do so."

## Save Middleware

From DevelopersGuide.adoc:940-982:

### Structure

Save middleware receives the Pathom mutation `env` with:

From DevelopersGuide.adoc:943-947:

- **`::form/params`**: Minimal diff of the form being saved
- **`::attr/key->attribute`**: Map from keyword to attribute definition
- **All other Pathom env entries**: Connections, auth, etc.

### Implementation Pattern

From DevelopersGuide.adoc:952-965 (Datomic example):

```clojure
(defn wrap-datomic-save
  "Form save middleware to accomplish Datomic saves."
  ([]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [save-result (save-form! pathom-env params)]
       save-result)))
  ([handler]
   (fn [{::form/keys [params] :as pathom-env}]
     (let [save-result    (save-form! pathom-env params)
           handler-result (handler pathom-env)]
       (deep-merge save-result handler-result)))))
```

**Two Arities**:

1. **No-arg**: Terminal middleware (no handler to call)
2. **One-arg**: Accepts a handler, calls it, merges results

This Ring-style pattern allows middleware composition.

### Form Params Format

From DevelopersGuide.adoc:967-979:

Forms save in a **normalized diff format**:

```clojure
{[:account/id 1] {:account/name    {:before "Joe" :after "Sally"}
                  :account/address {:after [:address/id 2]}}
 [:address/id 2] {:address/street  {:before "" :after "123 Main St"}}}
```

**Structure**:

- **Keys**: Fulcro idents `[id-keyword id-value]` (like Datomic lookup refs)
- **Values**: Diff maps for attributes that changed
    - `:before` - Old value (omitted if new entity)
    - `:after` - New value (omitted if deleted)

From DevelopersGuide.adoc:980-982:
> "Your middleware can *modify* the `env` (so that handlers further up the chain see the effects), side effect (save
> long strings to an alternate store), check security (possibly throwing exceptions or removing things from the params),
> etc.
>
> This simple construct allows an infinite variety of complexity to be added to your saves."

## Delete Middleware

From DevelopersGuide.adoc:984-987:

> "This is very similar to save middleware, but is invoked during a request to delete an entity."

Similar structure to save middleware:

```clojure
(defn wrap-datomic-delete
  "Form delete middleware."
  ([]
   (fn [pathom-env]
     (let [delete-result (delete-entity! pathom-env)]
       delete-result)))
  ([handler]
   (fn [pathom-env]
     (let [delete-result  (delete-entity! pathom-env)
           handler-result (handler pathom-env)]
       (deep-merge delete-result handler-result)))))
```

## Integration Pattern

### 1. Add Adapter Dependency

```clojure
;; deps.edn
{:deps {com.fulcrologic/fulcro-rad-datomic {:mvn/version "1.x.x"}
        com.datomic/datomic-pro            {:mvn/version "..."}}}
```

### 2. Configure Database Connection

```clojure
(ns com.example.components.datomic
  (:require
    [datomic.api :as d]
    [mount.core :refer [defstate]]
    [com.example.components.config :refer [config]]))

(defstate datomic-connections
  :start
  {:production (d/connect (get-in config [:datomic :uri]))})
```

### 3. Add Pathom Plugin

```clojure
(ns com.example.components.parser
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.example.components.datomic :refer [datomic-connections]]))

(defstate parser
  :start
  (pathom/new-parser config
    [(datomic/pathom-plugin
       (fn [env] {:production (:production datomic-connections)}))]
    [automatic-resolvers ...]))
```

**What the Plugin Does**:

- Adds database connection(s) to Pathom `env`
- Auto-generates resolvers from schema
- Provides attribute->database mapping

### 4. Install Save/Delete Middleware

```clojure
(ns com.example.components.save-middleware
  (:require
    [com.fulcrologic.rad.middleware.save-middleware :as r.s.middleware]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]))

(def middleware
  (->
    (datomic/wrap-datomic-save)
    (r.s.middleware/wrap-rewrite-values)))
```

```clojure
(ns com.example.components.delete-middleware
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]))

(def middleware
  (datomic/wrap-datomic-delete))
```

### 5. Mark Attributes with Database Schema

```clojure
(defattr name :account/name :string
  {ao/identities #{:account/id}
   ao/schema     :production  ; <-- Links to database
   ; Datomic-specific options
   :com.fulcrologic.rad.database-adapters.datomic/attribute-schema
   {:db/unique :db.unique/value}})
```

## Common Patterns

### Pattern 1: Multiple Database Schemas

```clojure
(defattr id :account/id :uuid
  {ao/identity? true
   ao/schema    :production})

(defattr audit-id :audit-log/id :uuid
  {ao/identity? true
   ao/schema    :audit})  ; Different schema

;; Parser plugin provides both connections
(datomic/pathom-plugin
  (fn [env]
    {:production (:production datomic-connections)
     :audit      (:audit datomic-connections)}))
```

### Pattern 2: Custom Save Logic

```clojure
(defn wrap-audit-log [handler]
  (fn [{::form/keys [params] :as env}]
    ;; Log save before calling next middleware
    (audit/log-save! env params)
    (handler env)))

(def middleware
  (->
    wrap-audit-log
    (datomic/wrap-datomic-save)
    (r.s.middleware/wrap-rewrite-values)))
```

### Pattern 3: Security Enforcement

```clojure
(defn wrap-security [handler]
  (fn [{::form/keys [params] :keys [ring/request] :as env}]
    (let [user (get-in request [:session :user])]
      (if (authorized-to-save? user params)
        (handler env)
        (throw (ex-info "Unauthorized" {:status 403}))))))
```

### Pattern 4: Field-Level Encryption

```clojure
(defn wrap-encryption [handler]
  (fn [{::form/keys [params] :as env}]
    (let [encrypted-params (encrypt-sensitive-fields params)]
      (handler (assoc env ::form/params encrypted-params)))))
```

## Important Notes

### 1. Adapters Are Optional

From DevelopersGuide.adoc:873:
> "Database adapters are an optional part of the RAD system."

You can use RAD without adapters by writing all resolvers and save logic manually.

### 2. Middleware Composition Order

Ring-style composition runs **rightmost first**:

```clojure
(->
  wrap-outer  ; Runs last
  wrap-middle ; Runs second
  wrap-inner) ; Runs first
```

### 3. Env is Immutable

Middleware receives an immutable `env` map. Use standard Clojure functions to transform it:

```clojure
(fn [env]
  (-> env
    (assoc ::my-ns/processed? true)
    (update ::form/params process-params)
    (handler)))
```

### 4. Adapter-Specific Attribute Options

Adapters define their own namespaced attribute options:

```clojure
{:com.fulcrologic.rad.database-adapters.datomic/attribute-schema
 {:db/unique    :db.unique/value
  :db/index     true
  :db/fulltext  true}}
```

Check adapter documentation for available options.

### 5. Schema Generation vs. Validation

Some adapters can:

- **Generate** schema from attributes (create database tables/schema)
- **Validate** attributes against existing schema (check compatibility)

### 6. Resolver Auto-Generation

Adapters typically generate resolvers for:

- **ID lookups**: `[:account/id uuid] -> {:account/name ...}`
- **Attribute relationships**: Following `ao/target` references
- **Batch resolution**: Optimized N+1 query handling

### 7. Connection Lookup

The plugin function receives `env` and returns a map of schema-keyword -> connection:

```clojure
(datomic/pathom-plugin
  (fn [env]
    {:production (get-connection env :production)
     :analytics  (get-connection env :analytics)}))
```

This allows dynamic connection selection based on request context.

## Writing Custom Adapters

From DevelopersGuide.adoc:905-906:
> "Adapters are not terribly difficult to write, as the data format of RAD and Fulcro is normalized and
> straightforward."

**Minimal Adapter Requirements**:

1. **Pathom plugin**: Add connection(s) to env
2. **Save middleware**: Process `::form/params` diff format
3. **Delete middleware**: Handle entity deletion
4. **(Optional) Resolver generation**: Auto-create ID-based resolvers
5. **(Optional) Schema generation**: Create database schema from attributes

## Related Topics

- **Server Setup** (10-server-setup.md): Installing adapter plugins and middleware in the parser
- **Forms Basics** (04-forms-basics.md): Understanding `form/save!` and the client-side save flow
- **Attributes and Data Model** (01-attributes-data-model.md): Using `ao/schema` to link attributes to databases
- **Form Relationships** (05-form-relationships.md): How adapters handle cascading saves for relationships

## Source References

- DevelopersGuide.adoc:871-987 (Database Adapters section)
- DevelopersGuide.adoc:873-887 (Core features)
- DevelopersGuide.adoc:890-906 (Available adapters)
- DevelopersGuide.adoc:907-917 (Server-side resolvers)
- DevelopersGuide.adoc:919-938 (Form middleware and env)
- DevelopersGuide.adoc:940-982 (Save middleware structure and params format)
- DevelopersGuide.adoc:984-987 (Delete middleware)
- RAD Datomic Adapter README: https://github.com/fulcrologic/fulcro-rad-datomic
