(ns com.fulcrologic.rad.routing
  "A wrapper for Fulcro's routing system. This is a historical compatiblity ns, and should not be used in new applications.

   Use Fulcro's routing systems instead."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.routing.system :as rsys]))

(defn ^:deprecated absolute-path
  "Use Fulcro's routing system instead. This method will fail on non dr routing systems.

   Get the absolute path for the given route target. NOTE: Using a route target in multiple paths of your application
   can lead to ambiguity and failure of general routing, since this will then return an unpredictable result."
  [app-ish RouteTarget route-params]
  (let [app       (comp/any->app app-ish)
        app-root  (app/root-class app)
        state-map (app/current-state app)]
    (binding [rc/*query-state* state-map]
      (dr/resolve-path app-root RouteTarget route-params))))

(defn ^:deprecated can-change-route?
  "Use Fulcro's routing system instead"
  [app-or-component new-route]
  (not (rsys/current-route-busy? app-or-component)))

(defn ^:deprecated route-to!
  "Use Fulcro's routing system instead.

  Change the UI to display the route to the specified class, with the additional parameter map as route params. If
  route history is installed, then it will be notified of the change. This function is also integrated into the RAD
  authorization system.

  The `RouteTarget` should be a _leaf_ target. Fulcro will correctly route through all the parent routers - just
  make sure that `route-params` includes all the params that are needed.

  NOTES:
    * The RouteTarget can be a component class or Fulcro registry key.
    * This function derives the absolute path. If the given target exists as a sibling to itself in your UI
      composition, then the result will be ambiguous and you must use `dr/change-route!` directly instead, which
      does not suffer from this ambiguity.

  You may include `::rad-routing/replace-route? true` in route-params as a hint to the history that you'd prefer to
  replace the top history element instead of pushing a new one.

  `options` is a map that is the same as `dr/route-to!`, and supports things like `:route-params`, `:target`,
  and dynamic route injection/loading."
  ([app options] (rsys/route-to! app options))
  ([app-or-component RouteTarget route-params]
   (route-to! app-or-component {:target RouteTarget
                                :params route-params})))

(defn ^:deprecate back!
  "Use Fulcro's routing system instead.

   Attempt to navigate back to the last point in history. Returns true if there is history support, false if
   it is impossible to even try to go back."
  [app-or-component]
  (rsys/back! app-or-component)
  true)

(defn update-route-params!
  "Like `clojure.core/update`. Has no effect if a routing system isn't properly installed.

  Just delegates to Fulcro's routing system.

  Run's `(apply f current-route-params args)` and store those as the current route params."
  [app-or-component f & args]
  (apply rsys/update-route-params! app-or-component f args))
