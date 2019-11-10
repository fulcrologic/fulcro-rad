(ns com.fulcrologic.rad.attributes
  #?(:cljs (:require-macros com.fulcrologic.rad.attributes))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.string :as str])
  #?(:clj
     (:import (clojure.lang IFn IDeref))))

#?(:clj
   (deftype Attribute [definition]
     IDeref
     (deref [this] (::keyword definition))
     IFn
     (invoke [this m]
       (get m (::keyword definition))))
   :cljs
   (deftype Attribute [definition]
     IDeref
     (-deref [_] (::keyword definition))
     IFn
     (-invoke [_ m]
       (get m (::keyword definition)))))

#?(:clj
   (defmacro defattr [sym type & {:as m}]
     (let [nspc       (if (comp/cljs? &env) (-> &env :ns :name str) (name (ns-name *ns*)))
           spec       (::clojure-spec m)
           kw         (keyword (str nspc) (name sym))
           spec-def   (when spec `(s/def ~kw ~spec))
           output     (-> m
                        (assoc ::type type)
                        (assoc ::keyword kw)
                        (dissoc ::clojure-spec))
           definition `(def ~sym (com.fulcrologic.rad.attributes/->Attribute ~output))]
       (if spec-def
         `(do
            ~spec-def
            ~definition)
         definition))))

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
