(ns com.fulcrologic.rad.routing.base)

(defprotocol RADRouter
  (-route-to! [this app options] [this app RouteTarget route-params]
    "Protocol method. Implement and install an instance of this protocol to alter how RAD interacts with the UI
     routing system. Default to using Fulcro Dynamic Router.

     The `options` version is recommended to accept the keys:

     `:target` - A class name of the target to route to
     `:route-params` - Parameters to send to that target.

     other options may be supported.

     Use route-to! instead of calling this function directly."))

(defn dynamic-routing?
  "Returns true when the system is using Fulcro's dynamic routing ns."
  [app]
  (nil? (some-> app :com.fulcrologic.fulcro.application/runtime-atom deref :com.fulcrologic.rad.routing/routing)))
