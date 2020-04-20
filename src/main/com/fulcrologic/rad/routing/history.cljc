(ns com.fulcrologic.rad.routing.history
  "Generic history protocol and support.

  In order to use history, you must install an implementation on your Fulcro app at application start-time that
  is compatible with your runtime environment (browser, native mobile, etc.) via `install-route-history!`. Once
  you've done that, then the non-protocol methods in this namespace can be used against the app to update the *history*,
  but they will *not* affect the actual *application route*. Actual routing should always be done via the
  `com.fulcrologic.rad.routing` namespace functions, which will keep track of history if it is installed."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.type-support.cache-a-bools :as cb]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad.authorization :as auth]
    [taoensso.timbre :as log]))

(defprotocol RouteHistory
  "A Route History is mainly a storage device. It records a history stack along with optional additional parameters
   at each history entry. It can be asked what it thinks the current route is, and it can be asked to replace the
   current top of the stack.

   A history implementation *may* be hooked to some external source of events (i.e. browser back/forward buttons, phone
   native navigation). These events (e.g. HTML5 popstate events) are only expected when there is an *external* change
   to the route that your application did not initiate with its own API (not that A tags in HTML with URIs will cause
   these events, since it is the browser, not your app, that is technically initiating the change). Such an implementation
   *must* honor the add/remove calls to hook up a listener to these external events.
   "
  (-push-route! [history route params] "Pushes the given route with params onto the current history stack.")
  (-replace-route! [history route params] "Replaces the top entry in the history stack.")
  (-back! [history]
    "Moves the history back one in the history stack. Calling this will result in a route listener notification about the new route.")
  (-undo! [history new-route params]
    "Attempt to undo the given (last) change to history that was reported to listeners. `new-route` and `params` are the
     parameters that were passed to the listener. This can only be done once,
     and will fail silently if no such notification just happened.")
  (-add-route-listener! [history listener-key f]
    "Add the callback `f` to the list of listeners. That listener will be known as `listener-key`. You should namespace that key to prevent conflicts.")
  (-remove-route-listener! [history listener-key] "Remove the listener named `listener-key`.")
  (-current-route [history]
    "Returns a map of {:route [\"a\" \"b\"] :params {}}. The params are the extra state/params, and the route is purely strings.
    Note that changing the route may be an async operation, so do *not* expect this to be the correct route immediately after
    a call to `-back!`; use a route listener instead. This particular method is useful for checking when the Fulcro
    app's idea of the current route differs from the current route in history (i.e. as a sanity check when Fulcro's state changes)"))

(s/def ::RouteHistory #(satisfies? RouteHistory %))
(s/def ::route (s/coll-of string? :kind vector?))
(s/def ::params map?)

(>defn active-history
  "Returns the active (installed) RouteHistory implementation, or nil if none is installed."
  [app-ish]
  [any? => (? ::RouteHistory)]
  (try
    (some-> app-ish comp/any->app ::app/runtime-atom deref ::history)
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn history-support?
  "Returns true if RAD history support is enabled on the given app (you can also pass a component)."
  [app-ish]
  [any? => boolean?]
  (boolean (active-history app-ish)))

(declare add-route-listener! undo!)

(>defn install-route-history!
  "Installs an implementation of RouteHistory onto the given Fulcro app."
  [app history]
  [(s/keys :req [::app/runtime-atom]) ::RouteHistory => any?]
  (swap! (::app/runtime-atom app) assoc ::history history)
  (add-route-listener! app ::rad-route-control
    (fn [route params]
      (if (and
            (dr/can-change-route? app)
            (cb/as-boolean (auth/can? app (auth/Execute `com.fulcrologic.rad.routing/route-to! {:path route}))))
        (dr/change-route! app route params)
        (do
          (log/warn "Browser routing event was denied.")
          (undo! app route params))))))

(>defn push-route!
  "Push the given route onto the route history (if history is installed)."
  [app-or-component target route-params]
  [any? (s/coll-of string? :kind vector?) map? => any?]
  (try
    (some-> app-or-component (active-history) (-push-route! target route-params))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn replace-route!
  "Replace the top of the current route stack "
  [app-or-component target route-params]
  [any? (s/coll-of string? :kind vector?) map? => any?]
  (try
    (some-> app-or-component (active-history) (-replace-route! target route-params))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(defn back!
  "Go to the last position in history (if history is installed)."
  [app-or-component]
  (try
    (some-> app-or-component (active-history) (-back!))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(defn undo!
  "Undo the (last) request to route that was delivered to a listener. Must be passed that parameters that were passed
  to the listener. Idempotent: calling this more than once will only have an effect once."
  [app-or-component new-route new-params]
  (try
    (some-> app-or-component (active-history) (-undo! new-route new-params))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn add-route-listener!
  "Add the callback `f` to the list of listeners. That listener will be known as `listener-key`. You should namespace that key to prevent conflicts."
  [app-or-component listener-key f]
  [any? keyword? fn? => any?]
  (try
    (some-> app-or-component (active-history) (-add-route-listener! listener-key f))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn remove-route-listener!
  "Remove the listener named `listener-key`."
  [app-or-component listener-key]
  [any? keyword? => any?]
  (try
    (some-> app-or-component (active-history) (-remove-route-listener! listener-key))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))

(>defn current-route
  "Returns a map of {:route [\"a\" \"b\"] :params {}}. The params are the extra state/params, and the route is purely strings."
  [app-or-component]
  [any? => (? (s/keys :req-un [::route ::params]))]
  (try
    (some-> app-or-component (active-history) (-current-route))
    (catch #?(:cljs :default :clj Exception) e
      (log/error e "Unable to execute history operation."))))
