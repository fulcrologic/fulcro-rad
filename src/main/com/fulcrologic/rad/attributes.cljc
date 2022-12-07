(ns com.fulcrologic.rad.attributes
  #?(:cljs (:require-macros com.fulcrologic.rad.attributes))
  (:require
    #?(:clj [com.fulcrologic.rad.registered-maps :refer [registered-map]])
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [taoensso.timbre :as log]
    [com.fulcrologic.guardrails.core :refer [>defn => >def >fdef ?]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.rad.ids :refer [new-uuid]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.fulcro.components :as comp])
  #?(:clj
     (:import (clojure.lang IFn)
              (javax.crypto.spec PBEKeySpec)
              (javax.crypto SecretKeyFactory)
              (java.util Base64))))

(>def ::qualified-key qualified-keyword?)
(>def ::type keyword?)
(>def ::target qualified-keyword?)
(>def ::attribute (s/keys :req [::type ::qualified-key]
                    :opt [::target]))
(>def ::attributes (s/every ::attribute))
(>def ::attribute-map (s/map-of keyword? ::attribute))
(>def ::id-keys (s/every qualified-keyword? :kind set?))

(>defn new-attribute
  "Create a new attribute, which is represented as an Attribute record.

  Type can be one of :string, :int, :uuid, etc. (more types are added over time,
  so see main documentation and your database adapter for more information).

  The remaining argument is an open map of additional things that any subsystem can
  use to describe facets of this attribute that are important to your system.

  If `:ref` is used as the type then the ultimate ID of the target entity should be listed in `m`
  under the ::target key.
  "
  [kw type m]
  [qualified-keyword? keyword? map? => ::attribute]
  (let [v (-> m
            (assoc ::type type)
            (assoc ::qualified-key kw))]
    (when (and (not= :ref type) (contains? m ::target))
      (log/warn "NON-Reference attribute" kw "was given an ao/target. This could cause errors in code that generates code from the attribute."))
    (when (and (= :ref type) (not (contains? m ::target)))
      (log/warn "Reference attribute" kw "does not list a target ID. Resolver generation will not be accurate."))
    #?(:clj  (registered-map (or (-> m meta :registration-key) kw) v)
       :cljs v)))

