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
  [pathom-env]
  (update-in pathom-env [::form/params ::form/delta]
    #(reduce-kv
       (fn rewrite-value-step* [m ident new-value]
         (let [rewritten-value (rewrite-value pathom-env ident new-value)]
           (if (nil? rewritten-value)
             (dissoc m ident)
             (assoc m ident rewritten-value))))
       {} %)))

(defn wrap-rewrite-values
  "Middleware that allows the distribution of incoming save diff rewrite across models. Should be at the bottom
   (early side) of the middleware if used."
  [handler]
  (fn [pathom-env]
    (let [new-save-env (rewrite-values pathom-env)]
      (handler new-save-env))))

(defn wrap-rewrite-delta
  "Save middleware that adds a step in the middleware that can rewrite the incoming delta of a save.
  The rewrite is allowed to do anything at all to the delta: add extra entities, create relations, augment
  entities, or even clear the delta to an empty map so nothing will be saved.

  The `rewrite-fn` should be a `(fn [pathom-env delta] updated-delta)`. You *can* return nil to indicate no
  rewrite is needed, but any other return will be used as the new thing to save (instead of what was sent).

  The `delta` has the format of a normalized Fulcro form save:

  ```
  {[:account/id 19] {:account/age {:before 42 :after 43}
                     :account/items {:before [] :after [[:item/id 1]]}}
   [:item/id 1] {:item/value {:before 22M :after 19.53M}}}
  ```
  "
  [handler rewrite-fn]
  (fn [env]
    (let [old-delta (get-in env [::form/params ::form/delta])
          new-delta (or (rewrite-fn env old-delta) old-delta)]
      (handler (assoc-in env [::form/params ::form/delta] new-delta)))))