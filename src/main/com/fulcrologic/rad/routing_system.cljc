(ns com.fulcrologic.rad.routing-system
  "Generalize protocol to wrap the conceptual methods of a routing system.")

(defprotocol RoutingSystem
  (resolve-path [system app TargetComponentClass route-params])
  (can-change-route? [system ])

  )
