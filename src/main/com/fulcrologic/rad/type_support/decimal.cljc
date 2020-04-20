(ns com.fulcrologic.rad.type-support.decimal
  "Support for CLJC arbitrary precision decimal numerics. CLJ support for BigDecimal works well, but it is not
  directly supported in CLJS.  Use the support in this ns when you need to write CLJC code that works the same
  in both.

  You MUST include big.js as a js dependency for this to compile in CLJS."
  (:refer-clojure :exclude [name + - * = < > <= >= max min])
  #?(:cljs (:require-macros [com.fulcrologic.rad.type-support.decimal]))
  (:require
    #?@(:cljs [["big.js" :as Big]
               [goog.object :as obj]
               [cljs.reader :as reader]
               [com.cognitect.transit.types :as ty]])
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >defn- => ?]]
    [cognitect.transit :as ct]
    [clojure.string :as str])
  #?(:clj (:import (java.math RoundingMode)
                   (java.text NumberFormat)
                   (java.util Locale))))

(def ^:dynamic *primitive* false)

(declare * div + - < > <= >= max min bigdecimal numeric)

(defn bigdecimal? [v]
  #?(:clj  (decimal? v)
     :cljs (ct/bigdec? v)))

(defn numeric?
  "Predicate for clj(s) dynamic number (n or bigdecimal). Returns true if the given value is a numeric
  in the current computing context (primitive or BigDecimal)."
  [v]
  [any? => boolean?]
  (if *primitive*
    (number? v)
    (bigdecimal? v)))

(s/def ::numeric
  (s/with-gen numeric? #(s/gen #{(numeric "11.35")
                                 (numeric "5.00")
                                 (numeric "42.11")})))


