(ns com.fulcrologic.rad.routing.history
  "Generic history protocol and support.

  Cannot be used with statecharts-based RAD.

  In order to use history, you must install an implementation on your Fulcro app at application start-time that
  is compatible with your runtime environment (browser, native mobile, etc.) via `install-route-history!`. Once
  you've done that, then the non-protocol methods in this namespace can be used against the app to update the *history*,
  but they will *not* affect the actual *application route*. Actual routing should always be done via the
  `com.fulcrologic.rad.routing` namespace functions, which will keep track of history if it is installed."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.routing.system :as rsys]
    [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
    [taoensso.timbre :as log]))

(s/def ::route (s/coll-of string? :kind vector?))
(s/def ::params map?)

(defn active-history
  "Returns the active (installed) RouteHistory implementation, or nil if none is installed."
  [app-ish]
  nil)

(>defn history-support?
  "Returns true if RAD history support is enabled on the given app (you can also pass a component)."
  [app-ish]
  [any? => boolean?]
  (boolean (rsys/current-routing-system app-ish)))

(declare add-route-listener! undo!)

(>defn ^:deprecated install-route-history!
  "Installs an implementation of RouteHistory onto the given Fulcro app.

  `route-predicate` is an optional `(fn [app route params])` that should return true
  if the route change is allowed, and false otherwise. The default value is
  `(fn [app _ _] (dr/can-change-route? app))`."
  ([app history route-predicate]
   [(s/keys :req [::app/runtime-atom]) ::RouteHistory fn? => any?]
   (log/error "Route history no longer supported. Use Fulcro's routing systems instead"))
  ([app history]
   [(s/keys :req [::app/runtime-atom]) ::RouteHistory => any?]
   (log/error "Route history no longer supported. Use Fulcro's routing systems instead")))

(defn push-route!
  "Push the given route onto the route history (if history is installed). A route is a vector of the route segments
   that locate a given target."
  [app-or-component route route-params]
  (rsys/route-to! app-or-component {:route  route
                                    :params route-params}))

(defn replace-route!
  "Replace the top of the current route stack "
  [app-or-component target route-params]
  (rsys/replace-route! app-or-component {:target target
                                         :params route-params}))

(defn back!
  "Go to the last position in history (if history is installed)."
  [app-or-component]
  (rsys/back! app-or-component))

(defn undo!
  "Undo the (last) request to route that was delivered to a listener. Must be passed that parameters that were passed
  to the listener. Idempotent: calling this more than once will only have an effect once."
  [app-or-component new-route new-params]
  (log/warn "Undo is now a no-op."))

(>defn add-route-listener!
  "Add the callback `f` to the list of listeners. That listener will be known as `listener-key`. You should namespace that key to prevent conflicts.

   `f` - A `(fn [route params])`, where `route` is a vector of strings, and params is the route parameter map."
  [app-or-component listener-key f]
  [any? keyword? fn? => any?]
  (rsys/add-route-listener! app-or-component listener-key (fn [{:keys [route params]}]
                                                            (f route params))))

(>defn remove-route-listener!
  "Remove the listener named `listener-key`."
  [app-or-component listener-key]
  [any? keyword? => any?]
  (rsys/remove-route-listener! app-or-component listener-key))

(>defn current-route
  "Returns a map of {:route [\"a\" \"b\"] :params {}}. The params are the extra state/params, and the route is purely strings."
  [app-or-component]
  [any? => (? (s/keys :req-un [::route ::params]))]
  (rsys/current-route app-or-component))
