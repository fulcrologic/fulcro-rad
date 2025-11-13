# Client Setup

## Overview

RAD client setup initializes a Fulcro application with RAD-specific features: attribute registry, UI rendering controls,
HTML5 routing, and date/time timezone configuration. The setup includes creating the app instance, installing UI
controls (form/report renderers), and mounting the root component.

## Minimum Requirements

From DevelopersGuide.adoc:826-828:
> "Fulcro RAD can be used with any Fulcro application. The only global configuration that is required is to initialize
> the attribute registry, but the more features you use, the more you'll want to configure. RAD applications that use
> HTML5 routing and UI generation, for example, will also need to configure those."

**Essential Steps**:

1. Create Fulcro app with `rad-app/fulcro-rad-app`
2. Install UI controls for auto-rendering
3. Set default timezone (for date/time fields)
4. Install routing (optional, for HTML5 history)
5. Mount root component

## Complete Client Setup

From DevelopersGuide.adoc:830-867:

```clojure
(ns com.example.client
  (:require
    [com.example.ui :refer [Root]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad.application :as rad-app]
    [com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls :as sui]
    [com.fulcrologic.fulcro.algorithms.timbre-support :refer [console-appender prefix-output-fn]]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [com.fulcrologic.rad.routing.html5-history :refer [html5-history]]
    [com.fulcrologic.rad.routing.history :as history]))

(defonce app
  (rad-app/fulcro-rad-app
    {:client-did-mount (fn [app]
                         ;; Adds improved logging support to js console
                         (log/merge-config! {:output-fn prefix-output-fn
                                             :appenders {:console (console-appender)}}))}))

(defn refresh []
  ;; Hot code reload of installed controls
  (log/info "Reinstalling controls")
  (rad-app/install-ui-controls! app sui/all-controls)
  (app/mount! app Root "app"))

(defn init []
  (log/info "Starting App")
  ;; Set default timezone for date/time support
  (datetime/set-timezone! "America/Los_Angeles")
  ;; Optional HTML5 history support
  (history/install-route-history! app (html5-history))
  ;; Install UI plugin that can auto-render forms/reports
  (rad-app/install-ui-controls! app sui/all-controls)
  (app/mount! app Root "app"))
```

## Core Functions

### rad-app/fulcro-rad-app

**Purpose**: Creates a Fulcro app instance with RAD-specific defaults.

**Signature**: `(rad-app/fulcro-rad-app options)`

**Options**: Same as `app/fulcro-app`, including:

- `:client-did-mount` - Callback after first render
- `:remote` - Customize HTTP remote (defaults to `/api`)
- `:props-middleware` - App-level props processing

**What It Does**:

- Initializes Fulcro app
- Sets up RAD attribute registry
- Configures default remotes
- Returns app instance

### rad-app/install-ui-controls!

From DevelopersGuide.adoc:855, 865:

**Purpose**: Installs rendering controls for auto-rendering forms and reports.

**Signature**: `(rad-app/install-ui-controls! app controls-map)`

**Parameters**:

- `app` - The Fulcro app instance
- `controls-map` - Map of control definitions from rendering plugin

**Example**:

```clojure
(rad-app/install-ui-controls! app sui/all-controls)
```

**Rendering Plugins**:

- `com.fulcrologic.rad.rendering.semantic-ui` - Semantic UI controls
- Custom controls - Define your own control map

**What Controls Provide**:

- Form field renderers (text inputs, dropdowns, date pickers)
- Report layouts (tables, lists, grids)
- Form/report action buttons
- Layout styles

### datetime/set-timezone!

From DevelopersGuide.adoc:860-861:

**Purpose**: Sets the default timezone for date/time field rendering and parsing.

**Signature**: `(datetime/set-timezone! tz-string)`

**Example**:

```clojure
(datetime/set-timezone! "America/Los_Angeles")
(datetime/set-timezone! "UTC")
(datetime/set-timezone! "Europe/London")
```

Uses IANA timezone database identifiers.

### history/install-route-history!

From DevelopersGuide.adoc:862-863:

**Purpose**: Installs HTML5 history routing (optional).

**Signature**: `(history/install-route-history! app history-impl)`

**Example**:

```clojure
(history/install-route-history! app (html5-history))
```

**Enables**:

- Browser back/forward buttons
- Bookmarkable URLs
- Direct navigation via URL bar

**Without History**: Still works, but routing is managed via component state only (no URL changes).

### app/mount!

**Purpose**: Renders the root component into a DOM element.

**Signature**: `(app/mount! app RootComponent element-id)`

**Example**:

```clojure
(app/mount! app Root "app")
```

Mounts `Root` into `<div id="app"></div>`.

## Complete Client Structure

From DevelopersGuide.adoc:1130-1181:

A minimal RAD client needs:

From DevelopersGuide.adoc:1133-1138:
> "The bare minimal client will have:
>
> * A Root UI component
> * (optional) Some kind of \"landing\" page (default route)
> * One or more forms/reports.
> * The Client initialization (shown earlier)."

### Root Component

```clojure
(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom :refer [div]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :refer [defrouter]]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.form :as form]))

(form/defsc-form AccountForm [this props]
  {fo/id           acct/id
   fo/attributes   [acct/name]
   fo/route-prefix "account"})

(defsc LandingPage [this props]
  {:query         ['*]
   :ident         (fn [] [:component/id ::LandingPage])
   :initial-state {}
   :route-segment ["landing-page"]}
  (div
    (dom/button {:onClick (fn [] (form/create! this AccountForm))}
      "Create a New Account")))

(defrouter MainRouter [this props]
  {:router-targets [LandingPage AccountForm]})

(def ui-main-router (comp/factory MainRouter))

(defsc Root [this {:keys [router]}]
  {:query         [{:router (comp/get-query MainRouter)}]
   :initial-state {:router {}}}
  (div :.ui.container.segment
    (ui-main-router router)))
```

