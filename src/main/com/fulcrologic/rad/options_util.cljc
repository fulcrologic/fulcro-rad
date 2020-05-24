(ns com.fulcrologic.rad.options-util
  "Utilities for interpreting and coping with form/report options."
  (:require
    #?(:clj  [cljs.analyzer :as ana]
       :cljs [goog.functions :as gf])
    [clojure.spec.alpha :as s]
    [clojure.string]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

#?(:clj
   (defn resolve-cljc
     "Usable in macros. Try to resolve the given raw-sym. If compiling CLJC/CLJS this requires that the raw-sym itself be
     in a CLJC file so it can be resolved at compile-time."
     [macro-env raw-sym]
     (let [sym-ns     (some-> raw-sym namespace symbol)
           {:keys [uses requires]} (get macro-env :ns)
           sym-ns     (if sym-ns (get requires sym-ns) (get uses raw-sym))
           to-resolve (if sym-ns (symbol (str sym-ns) (name raw-sym)) raw-sym)]
       (when sym-ns
         (require sym-ns))
       (resolve macro-env to-resolve))))

#?(:clj
   (defn resolve-key
     "Used by RAD macros to ensure that the given value is a keyword."
     [macro-env k?]
     (let [macro-env (merge {::original-key k?} macro-env)]
       (cond
         (var? k?) (var-get k?)
         (keyword? k?) k?
         (and (map? k?) (contains? k? :com.fulcrologic.rad.attributes/qualified-key)) (:com.fulcrologic.rad.attributes/qualified-key k?)
         (symbol? k?) (let [resolved (resolve-cljc macro-env k?)]
                        (resolve-key macro-env resolved))
         :else (::original-key macro-env)))))

#?(:clj
   (>defn resolve-keys
     "Used by RAD macros on options map to resolve keys in option maps at compile time. options-map MUST be
      a map already."
     [macro-env options-map]
     [any? map? => map?]
     (reduce-kv
       (fn [options k v]
         (assoc options (resolve-key macro-env k) v))
       {}
       options-map)))

(defn ?!
  "Run if the argument is a fn. This function can accept a value or function. If it is a
  function then it will apply the remaining arguments to it; otherwise it will just return
  `v`."
  [v & args]
  (if (fn? v)
    (apply v args)
    v))

(>defn qkey
  "Ensure that the argument, which can be the qualified key of an attribute or the attribute itself, is a keyword.

  Returns the value if is passed unless it is a map, in which case it returns the value at ::attr/qualified-key."
  [attr-or-keyword]
  [(s/or :k keyword :attr (s/keys :req [:com.fulcrologic.rad.attributes/qualified-key])) => (? keyword?)]
  (cond-> attr-or-keyword
    (and
      (map? attr-or-keyword)
      (contains? attr-or-keyword :com.fulcrologic.rad.attributes/qualified-key)) (get :com.fulcrologic.rad.attributes/qualified-key)))

(let [p! persistent!, t transient]
  (defn transform-entries
    "Convert all of the entries in the given map such that `{k v}` => `{(kf k) (vf v)}`"
    [m kf vf] (p! (reduce-kv (fn [m k v] (assoc! m (kf k) (vf v))) (t {}) m))))

(defn ?fix-keys
  "In RAD component options that are maps: the map keys *always* support keywords, but SHOULD allow attributes
   to be used for convenience. Therefore macros can transform any map keys that are attributes into their
   qualified key. This function takes a value that can be of any type. IF it detects that it is a map, then
   it will transform the map keys such that any attributes become their keywords, and any other kind of key is
   left alone."
  [v]
  (if (map? v)
    (enc/map-keys qkey v)
    v))

#?(:clj
   (>defn macro-optimize-options
     "Applies standard RAD optimizations to a macro's options map (where things may be symbolic). Returns an updated
      options map that contains new syntax that must be evaluated.

      Fixes anything listed in `keys-to-fix` by applying `opts/?fix-keys`, and anything in `key-transform`.

      The returned option map will change the values for keys in the `keys-to-fix`:

      * If there is an entry in `key-transforms` {k (fn [v] ...)} then it will use that (the fn should return syntax, since
        v may be symbolic)
      * Otherwise it will apply opts/?fix-keys iff the value is a map or is symbolic.
      "
     [env options keys-to-fix key-transforms]
     [any? map? (s/coll-of keyword? :kind set?) (s/map-of keyword? fn?) => map?]
     (try
       (reduce-kv
         (fn [new-options k v]
           (let [k (resolve-key env k)]
             (assoc new-options
               k (if-let [xform (get key-transforms k)]
                   (xform v)
                   (if (and (contains? keys-to-fix k) (or (map? v) (symbol? v)))
                     `(com.fulcrologic.rad.options-util/?fix-keys ~v)
                     v)))))
         {}
         options)
       (catch Exception e
         (throw (ana/error env (str "Cannot transform macro options map: " (.getMessage e))))))))

(>defn form-class
  "Attempt to coerce into a Fulcro component class. If the argument is a keyword it will look it up in Fulcro's
  component registry, otherwise the argument is return unmodified. May return nil if it is passed nil or the
  component is not registered at the provided key."
  [registry-key-or-component-class]
  [(? (s/or :registry-key keyword? :fulcro-class comp/component-class?)) => (? comp/component-class?)]
  (cond-> registry-key-or-component-class
    (keyword? registry-key-or-component-class) (comp/registry-key->class)))

(defn debounce
  "Debounce calls to f to at-most every tm ms. Trailing edge wins. In CLJ this just calls `f` immediately on the calling thread."
  [f tm]
  #?(:clj  f
     :cljs (gf/debounce f tm)))

(>defn narrow-keyword
  "Narrow the meaning of a keyword by turning the full original keyword into a namespace and adding the given
  `new-name`.

  ```
  (narrow-keyword :a/b \"c\") => :a.b/c
  ```

  Requires that the incoming keyword already have a namespace.
  "
  [k new-name]
  [qualified-keyword? (s/or :string string? :k keyword? :sym symbol?) => qualified-keyword?]
  (let [old-ns (namespace k)
        nm     (name k)
        new-ns (str old-ns "." nm)]
    (keyword new-ns new-name)))

(defn ast-child-classes
  "Returns a set of classes that are in the children of the given AST."
  [ast recursive?]
  (let [{:keys [children]} ast]
    (reduce
      (fn [result {:keys [component children]}]
        (cond-> result
          component (conj component)
          recursive? (into (mapcat #(ast-child-classes % recursive?)) children)))
      #{}
      children)))

(defn child-classes
  "Returns a de-duped set of classes of the children of the given instance/class (using it's query). An instance will
   use dynamic queries, but a class may not (depending on usage context in Fulcro).

   The `recursive?` flag  (default false) can be used to recurse the query to find all subchildren as well."
  ([class-or-instance]
   (child-classes class-or-instance false))
  ([class-or-instance recursive?]
   (let [q   (comp/get-query class-or-instance)
         ast (eql/query->ast q)]
     (ast-child-classes ast recursive?))))