#?(:clj
   (defmacro with-primitive-ops
     "Creates a thread-safe dynamic context where the math operations from this namespace
     use primitives instead of BigDecimal for speed."
     [& body]
     `(binding [*primitive* true]
        ~@body)))

(defn strip-zeroes [s]
  #?(:clj  s
     :cljs (-> s
             (str/replace #"^0+([1-9].*)$" "$1")
             (str/replace #"^0*([.].*)$" "0$1"))))

(>defn numeric->str
  "Convert a math number to a string."
  [bd]
  [(? ::numeric) => string?]
  (if bd
    #?(:cljs (if (ct/bigdec? bd)
               (or (some-> ^js bd .-rep) "0")
               (str bd))
       :clj  (str bd))
    ""))

(defn numeric
  "Coerce to a numeric from an arbitrary type (number, string, or numeric)."
  [s]
  [any? => ::numeric]
  (cond
    (nil? s) (numeric 0.0)
    (and *primitive* (bigdecimal? s)) #?(:cljs (js/parseFloat (.-rep ^js s)) :clj (Double/parseDouble (str s)))
    (and *primitive* (string? s) (empty? s)) (numeric "0")
    *primitive* #?(:cljs (js/parseFloat (str s)) :clj (Double/parseDouble (str s)))
    (numeric? s) s
    :else (let [s (if (seq (str s)) s "0")]
            #?(:clj  (new java.math.BigDecimal (.toString s))
               :cljs (ct/bigdec (strip-zeroes (.toString s)))))))

(defn positive?
  "Predicate for clj(s) positive bigdecimal"
  [v]
  [any? => boolean?]
  (< 0 (numeric v)))

(defn negative?
  "Predicate for clj(s) negative bigdecimal"
  [v]
  [any? => boolean?]
  (not (positive? v)))

(defn numeric->currency-str [n]
  #?(:clj
     (.format (NumberFormat/getCurrencyInstance (Locale. "en" "US")) (numeric n))
     :cljs
     (when n
       (let [n         (js/parseFloat (numeric->str (numeric n)))
             negative? (neg? n)
             n         (if negative? (clojure.core/* -1 n) n)
             result    (.toLocaleString n "en-US" #js {:style "currency" :currency "USD"})]
         (if negative?
           (str "-" result)
           result)))))

(defn numeric->percent-str [n]
  #?(:clj
     (let [formatter (NumberFormat/getPercentInstance (Locale. "en" "US"))]
       (.setMaximumFractionDigits formatter 3)
       (.format formatter (numeric n)))
     :cljs
     (when n
       (str (numeric->str (* n (numeric 100))) "%"))))

(defn- n->big
  "Convert a number-like thing into a low-level js Big representation."
  [n]
  (cond
    (numeric? n)
    #?(:clj  n
       :cljs (Big. (if (seq (numeric->str n))
                     (numeric->str n)
                     "0")))
    (and n (not= "" (.toString n)))
    #?(:clj  (numeric (.toString n))
       :cljs (Big. (.toString n)))
    :else-if-nil-or-empty-string
    #?(:clj  (numeric "0")
       :cljs (Big. "0"))))

(defn- big->bigdec
  "Convert a low-level js Big number into a bigdecimal."
  [n]
  #?(:clj  n
     :cljs (numeric (.toString n))))

(defn +
  "Add the given numbers together, coercing any that are not numeric."
  ([] (numeric "0"))
  ([& numbers]
   #?(:clj
      (if *primitive*
        (apply clojure.core/+ numbers)
        (apply clojure.core/+ (map numeric numbers)))
      :cljs
      (if *primitive*
        (apply cljs.core/+ numbers)
        (big->bigdec
          (reduce (fn [acc n]
                    (.plus ^js acc (n->big n)))
            (Big. "0")
            numbers))))))

(defn -
  "Subtract the given numbers, using bigdecimal math"
  [& numbers]
  #?(:clj
     (if *primitive*
       (apply clojure.core/- numbers)
       (apply clojure.core/- (map numeric numbers)))
     :cljs
     (if *primitive*
       (apply cljs.core/- numbers)
       (big->bigdec
         (if (clojure.core/= 1 (count numbers))
           (.times (n->big (first numbers)) (n->big -1))
           (reduce (fn [acc n]
                     (.minus ^js acc (n->big n)))
             (-> numbers first n->big)
             (rest numbers)))))))

(defn *
  "Multiply the given numbers, using bigdecimal math"
  ([] (numeric "1"))
  ([& numbers]
   #?(:clj
      (if *primitive*
        (apply clojure.core/* numbers)
        (apply clojure.core/* (map numeric numbers)))
      :cljs
      (if *primitive*
        (apply cljs.core/* numbers)
        (big->bigdec
          (reduce (fn [acc n]
                    (.times ^js acc (n->big n)))
            (Big. "1")
            numbers))))))

(def ^:dynamic *precision* 20)

(defn div
  "Divide the given two numbers, using bigdecimal math, with 20 digits
  of precision. In primitive mode just uses regular `/`."
  ([n d]
   (div n d *precision*))
  ([n d precision]
   (assert (not= 0 d))
   (if *primitive*
     (/ n d)
     (let [n (n->big n)
           d (n->big d)]
       #?(:clj
          (with-precision precision
            (/ n d))
          :cljs
          (do
            (set! Big/DP precision)
            (big->bigdec
              (.div n d))))))))

#?(:cljs
   (do
     (defn- big-eq [x y]
       (.eq (n->big x) (n->big y)))

     (defn- big-lt [x y]
       (.lt (n->big x) (n->big y)))

     (defn- big-lte [x y]
       (.lte (n->big x) (n->big y)))

     (defn- big-gt [x y]
       (.gt (n->big x) (n->big y)))

     (defn- big-gte [x y]
       (.gte (n->big x) (n->big y)))))

(defn compare-fn
  #?(:cljs ([big-fn]
            (fn [x y & more]
              (if (big-fn x y)
                (if (next more)
                  (recur y (first more) (next more))
                  (if (first more)
                    (big-fn y (first more))
                    true))
                false)))
     :clj  ([core-fn]
            (fn [& numbers]
              (apply core-fn (map n->big numbers))))))

(def = (compare-fn #?(:cljs (if *primitive* cljs.core/= big-eq) :clj clojure.core/=)))
(def < (compare-fn #?(:cljs (if *primitive* cljs.core/< big-lt) :clj clojure.core/<)))
(def <= (compare-fn #?(:cljs (if *primitive* cljs.core/<= big-lte) :clj clojure.core/<=)))
(def > (compare-fn #?(:cljs (if *primitive* cljs.core/> big-gt) :clj clojure.core/>)))
(def >= (compare-fn #?(:cljs (if *primitive* cljs.core/>= big-gte) :clj clojure.core/>=)))

(defn max [number & numbers]
  (reduce
    (fn [max-val n] (if (< max-val n) (numeric n) max-val))
    (numeric number)
    numbers))

(defn min [number & numbers]
  (reduce
    (fn [min-val n] (if (> min-val n) (numeric n) min-val))
    (numeric number)
    numbers))

(defn round
  "Round the given number to the given number of
  decimal digits. Returns a new bigdecimal number.

  n can be nil (returns 0), a numeric string, a regular number, or a bigdecimal."
  [n decimal-digits]
  (if *primitive*
    #?(:clj
       (double (.setScale (bigdec (n->big n)) decimal-digits RoundingMode/HALF_UP))
       :cljs
       (js/parseFloat (.toFixed (numeric n) decimal-digits)))
    (big->bigdec
      #?(:clj
         (.setScale (n->big n) decimal-digits RoundingMode/HALF_UP)
         :cljs
         (.toFixed (n->big n) decimal-digits)))))

(defn negative
  "If n is positive then returns n*(-1) else returns n."
  [n]
  (when n
    (let [n (numeric n)]
      (if (< 0 n)
        (* n (numeric -1))
        n))))

(defn positive
  "If n is negative then returns n*(-1) else returns n."
  [n]
  (when n
    (let [n (numeric n)]
      (if (< n 0)
        (* n (numeric -1))
        n))))

(defn zero [] (numeric 0))

;;; CLJ has a literal syntax for big decimals, CLJS does not.
#?(:cljs
   (extend-protocol IPrintWithWriter
     ty/TaggedValue (-pr-writer [d writer opts]
                      (let [t    (.-tag d)
                            v    (.-rep d)
                            type (case t
                                   "f" "bigdec"
                                   "n" "bigint"
                                   "tagged")]
                        (-write writer (str "#" type " \"" v "\""))))))

#?(:cljs
   (reader/register-tag-parser! 'math/bigdec bigdecimal))

(defn floor
  "Returns the floor of n, which is n with all decimal digits removed."
  [n]
  (let [v (str/replace (numeric->str (numeric n)) #"[.].*" "")]
    (numeric v)))
