(ns com.fulcrologic.rad.routing
  "A support layer for application-level routing. RAD supports an application-level of history, since it may be used
  on platforms (like React Native or Electron) where no natural browser history API exists. Even users
  of RAD on web platforms may choose not to use HTML5 routing, and it is also useful to have a layer on which you
  can save metadata about a route's past use to more intelligently inform routing decisions.

  DEVELOPMENT NOTES:

  RAD elements are meant to be composed. As such, it is possible for you to place a RAD component at some arbitrarily-nested
  location in the application. The routing support allows you to configure the path to any forms that you choose
  not to put at the root of the application.

  - Something can be a route target in dynamic routing, but its path can be arbitrarily deep
  - We should be able to scan for all distinct RAD route targets, and record in routing the true absolute path to all
    singleton classes in the root query.
  - We can warn when the same class appears in more than one position in the query, such that we cannot find a distinct route
  for it.
  - Elements in *containers* are never directly routable: The container controls the sub-routes, so they can be excluded from warnings
  -

"
  (:require
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.components :as comp]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]))

(defn absolute-path
  "Get the absolute path for the given route target."
  [app-ish RouteTarget route-params]
  (let [app       (comp/any->app app-ish)
        state-map (app/current-state app)
        app-root  (app/root-class app)]
    (dr/resolve-path app-root RouteTarget route-params)))

(defn route-to!
  "Change the route the specified class, with the additional parameter map as route params"
  [app-or-component RADClass route-params]
  (if-let [path (absolute-path app-or-component RADClass route-params)]
    (dr/change-route! app-or-component path)
    (log/error "Cannot find path for" (comp/component-name RADClass))))

(defn push-route! [app-or-component target route-params])

(defn pop-route! [app-or-component])

(defn replace-route! [app-or-component target route-params])
