(ns com.fulcrologic.rad.attributes
  #?(:cljs (:require-macros com.fulcrologic.rad.attributes))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :as gr :refer [>defn => >def >fdef ?]]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.fulcro.components :as comp]
    [clojure.set :as set])
  #?(:clj
     (:import (clojure.lang IFn)
              (javax.crypto.spec PBEKeySpec)
              (javax.crypto SecretKeyFactory)
              (java.util Base64))))

(def attribute-registry (atom {}))

#?(:clj
   (>fdef defattr
     [kw type m]
     [qualified-keyword? keyword? map? => any?]))

#?(:clj
   (defmacro defattr
     "Create a data model attribute. Type can be one of :string, :int, :uuid, etc. (more types are added over time,
     so see main documentation and your database adapter for more information).

     The remaining argument is an open map of additional things that any subsystem can
     use to describe facets of this attribute that are important to your system.
     "
     [kw type m]
     (let [definition `(do
                         (swap! attribute-registry assoc ~kw ~(-> m
                                                                (assoc ::type type)
                                                                (assoc ::qualified-key kw)
                                                                (dissoc ::spec)))
                         ~kw)]
       definition)))

;; TODO: rename ref-target
(>def ::target qualified-keyword?)
(>def ::qualified-key qualified-keyword?)
(>def ::index? boolean?)
(>def ::component? boolean?)
(>def ::attribute (s/keys
                    :req [::type ::qualified-key]
                    :opt [::index? ::component? ::spec]))
(>def ::attributes (s/every qualified-keyword?))

(>defn key->attribute
  "Look up a schema attribute using the runtime registry. Avoids having attributes in application state"
  [k]
  [::qualified-key => ::attribute]
  (get @attribute-registry k))

(>defn to-int [str]
  [string? => int?]
  (if (nil? str)
    0
    (try
      #?(:clj  (Long/parseLong str)
         :cljs (js/parseInt str))
      (catch #?(:clj Exception :cljs :default) e
        0))))

;; TODO: These need to be tied to the database adapter. Native controls in DOM always deal in strings, but
;; it is possible that custom inputs might not need coercion?
(>defn string->value [k v]
  [::qualified-key string? => any?]
  (let [{::keys [type]} (key->attribute k)]
    (case type
      :uuid (new-uuid v)
      :int (to-int v)
      ;; TODO: More coercion
      v)))

(>defn value->string [k v]
  [::qualified-key any? => string?]
  (let [{::keys [type]} (key->attribute k)]
    ;; TODO: more coercion
    (str v)))

(>defn identity?
  [k]
  [qualified-keyword? => boolean?]
  (boolean (some-> k key->attribute ::unique (= :identity))))

(>defn attributes->eql
  "Returns an EQL query for all of the attributes that are available for the given database-id"
  [database-id attrs]
  [::db/id ::attributes => vector?]
  (reduce
    (fn [outs {::keys [qualified-key type target]}]
      (if (and target (= :ref type))
        (conj outs {qualified-key [target]})
        (conj outs qualified-key)))
    []
    (filter
      (fn [{::db/keys [id]}] (= id database-id))
      (map key->attribute attrs))))

#?(:clj
   (defn ^String gen-salt []
     (let [sr   (java.security.SecureRandom/getInstance "SHA1PRNG")
           salt (byte-array 16)]
       (.nextBytes sr salt)
       (String. salt))))

#?(:clj
   (defn ^String encrypt
     "Encrypt the given password, returning a string."
     [^String password ^String salt ^Long iterations]
     (let [keyLength           512
           password-characters (.toCharArray password)
           salt-bytes          (.getBytes salt "UTF-8")
           skf                 (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA512")
           spec                (new PBEKeySpec password-characters salt-bytes iterations keyLength)
           key                 (.generateSecret skf spec)
           res                 (.getEncoded key)
           hashed-pw           (.encodeToString (Base64/getEncoder) res)]
       (str salt "|" iterations "|" hashed-pw))))

