(ns com.fulcrologic.rad.middleware.autojoin
  "This middleware allows you to declare that a particular entity MUST be joined to some parent, but
   enables you to create new/edit that entity in isolation (without needing a subform).

   RAD form graphs are inherently tree-centric (because of React), and so children are always represented
   as forward references in the graph. This is inconvenient when you want to edit or create a
   child in isolation, but you need to make sure it is joined to the parent.  This middleware rewrites
   a save that includes a value (an ident) in a virtual attribute that has a `forward-reference` option
   as follows:

   ```
   {::form/delta {[:todo-item/id _tempid_]
                  {:todo-item/list {:after [:todo-list/id 1]}}
                   :todo-item/label {:after \"foo\"}}}
   ```

   where `:todo-item/list` is a virtual edge pointing back to the owning list. It rewrites the delta to:

   ```
   {::form/delta {[:todo-item/id _tempid_]
                  {:todo-item/label {:after \"foo\"}}

                  [:todo-list/id 1]
                  {:todo-list/items {:before [] :after [[:todo-item/id _tempid_]]}}}}
   ```

   this assumes that you are using a db driver that *does not* do pessimistic checks of the before and after (none
   of the Fulcrologic drivers do). This is because the set logic of before/after on a to-many works fine for
   adding members to a set.

   Note, that if you CHANGE parents (make the virtual edge editable) be sure to pre-populate the virtual edge so that
   the delta on to-many edges will include a before of the old value, to ensure a proper database update.

   To set this system up:

   . Install the `wrap-autojoin` in your save middleware so that it happens before the database save middleware, You must
     include the standard attribute and form plugins in your pathom system so that the standard `env` contains
     `::attr/key->attribute` (your RAD model map).
   . Add the `autojoin-options/forward-reference` option to which *forward* edge must be fixed on save.
   . (optional, if you want edit to work) Make a virtual _reverse_ edge (`defattr`) from the child to the parent so that it can be resolved on load.

   When the entity in question is *new* (has a tempid), then this middleware *requires* that
   the virtual edge be included in the form attributes (so it is included on the save), and
   must be populated with the correct ident of the parent in question.

   For *new* entities where the parent it known, you can simply pre-populate the field using the
   `:initial-state` parameter of `form/create!`. You may pre-populate this virtual edge in that
   initial state using an ident or a single-entry map that contains the id key/value.
   (i.e. `{:entity/edge [:parent/id ...]}` or `{:entity/edge {:parent/id ...}}`)

   "
  (:require
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.middleware.autojoin-options :as ajo]
    [com.fulcrologic.rad.attributes :as attr]
    [edn-query-language.core :as eql]))

(defn keys-to-autojoin [all-attributes]
  (into #{}
    (comp
      (filter ajo/forward-reference)
      (map ao/qualified-key))
    all-attributes))

(defn- id-key [v] (first (filter #(and (= "id" (name %)) (not= "db" (namespace %))) (keys v))))

(defn- ensure-ident
  "Ensure single-entry maps are turned into proper idents"
  [v]
  (cond
    (eql/ident? v) v
    (map? v) (if-let [k (id-key v)] [k (get v k)] nil)))

(defn autojoin [{::attr/keys [key->attribute]
                 ::form/keys [params] :as pathom-env}]
  (let [{::form/keys [delta]} params
        all-attributes (vals key->attribute)
        affected-ks    (keys-to-autojoin all-attributes)
        paths-to-check (for [[ident change] delta
                             k (keys change)
                             :when (contains? affected-ks k)]
                         [ident k])
        rewrite-join   (fn [delta path]
                         (let [[ident k] path
                               {:keys [before after]} (get-in delta path)
                               before   (ensure-ident before)
                               after    (ensure-ident after)
                               fref     (ajo/forward-reference (key->attribute k))
                               ref-attr (key->attribute fref)
                               many?    (= :many (ao/cardinality ref-attr))]
                           (-> (update delta ident dissoc k)
                             (cond->
                               (not many?) (cond->
                                             after (assoc-in [after fref] {:after ident})
                                             before (assoc-in [before fref] {:before ident}))
                               many? (cond->
                                       after (update-in [after fref :after] (fnil conj []) ident)
                                       before (update-in [before fref :before] (fnil conj []) ident))))))
        new-delta      (reduce rewrite-join delta paths-to-check)]
    (assoc-in pathom-env [::form/params ::form/delta] new-delta)))

(defn wrap-autojoin
  [handler]
  (fn [pathom-env]
    (handler (autojoin pathom-env))))
