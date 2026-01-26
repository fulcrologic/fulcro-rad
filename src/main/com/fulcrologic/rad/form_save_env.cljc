(ns com.fulcrologic.rad.form-save-env
  "A namespace of helper functions when working with env that is passed through the RAD form save middleware"
  (:require
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]))

(defn new-master-entity?
  "Returns true if the master entity being saved has a tempid."
  [save-middleware-env]
  (tempid/tempid? (get-in save-middleware-env [:com.fulcrologic.rad.form/params :com.fulcrologic.rad.form/id])))

(defn master-ident
  "Returns the main entity's ident being saved by a RAD form from the save middleware env. Returns nil
if this is not a form save (dosn't have a master pk/id)"
  [{{:com.fulcrologic.rad.form/keys [master-pk id]} :com.fulcrologic.rad.form/params}]
  (when (and master-pk id)
    [master-pk id]))

(defn save-delta
  "Returns the full save delta."
  [env]
  (get-in env [::form/params ::form/delta]))

(defn save-delta-idents
  "Returns a set of all of the idents that are being saved by a RAD form save."
  [env]
  (set (some-> (save-delta env) (keys))))
