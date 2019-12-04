(ns com.fulcrologic.rad.entity
  #?(:cljs (:require-macros com.fulcrologic.rad.entity))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => >def >fdef]]
    [com.fulcrologic.fulcro.components :as comp]))

(>def ::qualified-key qualified-keyword?)
(>def ::attributes ::attr/attributes)
(>def ::entity (s/keys :req [::qualified-key ::attributes]))

#?(:clj
   (defmacro defentity
     "Define an entity with attributes, and optional additional options.

     ```
     (defentity account [::id ::name ::password])
     ```
     "
     [sym attributes options]
     (let [nspc   (if (comp/cljs? &env) (-> &env :ns :name str) (name (ns-name *ns*)))
           kw     (keyword (str nspc) (name sym))
           output (-> options
                    (assoc ::qualified-key kw)
                    (assoc ::attributes attributes))]
       `(def ~sym ~output))))

