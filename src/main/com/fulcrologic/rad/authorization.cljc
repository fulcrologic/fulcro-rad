(ns com.fulcrologic.rad.authorization
  (:require
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

(defstatemachine authorization-machine-definition
  {::uism/actors
   #{}

   ::uism/aliases
   {}

   ::uism/states
   {:initial
    {}

    :state/idle
    {}

    :state/authorizing
    {}

    }})


