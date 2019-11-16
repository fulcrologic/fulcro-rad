(ns com.example.ui
  (:require
    [com.example.model.account :as acct]
    [com.example.schema :refer [schema]]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]))

(defsc AccountForm [this props]
  {::rad/schema      schema
   ::form/attributes [acct/name]
   ;; TODO: Derive query of attributes that are needed to manage the entities that hold the
   ;; attributes being edited.
   :form-fields      #{::acct/name}
   :query            [::acct/id ::acct/name]
   :ident            ::acct/id}
  (form/render-form this props))
