# Server Setup

## Overview

A RAD server provides an EQL API endpoint (typically `/api`) using Pathom for query resolution. The server setup
involves: configuration files, form save/delete middleware, a Pathom parser with RAD plugins, and Ring middleware. RAD
integrates with database adapters and auto-generates resolvers from attributes.

## Core Components

From DevelopersGuide.adoc:606-607:
> "A RAD server must have an EQL API endpoint, typically at `/api`. This is standard Fulcro stuff..."

**Setup Stack**:

1. **Configuration** - EDN files with app settings
2. **Form Middleware** - Save/delete handlers for database adapters
3. **Pathom Parser** - EQL query resolution with RAD plugins
4. **Ring Middleware** - HTTP server with API endpoint

## Configuration Files

From DevelopersGuide.adoc:609-646:

Use Fulcro's EDN-based config system with `mount` for state management.

### Config Component

```clojure
(ns com.example.components.config
  (:require
    [com.fulcrologic.fulcro.server.config :as fulcro-config]
    [com.example.lib.logging :as logging]
    [mount.core :refer [defstate args]]
    [taoensso.timbre :as log]
    [com.example.model :as model]
    [com.fulcrologic.rad.attributes :as attr]))

(defstate config
  "The overrides option in args is for overriding configuration in tests."
  :start (let [{:keys [config overrides]
                :or   {config "config/dev.edn"}} (args)
               loaded-config (merge (fulcro-config/load-config {:config-path config})
                                    overrides)]
           (log/info "Loading config" config)
           ;; Set up Timbre logging, etc.
           (logging/configure-logging! loaded-config)
           loaded-config))
```

### Config Files

From DevelopersGuide.adoc:640-646:

**`config/defaults.edn`** - Base configuration
**`config/dev.edn`** - Development overrides
**`config/prod.edn`** - Production overrides

```clojure
{:my-database {:host "localhost"
               :port 5432}
 :my-config-value 42}
```

Fulcro's config system merges files and supports environment variables. See Fulcro Developer's Guide for details.

## Form Middleware

From DevelopersGuide.adoc:648-693:

Form middleware hooks into RAD's I/O subsystem using a Ring-like pattern. Two middlewares are required: **save** and *
*delete**.

### Save Middleware

From DevelopersGuide.adoc:656-677:

```clojure
(ns com.example.components.save-middleware
  (:require
    [com.fulcrologic.rad.middleware.save-middleware :as r.s.middleware]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.example.components.datomic :refer [datomic-connections]]
    [com.fulcrologic.rad.blob :as blob]
    [com.example.model :as model]))

(def middleware
  (->
    (datomic/wrap-datomic-save)
    (r.s.middleware/wrap-rewrite-values)))
```

**Purpose**: Receives Pathom mutation `env` augmented with `::form/params` and handles database saves.

From DevelopersGuide.adoc:677:
> "This is also the best place to put things like security and schema validation enforcement for save."

**Composition**:

- Database adapter save wrapper (e.g., `wrap-datomic-save`)
- Value rewrite middleware (`wrap-rewrite-values`)
- Custom security/validation middleware (add your own)

### Delete Middleware

From DevelopersGuide.adoc:679-692:

```clojure
(ns com.example.components.delete-middleware
  (:require
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]))

(def middleware
  (datomic/wrap-datomic-delete))
```

**Purpose**: Invoked during entity deletion requests.

From DevelopersGuide.adoc:692:
> "Of course you'll also want to add things to this middleware to check security and such."

## Pathom Parser

From DevelopersGuide.adoc:694-751:

The Pathom parser processes EQL queries. RAD auto-generates resolvers from attributes and database adapters provide
additional resolvers.

### Auto-Resolvers

From DevelopersGuide.adoc:699-714:

Generate resolvers from attributes with `::pc/resolve` keys:

```clojure
(ns com.example.components.auto-resolvers
  (:require
    [com.example.model :refer [all-attributes]]
    [mount.core :refer [defstate]]
    [com.fulcrologic.rad.resolvers :as res]
    [taoensso.timbre :as log]))

(defstate automatic-resolvers
  :start
  (vec (res/generate-resolvers all-attributes)))
```

**What This Does**:

