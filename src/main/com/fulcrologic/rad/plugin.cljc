(ns com.fulcrologic.rad.plugin)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; An idea...we could make plugins have an interface so they could be composed
;; into some kind of system on client/server as a convenience...
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defprotocol RADPlugin
  (save-form [this env form-delta] "Execute whatever plugin-local instructions should happen when a form is saved.")
  (generate-resolvers [this attributes] "Should return a list of Pathom resolvers (if any) for the given attribute definitions.")
  (pathom-plugins [_] "Return plugins that should be installed into the pathom parser.")
  (start [_] "Plugin-specific operations that should run when starting the system. System code must ensure this runs in the proper order.")
  (stop [_] "Plugin-specific operations that should run when stopping the system. System code must ensure this runs in the proper order."))