#?(:clj
   (defmacro ^{:arglists '[[symbol docstring? qualified-keyword data-type options-map]]} defattr
     "Define a new attribute into a sym.

     WARNING: IF YOU ARE DOING FULL-STACK, THEN THESE MUST BE DEFINED IN CLJC FILES FOR RAD TO WORK! RAD actually supports
     the idea of having rendering plugins that work just in the JVM, in which case all of your code can be CLJ. It can
     also support client-side database adapters, which would mean that all of your code would be CLJS.

     * `sym`: The name of the new var to create.
     * `qualified-keyword`: The unique (keyword) name of this attribute.
     * `data-type`: A supported data type (e.g. :int, :uuid, :ref, :instant).
     * `options-map`: An open map (any data can be placed in here) of key-value pairs.

     An attribute defines a logical bit of data in your graph model that may be stored in a database, derived by complex
     logic, or simply generated out of thin air.  Attributes are the central place in RAD where information *about* your
     desired data model is supplied.

     The most common options to put in `options-map` are:

     * `::attr/identities` - if persisted in storage, but NOT a table/row/entity key.
     * `::attr/identity?` - if a PK or natural key
     * `::attr/schema` - if persisted in storage
     * `::attr/target` - if type `:ref`

     See the `attributes-options` namespace for more details.

     Attributes types are extensible (though there are many built in), and the concept of traversal to to-one and to-many
     edges in either direction is very easily represented in a natural, database-independent, fashion (though database
     adapters may require you to supply more information in order for them to actually do concrete work for you).

     A attribute is required to have a *qualified key* that uniquely designates its name in the model, and a data
     type. The options map is a completely open map of key-value pairs that can further describe the details of
     an attribute. Some of those are standard options (found in the attributes-options namespace), and many more
     are defined by reports, forms, database adapters, and rendering plugins. Look for `*-options` namespaces in
     order to find vars with docstrings that describe the possible options."
     [sym & args]
     (let [[k type m] (if (string? (first args)) (rest args) args)
           nspc (if (comp/cljs? &env) (-> &env :ns :name str) (name (ns-name *ns*)))
           fqkw (keyword (str nspc) (name sym))]
       `(def ~sym (new-attribute ~k ~type (with-meta ~m {:registration-key ~fqkw}))))))

(>defn to-many?
  "Returns true if the attribute with the given key is a to-many."
  [attr]
  [::attribute => boolean?]
  (= :many (::cardinality attr)))

(>defn to-one?
  "Returns true if the attribute with the given key is a to-one."
  [attr]
  [::attribute => boolean?]
  (not= :many (::cardinality attr)))

(>defn to-int [str]
  [string? => int?]
  (if (nil? str)
    0
    (try
      #?(:clj  (Long/parseLong str)
         :cljs (js/parseInt str))
      (catch #?(:clj Exception :cljs :default) e
        0))))

(>defn attributes->eql
  "Returns an EQL query for all of the attributes that are available for the given database-id"
  [attrs]
  [::attributes => vector?]
  (reduce
    (fn [outs {::keys [qualified-key type target targets]}]
      (cond
        (and (seq targets) (= :ref type)) (conj outs {qualified-key (into {}
                                                                      (map (fn [t] [t [t]]))
                                                                      targets)})
        (and target (= :ref type)) (conj outs {qualified-key [target]})
        :else (conj outs qualified-key)))
    []
    attrs))

#?(:clj
   (defn ^String gen-salt []
     (let [sr   (java.security.SecureRandom/getInstance "SHA1PRNG")
           salt (byte-array 16)]
       (.nextBytes sr salt)
       (String. salt))))

#?(:clj
   (defn ^String encrypt
     "Returns a cryptographycally-secure hashed password based on the given a plain-text password,
      a random salt string (see `gen-salt`), and a number of iterations.  You should save the hashed result, salt, and
      iterations in your database. Checking a password is then taking the password the user supplied, passing it through
      this function with the original salt and iterations, and seeing if the hashed result is the same as the original.
     "
     [^String password ^String salt ^Long iterations]
     (let [keyLength           512
           password-characters (.toCharArray password)
           salt-bytes          (.getBytes salt "UTF-8")
           skf                 (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA512")
           spec                (new PBEKeySpec password-characters salt-bytes iterations keyLength)
           key                 (.generateSecret skf spec)
           res                 (.getEncoded key)
           hashed-pw           (.encodeToString (Base64/getEncoder) res)]
       hashed-pw)))

(>defn attribute?
  [v]
  [any? => boolean?]
  (contains? v ::qualified-key))

(>defn eql-query
  "Convert a query that uses attributes (records) as keys into the proper EQL query. I.e. (eql-query [account/id]) => [::account/id]
   Honors metadata and join nesting."
  [attr-query]
  [vector? => vector?]
  (walk/prewalk
    (fn [ele]
      (if (attribute? ele)
        (::qualified-key ele)
        ele)) attr-query))

(defn valid-value?
  "Checks if the value looks to be a valid value based on the ::attr/required? and ::attr/valid? options of the
   given attribute.

   Returns true if:

   * The value is completely missing (nil), and not marked `ao/required?`
   * The attribute defines a `ao/valid?` predicate that returns true.
   * The attribute has NO `ao/valid?` option but is marked `ao/required?`
     but the value is non-nil (and if a string, non-blank).

   Otherwise returns false.
   "
  [{::keys [required? valid?] :as attribute} value props k]
  (let [non-empty-value? (and
                           (not (nil? value))
                           (or
                             (not (string? value))
                             (not= 0 (count (str/trim value)))))]
    (or
      (and (nil? value) (not required?))
      (if valid?
        (valid? value props k)
        (or (not required?) non-empty-value?)))))

(>defn attribute-map
  "Returns a map of qualified key -> attribute for the given attributes"
  [attributes]
  [::attributes => ::attribute-map]
  (into {}
    (map (fn [{::keys [qualified-key] :as a}] [qualified-key a]))
    attributes))

(>defn entity-map
  "Returns a map of qualified ID key -> the collection of attributes on entities that have that ID."
  [attributes]
  [::attributes => (s/map-of qualified-keyword? ::attributes)]
  (reduce
    (fn [acc attr]
      (let [identities (ao/identities attr)]
        (reduce
          (fn [acc2 k]
            (update acc2 k (fnil conj []) attr))
          acc
          identities)))
    {}
    attributes))

(defn make-attribute-validator
  "Creates a Fulcro form-state validator function that can be used as a form validator for any form that contains
   the given `attributes`.

   A field is considered valid in this validator IF AND ONLY IF `attr/valid-value` returns true. See that
   function's docstring for how that interacts with the `ao/valid?` option of attributes.
   "
  [attributes]
  (let [attribute-map (attribute-map attributes)]
    (fs/make-validator
      (fn [form k]
        (valid-value? (get attribute-map k) (get form k) form k)))))

(defn wrap-env
  "Build a (fn [env] env') that adds RAD attribute data to an env. If `base-wrapper` is supplied, then it will be called
   as part of the evaluation, allowing you to build up a chain of environment middleware.

   ```
   (def build-env
     (-> (wrap-env all-attributes)
        ...))

   ;; Pathom 2
   (def env-plugin (p/env-wrap-plugin build-env))

   ;; Pathom 3
   (let [base-env (pci/register [...])
         env (build-env base-env)]
      (process env eql))
   ```

   similar to Ring middleware.
   "
  ([all-attributes] (wrap-env nil all-attributes))
  ([base-wrapper all-attributes]
   (let [key->attribute (attribute-map all-attributes)
         id-keys        (into #{}
                          (comp
                            (filter ::identity?)
                            (map ::qualified-key))
                          all-attributes)]
     (fn [env]
       (cond-> (assoc env
                 ::key->attribute key->attribute
                 ::id-keys id-keys)
         base-wrapper (base-wrapper))))))

(defn pathom-plugin
  "Pathom 2 plugin. See also `wrap-env`."
  [all-attributes]
  (let [augment (wrap-env all-attributes)]
    {:com.wsscode.pathom.core/wrap-parser
     (fn env-wrap-wrap-parser [parser]
       (fn env-wrap-wrap-internal [env tx]
         (parser (augment env) tx)))}))
