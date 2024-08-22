(ns com.fulcrologic.rad.middleware.autojoin-options
  (:require
    [com.fulcrologic.rad.options-util :refer [defoption]]))

(defoption forward-reference
  "ATTRIBUTE option. The value of this option is the keyword that represents the real schema edge
   (which is saved in the database) from a parent to this entity.

   This option is placed on a *virtual* attribute (no schema).

   ```
   (defattr todo-list :todo-item/todo-list :ref
     {ao/identities #{:todo-item/id}
      aho/forward-reference :todo-list/items})
   ```

   if you want to be able to edit (and not just create) this edge, then you must also provide a resolver
   fo the virtual attribute which returns either a single-entry map identifying the parent, or an ident of the parent.
   On create, you must include the edge in the form initial state:

   ```
   (form/create! this ItemForm {:initial-state {:todo-item/list [:todo-list/id 42]}})
   ```

   You MUST install the `autojoin` middleware into your save middleware for this to work properly.

   See the autojoin namespace docstring for more details.
   ")