- Scans `all-attributes` for those with `::pc/resolve` functions
- Converts them into Pathom resolvers
- Enables virtual attributes and computed values

### Parser Construction

From DevelopersGuide.adoc:716-747:

```clojure
(ns com.example.components.parser
  (:require
    [com.example.components.auto-resolvers :refer [automatic-resolvers]]
    [com.example.components.config :refer [config]]
    [com.example.components.datomic :refer [datomic-connections]]
    [com.example.components.delete-middleware :as delete]
    [com.example.components.save-middleware :as save]
    [com.example.model :refer [all-attributes]]
    [com.example.model.account :as account]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.database-adapters.datomic :as datomic]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.pathom :as pathom]
    [mount.core :refer [defstate]]))

(defstate parser
  :start
  (pathom/new-parser config
    ;; Plugins
    [(attr/pathom-plugin all-attributes)
     (form/pathom-plugin save/middleware delete/middleware)
     (datomic/pathom-plugin (fn [env] {:production (:main datomic-connections)}))]
    ;; Resolvers
    [automatic-resolvers
     form/resolvers
     account/resolvers
     ; ... your other resolvers ...
     ]))
```

From DevelopersGuide.adoc:749-752:
> "The supplied constructor for pathom parsers is not required, you can use the source to see what it includes by
> default. The RAD parser construction function takes a Fulcro-style server config map, a vector of plugins, and a vector
> of resolvers (the resolvers can be nested sequences)."

### Required Plugins

**`attr/pathom-plugin`** (from DevelopersGuide.adoc:740):
> "required to populate standard things in the parsing env"

Provides RAD attribute data to the Pathom env.

**`form/pathom-plugin`** (from DevelopersGuide.adoc:741):
> "installs form save/delete middleware"

Registers save/delete mutations using the provided middleware.

**Database Adapter Plugin** (from DevelopersGuide.adoc:742):
> "db-specific adapter"

Example: `datomic/pathom-plugin` - provides database connection lookup and generates resolvers from schema.

### Required Resolvers

**`automatic-resolvers`**: Generated from attributes
**`form/resolvers`**: Predefined resolvers for form save/delete
**Custom resolvers**: Your hand-written resolvers

## Ring Middleware

From DevelopersGuide.adoc:754-798:

Wrap the Pathom parser in a Ring-based HTTP server.

### API Handler

From DevelopersGuide.adoc:759-794:

```clojure
(ns com.example.components.ring-middleware
  (:require
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [com.example.components.config :as config]
    [com.example.components.parser :as parser]
    [taoensso.timbre :as log]
    [ring.util.response :as resp]
    [clojure.string :as str]))

(defn wrap-api [handler uri]
  (fn [request]
    (if (= uri (:uri request))
      (server/handle-api-request (:transit-params request)
        (fn [query]
          (parser/parser {:ring/request request}
            query)))
      (handler request))))

(def not-found-handler
  (fn [req]
    {:status 404
     :body   {}}))

(defstate middleware
  :start
  (let [defaults-config (:ring.middleware/defaults-config config/config)]
    (-> not-found-handler
      (wrap-api "/api")
      (server/wrap-transit-params {})
      (server/wrap-transit-response {})
      (wrap-defaults defaults-config))))
```

**Middleware Stack** (bottom to top):

1. `not-found-handler` - Base 404 response
2. `wrap-api` - Routes `/api` to Pathom parser
3. `wrap-transit-params` - Decodes Transit request params
4. `wrap-transit-response` - Encodes Transit response
5. `wrap-defaults` - Ring security defaults (CSRF, etc.)

From DevelopersGuide.adoc:796-798:
> "See the RAD Demo project for the various extra bits you might want to define around your middleware. You will need to
> add middleware to support things like file upload, CSRF protection, etc."

## Common Patterns

### Pattern 1: Multiple Database Connections

```clojure
(defstate datomic-connections
  :start
  {:production (d/connect production-uri)
   :analytics  (d/connect analytics-uri)})

(defstate parser
  :start
  (pathom/new-parser config
    [(datomic/pathom-plugin (fn [env]
                               {:production (:production datomic-connections)
                                :analytics  (:analytics datomic-connections)}))]
    [automatic-resolvers ...]))
```

