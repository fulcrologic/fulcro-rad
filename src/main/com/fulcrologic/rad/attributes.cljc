(ns com.fulcrologic.rad.attributes
  #?(:cljs (:require-macros com.fulcrologic.rad.attributes))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => >def >fdef]]
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.set :as set])
  #?(:clj
     (:import (clojure.lang IFn))))

;; defrecord so we get map-like behavior and proper = support
#?(:clj
   (defrecord Attribute []
     IFn
     (invoke [this m]
       (get m (::qualified-key this))))
   :cljs
   (defrecord Attribute [definition]
     IFn
     (-invoke [this m]
       (get m (::qualified-key this)))))

#?(:clj
   (>fdef defattr
     [sym type & args]
     [simple-symbol? ::type (s/* (s/cat :k qualified-keyword? :v any?)) => any?]))

#?(:clj
   (defmacro defattr
     "Create a data model attribute. Type can be one of :string, :int, :uuid, etc. (more types are added over time,
     so see main documentation and your database adapter for more information).

     The remaining arguments are key-value pairs, and these represent an open set of options
     that can be used to add features to attributes arbitrarily. Thus, you should consult the
     documentation of various other modules for what to include on an attribute.

     By default the following are supported:

     * `::attr/spec spec` - A clojure spec for the attribute. Will cause the macro to emit a guardrails `>def`.

     "
     [sym type & {:as m}]
     (let [nspc       (if (comp/cljs? &env) (-> &env :ns :name str) (name (ns-name *ns*)))
           spec       (::spec m)
           kw         (keyword (str nspc) (name sym))
           spec-def   (when spec `(gr/>def ~kw ~spec))
           output     (-> m
                        (assoc ::type type)
                        (assoc ::qualified-key kw)
                        (dissoc ::spec))
           definition `(def ~sym (com.fulcrologic.rad.attributes/map->Attribute ~output))]
       (if spec-def
         `(do
            ~spec-def
            ~definition)
         definition))))

(>def ::type #{:string :uuid :int :inst :ref :keyword})
(>def ::spec any?)
(>def ::qualified-key qualified-keyword?)
(>def ::attribute (s/keys
                    :req [::type ::qualified-key]
                    :opt [::spec]))

;;(comment
;;  (defattr phone-number
;;    {::clojure-spec (s/with-gen string? #(s/gen #{"5415551212" "4689991122"}))
;;     ::database/id  :production
;;     ::type         :string
;;     ::chrome       [:phone-number :string]
;;     ::normalizer   (fn [v] (str/replace v #"\D" ""))
;;     ::validator    (fn [v] (boolean (re-matches #"\d{10}" v)))
;;     ::formatter    (fn [v] (let [[_ area prefix number] (re-matches #"(\d{3})(\d{3})(\d{4})" v)]
;;                              (str "(" area ") " prefix "-" number)))
;;     :other         1}))
