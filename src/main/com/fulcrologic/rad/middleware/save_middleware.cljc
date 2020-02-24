(ns com.fulcrologic.rad.middleware.save-middleware
  (:require
    [com.fulcrologic.rad.form :as form]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]))

(defmulti rewrite-value
  "
  [save-env ident save-diff]

  Given the save-env, ident of an entity, and incoming save diff (map from :before to :after for each
  changed attribute): Return an updated save-diff.  The default method for this simply returns save-diff.
  Returning nil from this method will have the effect of removing all values for `ident` from the save."
  (fn [save-env ident value] (first ident)))

(defmethod rewrite-value :default [_ _ v] v)

(defn rewrite-values
  "Rewrite the delta in ::form/params of save-env. Returns the new save-env."
  [save-env]
  (update-in save-env [::form/params ::form/delta]
    #(reduce-kv
       (fn rewrite-value-step* [m ident new-value]
         (let [rewritten-value (rewrite-value save-env ident new-value)]
           (if (nil? rewritten-value)
             (dissoc m ident)
             (assoc m ident rewritten-value))))
       {} %)))

(defn wrap-rewrite-values
  "Middleware that allows the distribution of incoming save diff rewrite across models. Should be at the bottom
   (early side) of the middleware if used."
  [handler]
  (fn [save-env]
    (let [new-save-env (rewrite-values save-env)]
      (handler new-save-env))))