### Pattern 2: Custom Security Middleware

```clojure
(defn wrap-security-check [handler]
  (fn [env]
    (let [{:keys [ring/request]} env
          user (get-in request [:session :user])]
      (if (authorized? user (get-in env [::form/params]))
        (handler env)
        {:error "Unauthorized"}))))

(def middleware
  (->
    wrap-security-check
    (datomic/wrap-datomic-save)
    (r.s.middleware/wrap-rewrite-values)))
```

### Pattern 3: Logging Middleware

```clojure
(defn wrap-save-logging [handler]
  (fn [env]
    (log/info "Saving:" (get-in env [::form/params ::form/delta]))
    (let [result (handler env)]
      (log/info "Save result:" result)
      result)))
```

### Pattern 4: Environment Augmentation

```clojure
(defstate parser
  :start
  (pathom/new-parser config
    [(attr/pathom-plugin all-attributes)
     (form/pathom-plugin save/middleware delete/middleware)
     (datomic/pathom-plugin db-connection-fn)
     ;; Add custom env data
     {:env {::my-app/feature-flags (load-feature-flags config)}}]
    [automatic-resolvers ...]))
```

## Important Notes

### 1. Parser Construction is Optional

From DevelopersGuide.adoc:749-750:
> "The supplied constructor for pathom parsers is not required, you can use the source to see what it includes by
> default."

You can build the Pathom parser manually if you need more control. `pathom/new-parser` is a convenience.

### 2. Resolver Order Doesn't Matter

Pathom auto-connects resolvers based on inputs/outputs. The order in the resolver vector doesn't affect query
resolution.

### 3. Plugins vs. Resolvers

- **Plugins**: Modify the Pathom environment, add global behavior
- **Resolvers**: Provide data for specific attributes/edges

### 4. Middleware Composition

Form middleware uses Ring-style composition. The **rightmost** middleware runs **first**:

```clojure
(-> handler-fn
  wrap-outer  ; runs last
  wrap-middle ; runs second
  wrap-inner) ; runs first
```

### 5. Config is Stateful

Using `mount`, the config is a stateful component. It's loaded once at startup. To reload config in development, restart
the mount state.

### 6. Security is Your Responsibility

From DevelopersGuide.adoc:677, 692:

RAD provides hooks for security (middleware), but **you must implement** authorization checks. Never trust client data -
validate and authorize in middleware.

### 7. Transit is the Default Format

RAD uses Transit for serialization. The Ring middleware wraps requests/responses with `wrap-transit-params` and
`wrap-transit-response`.

### 8. Parser Receives Ring Request

The parser is called with `{:ring/request request}`, giving resolvers access to sessions, headers, etc.:

```clojure
(defresolver current-user-resolver [{:keys [ring/request]} _]
  {::pc/output [:user/current]}
  {:user/current (get-in request [:session :user])})
```

## Startup Flow

1. **Config loads** (`config` defstate starts)
2. **Database connections** established (`datomic-connections` starts)
3. **Auto-resolvers generated** (`automatic-resolvers` starts)
4. **Parser created** (`parser` starts with plugins + resolvers)
5. **Ring middleware** wraps parser (`middleware` starts)
6. **HTTP server** starts with middleware (e.g., `http-kit`, `jetty`)

## Related Topics

- **Database Adapters** (11-database-adapters.md): How adapters integrate with the parser and provide resolvers
- **Forms Basics** (04-forms-basics.md): Understanding `form/save!` and how it uses server middleware
- **Attributes and Data Model** (01-attributes-data-model.md): Attributes with `::pc/resolve` for virtual data
- **Client Setup** (12-client-setup.md): Configuring the client to talk to the `/api` endpoint

## Source References

- DevelopersGuide.adoc:604-798 (Server Setup section)
- DevelopersGuide.adoc:616-638 (Config component example)
- DevelopersGuide.adoc:656-675 (Save middleware)
- DevelopersGuide.adoc:683-690 (Delete middleware)
- DevelopersGuide.adoc:703-714 (Auto-resolvers)
- DevelopersGuide.adoc:720-747 (Parser construction)
- DevelopersGuide.adoc:759-794 (Ring middleware)
- Fulcro Developer's Guide: Configuration and Server Setup chapters
