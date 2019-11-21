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
     (defentity account [id name password]
       ::spec (s/keys :req [::id ::name ::password]))
     ```
     "
     [sym attributes & {:as m}]
     (let [nspc       (if (comp/cljs? &env) (-> &env :ns :name str) (name (ns-name *ns*)))
           spec       (::spec m)
           kw         (keyword (str nspc) (name sym))
           spec-def   (when spec `(gr/>def ~kw ~spec))
           output     (-> m
                        (assoc ::qualified-key kw)
                        (assoc ::attributes attributes)
                        (dissoc ::spec))
           definition `(def ~sym ~output)]
       (if spec-def
         `(do
            ~spec-def
            ~definition)
         definition))))

