(ns com.fulcrologic.rad.middleware.save-middleware
  (:require
    [com.fulcrologic.rad.form :as form]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]))

(defmulti rewrite-value
  "
  [env ident value]

  Rewrites the attributes in the incoming diff of a save for a given `ident` so that they represent the correct. Should
   return the new `value`.  The default for this is to simply return value unmodified."
  (fn [env ident value] (first ident)))

(defmethod rewrite-value :default [_ _ v] v)

(defn rewrite-values [pathom-env save-params]
  (update save-params ::form/delta
    #(reduce-kv
       (fn rewrite-value-step* [m [table eid :as ident] new-value]
         (let [rewritten-value (rewrite-value pathom-env ident new-value)]
           (assoc m ident rewritten-value)))
       {} %)))

(defn wrap-rewrite-values
  ([handler]
   (fn [pathom-env params]
     (let [new-params (rewrite-values pathom-env params)]
       (handler pathom-env new-params))))
  ([]
   (fn [pathom-env params]
     (rewrite-values pathom-env params))))
