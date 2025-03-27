(ns com.fulcrologic.rad.routing
  "A support layer for application-level routing. RAD supports the idea of an *application-level* history. This
  allows it to abstract over the concepts of relative navigation since it can be used
  on many platforms (like React Native or Electron) where no natural browser history API exists.

  History support in RAD requires that you install an implementation of RouteHistory at application start time. See
  `com.fulcrologic.rad.routing.history` and associated namespaces.

  Functions in this namespace that do relative routing will silently fail if no such history support is installed."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.routing.history :as history]
    [taoensso.timbre :as log]))

(defn absolute-path
  "Get the absolute path for the given route target. NOTE: Using a route target in multiple paths of your application
   can lead to ambiguity and failure of general routing, since this will then return an unpredictable result."
  [app-ish RouteTarget route-params]
  (let [app       (comp/any->app app-ish)
        app-root  (app/root-class app)
        state-map (app/current-state app)]
    (binding [rc/*query-state* state-map]
      (dr/resolve-path app-root RouteTarget route-params))))

(defn can-change-route?
  [app-or-component new-route]
  (let [app            (rc/any->app app-or-component)
        root           (app/root-class app)
        [relative-class-or-instance _] (dr/evaluate-relative-path root new-route)
        relative-class (if (rc/component? relative-class-or-instance)
                         (comp/react-type relative-class-or-instance)
                         relative-class-or-instance)]
    (dr/can-change-route? app relative-class)))

(defn route-to!
  "Change the UI to display the route to the specified class, with the additional parameter map as route params. If
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
  ([app options]
   (dr/route-to! app (assoc options :before-change (fn [app {:keys [path route-params]}]
                                                     (if (::replace-route? route-params)
                                                       (history/replace-route! app path route-params)
                                                       (history/push-route! app path route-params))))))
  ([app-or-component RouteTarget route-params]
   (route-to! app-or-component {:target       RouteTarget
                                :route-params route-params})))

(defn back!
  "Attempt to navigate back to the last point in history. Returns true if there is history support, false if
   it is impossible to even try to go back."
  [app-or-component]
  (if (history/history-support? app-or-component)
    (do
      (history/back! app-or-component)
      true)
    false))

(defn update-route-params!
  "Like `clojure.core/update`. Has no effect if history support isn't installed.

  Run `(apply f current-route-params args)` and store those as the current route params."
  [app-or-component f & args]
  (when (history/history-support? app-or-component)
    (let [{:keys [route params]} (history/current-route app-or-component)
          new-params (apply f params args)]
      (history/replace-route! app-or-component route new-params))))