From DevelopersGuide.adoc:1180-1181:
> "The landing page in this example includes a sample button to create a new account, but of course you'll also need to
> add some seed data to your database, wrap things with some authorization, etc."

## Common Patterns

### Pattern 1: Development Hot Reload

```clojure
(defn ^:after-load refresh
  "Called by shadow-cljs after hot code reload"
  []
  (log/info "Hot reload - reinstalling controls")
  (rad-app/install-ui-controls! app sui/all-controls)
  (app/mount! app Root "app"))
```

### Pattern 2: Multiple Remotes

```clojure
(defonce app
  (rad-app/fulcro-rad-app
    {:remotes {:remote     (http/fulcro-http-remote {:url "/api"})
               :analytics  (http/fulcro-http-remote {:url "/analytics"})}}))
```

### Pattern 3: Custom Rendering Plugin

```clojure
(ns com.example.controls
  (:require
    [com.fulcrologic.rad.rendering.semantic-ui.semantic-ui-controls :as sui]))

(def my-controls
  (-> sui/all-controls
    (assoc :text-input my-custom-text-input)
    (assoc-in [:report-layout :my-layout] my-custom-report-layout)))

(defn init []
  (rad-app/install-ui-controls! app my-controls)
  (app/mount! app Root "app"))
```

### Pattern 4: Authorization Integration

```clojure
(ns com.example.ui
  (:require
    [com.fulcrologic.rad.authorization :as auth]))

(defsc Root [this {::auth/keys [authorization]
                   :keys       [router]}]
  {:query         [{::auth/authorization (comp/get-query auth/Authorization)}
                   {:router (comp/get-query MainRouter)}]
   :initial-state {::auth/authorization {}
                   :router               {}}}
  (if (auth/authorized? authorization)
    (div (ui-main-router router))
    (div "Please log in")))
```

### Pattern 5: Conditional Features

```clojure
(defn init []
  (log/info "Starting App")
  (datetime/set-timezone! "UTC")

  ;; Only install history in production
  (when (= js/goog.DEBUG false)
    (history/install-route-history! app (html5-history)))

  (rad-app/install-ui-controls! app sui/all-controls)
  (app/mount! app Root "app"))
```

## Important Notes

### 1. App Must Be Created with rad-app/fulcro-rad-app

From DevelopersGuide.adoc:846:

Use `rad-app/fulcro-rad-app`, NOT `app/fulcro-app`. The RAD version sets up the attribute registry and RAD-specific
defaults.

### 2. Controls Must Be Installed Before Mounting

```clojure
;; Correct order:
(rad-app/install-ui-controls! app sui/all-controls)
(app/mount! app Root "app")

;; Wrong: mount before controls
(app/mount! app Root "app")
(rad-app/install-ui-controls! app sui/all-controls) ; Too late!
```

### 3. Timezone is Global

`datetime/set-timezone!` affects all date/time rendering in the app. Set it once at startup.

### 4. History is Optional

HTML5 history is not required. Without it, routing still works via component state, but:

- No browser back/forward
- No bookmarkable URLs
- No URL bar updates

### 5. Root Component Query Must Include Router

From DevelopersGuide.adoc:1174:

```clojure
{:query [{:router (comp/get-query MainRouter)}]}
```

The router must be in the root query for routing to work.

### 6. defonce for App Instance

From DevelopersGuide.adoc:846:

```clojure
(defonce app ...)
```

Use `defonce` to prevent recreating the app on hot reload. This preserves app state during development.

### 7. Rendering Plugins Are Required for Auto-Rendering

Without installing controls, forms and reports won't auto-render. You'll need to manually render everything or install a
rendering plugin.

## Additional Configuration

From DevelopersGuide.adoc:869-870:
> "Additional RAD plugins and templates will include additional features, and you should see the Fulcro and Ring
> documentation for setting up customizations to things like sessions, cookies, security, CSRF, etc."

**Common Additions**:

- Authentication/authorization
- CSRF tokens
- WebSockets (for real-time features)
- Service workers (for offline support)
- Analytics integration
- Error tracking (e.g., Sentry)

## Startup Sequence

1. **Define app** (`defonce app (rad-app/fulcro-rad-app {...})`)
2. **Init function called** (typically by shadow-cljs or webpack)
3. **Set timezone** (`datetime/set-timezone!`)
4. **Install history** (`history/install-route-history!`)
5. **Install controls** (`rad-app/install-ui-controls!`)
6. **Mount root** (`app/mount!`)
7. **Client-did-mount callback** (if defined)

## Related Topics

- **Server Setup** (10-server-setup.md): Configuring the `/api` endpoint that the client talks to
- **Forms Basics** (04-forms-basics.md): Using `form/create!`, `form/edit!` from UI
- **Reports Basics** (08-reports-basics.md): Routing to report components
- **Type Support** (15-type-support.md): Understanding date/time handling and timezones

## Source References

- DevelopersGuide.adoc:824-870 (Client Setup section)
- DevelopersGuide.adoc:1130-1181 (Complete Client example)
- DevelopersGuide.adoc:846-850 (App creation)
- DevelopersGuide.adoc:858-866 (Init function)
- DevelopersGuide.adoc:852-856 (Refresh function for hot reload)
- DevelopersGuide.adoc:1153-1177 (Root component structure)
