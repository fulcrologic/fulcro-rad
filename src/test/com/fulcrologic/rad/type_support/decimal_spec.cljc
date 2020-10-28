(ns com.fulcrologic.rad.type-support.decimal-spec
  (:refer-clojure :exclude [name])
  (:require
    [fulcro-spec.core :refer [specification assertions component]]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [cognitect.transit :as ct]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(declare =>)

(defn is-x-times-faster? [x fast-version slow-version]
  (let [atm       (inst-ms (datetime/now))
        _         (fast-version)
        btm       (inst-ms (datetime/now))
        _         (slow-version)
        ctm       (inst-ms (datetime/now))
        fast-time (double (- btm atm))
        slow-time (double (- ctm btm))
        ratio     (/ slow-time fast-time)]
    (> ratio x)))

(specification "numeric constructor"
  (component "Primitive Mode"
    (math/with-primitive-ops
      (assertions
        "converts bigdecimal values to numerics"
        (math/numeric #?(:clj 11M :cljs (ct/bigdec "11"))) => 11.0
        "converts nil to 0"
        (math/numeric nil) => 0.0
        "Uses primitive types"
        (math/numeric nil) => 0.0
        (math/numeric "42") => 42.0
        "converts \"\" to 0"
        (math/numeric "") => 0.0
        "string, int, float input results in same value"
        (math/numeric 1.0) => (math/numeric "1")
        (math/numeric 1.2) => (math/numeric "1.2"))))
  (component "Normal mode"
    #?(:clj
       (assertions
         "converts nil to 0M"
         (math/numeric nil) => 0M
         "converts \"\" to 0M"
         (math/numeric "") => 0M
         "Leaves bigdec alone"
         (math/numeric 42M) => 42M
         "Uses bigdecimal types"
         (decimal? (math/numeric nil)) => true
         (decimal? (math/numeric "42")) => true
         "string, int, float input results in same value"
         (math/numeric 1) => (math/numeric "1")
         (math/numeric 1.2) => (math/numeric "1.2"))
       :cljs
       (assertions
         "converts nil to 0M"
         (math/numeric nil) => (ct/bigdec "0")
         "converts \"\" to 0M"
         (math/numeric "") => (ct/bigdec "0")
         "Uses bigdecimal types"
         (ct/bigdec? (math/numeric nil)) => true
         (ct/bigdec? (math/numeric "42")) => true
         "Leaves bigdec alone"
         (math/numeric (ct/bigdec "4")) => (ct/bigdec "4")
         "string, int, float input results in same value"
         (math/numeric 1) => (math/numeric "1")
         (math/numeric 1.2) => (math/numeric "1.2")))))

(specification "number->str"
  (component "Normal mode"
    (assertions
      "gives a string version of a number"
      (math/numeric->str (math/numeric "1.334")) => "1.334"))
  (component "Primitive mode"
    (math/with-primitive-ops
      (assertions
        "gives a string version of a number"
        (math/numeric->str (math/numeric "1.334")) => "1.334"))))

(specification "positive?" :focus
  (component "Normal mode"
    (assertions
      "Detects positive numbers"
      (math/positive? (math/numeric "0")) => false
      (math/positive? (math/numeric "0.00001")) => true
      (math/positive? (math/numeric "1.334")) => true
      (math/positive? (math/numeric "-1.334")) => false
      "Coerces raw values"
      (math/positive? -4) => false
      (math/positive? 4) => true
      (math/positive? "4") => true))
  (component "Primitive mode"
    (math/with-primitive-ops
      (assertions
        "Detects positive numbers"
        (math/positive? (math/numeric "0")) => false
        (math/positive? (math/numeric "0.00001")) => true
        (math/positive? (math/numeric "1.334")) => true
        (math/positive? (math/numeric "-1.334")) => false
        "Coerces raw values"
        (math/positive? -4) => false
        (math/positive? 4) => true
        (math/positive? "4") => true))))

(specification "Basic Math"
  (assertions
    "Primitive mode is at least 5x faster than normal mode"
    (is-x-times-faster? 5
      (fn []
        (math/with-primitive-ops
          (doseq [n (range 1000)]
            (-> n
              (math/+ 5)
              (math/* 55)
              (math/- 4 5 6)
              (math/div 11.45)))))
      (fn []
        (doseq [n (range 1000)]
          (-> n
            (math/+ 5)
            (math/* 55)
            (math/- 4 5 6)
            (math/div 11.45))))) => true)
  (component "Normal mode"
    (assertions
      "regular + math cannot represent certain (common) numbers"
      (not= 0.3 (+ 0.1 0.2)) => true
      "m/+ overcomes accuracy problems."
      (math/+ 0.1 0.2) => (math/numeric "0.3")
      (math/+ "0.1" "0.2") => (math/numeric "0.3")
      "regular - math cannot represent certain (common) numbers"
      (not= (- 0.3 0.1) 0.2) => true
      "m/- overcomes accuracy problems."
      (math/- 0.3 "0.1") => (math/numeric "0.2")

      "passing in a single number matches clojure.core behavior"
      (math/+ 0.2) => (math/numeric "0.2")
      (math/- 0.2) => (math/numeric "-0.2")
      (math/* 0.2) => (math/numeric "0.2")

      "passing in no numbers matches clojure.core behavior"
      (math/+) => (math/numeric "0")
      (math/*) => (math/numeric "1")

      "regular * has accuracy problems"
      (not= 0.02 (* 0.1 0.2)) => true
      "m/* overcomes accuracy problems"
      (math/* 0.1 0.2) => (math/numeric "0.02")

      "regular / is inaccurate."
      (not= 3 (/ 0.3 0.1)) => true
      "m/div compensates"
      (math/div 0.3 0.1) => (math/numeric "3")))
  (component "Primitive mode"
    (math/with-primitive-ops
      (assertions
        "Is identical to primitive operations"
        (math/+ (math/numeric #?(:cljs (ct/bigdec "0.1")
                                 :clj  0.1M)) (math/numeric 0.2)) => (+ 0.1 0.2)
        (math/- (math/numeric 0.1) 0.2) => (- 0.1 0.2)
        (math/div 0.1 0.2) => (/ 0.1 0.2)
        (math/* 0.1 (math/numeric 0.2)) => (* 0.1 0.2)))))

(specification "Comparison Operators"
  (component "Normal mode"
    (assertions
      "Allows raw (coerced) and mixed values"
      (math/= 1 2 3) => false
      (math/= 2 2 2) => true
      (math/> 3 1 -1) => true
      (math/> 1 3 -1) => false
      (math/>= (math/numeric 3) 3 "1" -1) => true
      (math/>= (math/numeric 3) "1" 3 -1) => false
      (math/< 1 "2" 5) => true
      (math/< "2" 1 5) => false
      (math/<= 1 1 2 (math/numeric 5)) => true
      (math/<= 1 2 1 (math/numeric 5)) => false)
    (assertions
      "handles = comparisons"
      (math/= (math/numeric "1") (math/numeric "1") (math/numeric "1")) => true
      (math/= (math/numeric "1") (math/numeric "1") (math/numeric "7")) => false)
    (assertions
      "handles < comparisons"
      (math/< (math/numeric "1") (math/numeric "5") (math/numeric "7")) => true
      (math/< (math/numeric "1") (math/numeric "8") (math/numeric "7")) => false
      (math/< (math/numeric "1") (math/numeric "1") (math/numeric "7")) => false)
    (assertions
      "handles <= comparisons"
      (math/<= (math/numeric "1") (math/numeric "5") (math/numeric "7")) => true
      (math/<= (math/numeric "1") (math/numeric "1") (math/numeric "7")) => true)
    (assertions
      "handles > comparisons"
      (math/> (math/numeric "1") (math/numeric "5") (math/numeric "7")) => false
      (math/> (math/numeric "9") (math/numeric "8") (math/numeric "7")) => true
      (math/> (math/numeric "1") (math/numeric "1") (math/numeric "7")) => false)
    (assertions
      "handles >= comparisons"
      (math/>= (math/numeric "7") (math/numeric "5") (math/numeric "1")) => true
      (math/>= (math/numeric "9") (math/numeric "9") (math/numeric "7")) => true))
  (component "Primitive mode"
    (math/with-primitive-ops
      (assertions
        "Allows raw (coerced) and mixed values"
        (math/= 1 2 3) => false
        (math/= 2 2 2) => true
        (math/> 3 1 -1) => true
        (math/> 1 3 -1) => false
        (math/>= (math/numeric 3) 3 "1" -1) => true
        (math/>= (math/numeric 3) "1" 3 -1) => false
        (math/< 1 "2" 5) => true
        (math/< "2" 1 5) => false
        (math/<= 1 1 2 (math/numeric 5)) => true
        (math/<= 1 2 1 (math/numeric 5)) => false)
      (assertions
        "handles = comparisons"
        (math/= (math/numeric "1") (math/numeric "1") (math/numeric "1")) => true
        (math/= (math/numeric "1") (math/numeric "1") (math/numeric "7")) => false)
      (assertions
        "handles < comparisons"
        (math/< (math/numeric "1") (math/numeric "5") (math/numeric "7")) => true
        (math/< (math/numeric "1") (math/numeric "8") (math/numeric "7")) => false
        (math/< (math/numeric "1") (math/numeric "1") (math/numeric "7")) => false)
      (assertions
        "handles <= comparisons"
        (math/<= (math/numeric "1") (math/numeric "5") (math/numeric "7")) => true
        (math/<= (math/numeric "1") (math/numeric "1") (math/numeric "7")) => true)
      (assertions
        "handles > comparisons"
        (math/> (math/numeric "1") (math/numeric "5") (math/numeric "7")) => false
        (math/> (math/numeric "9") (math/numeric "8") (math/numeric "7")) => true
        (math/> (math/numeric "1") (math/numeric "1") (math/numeric "7")) => false)
      (assertions
        "handles >= comparisons"
        (math/>= (math/numeric "7") (math/numeric "5") (math/numeric "1")) => true
        (math/>= (math/numeric "9") (math/numeric "9") (math/numeric "7")) => true))))

(specification "round"
  (component "Big mode"
    (assertions
      "round on regular number input"
      (math/round 3.145678 2) => (math/numeric "3.15")
      "round works on bigdecimal input"
      (math/round (math/numeric "9.2876487654987654923427862359876") 6) => (math/numeric "9.287649")
      "round on nil gives zero"
      (math/round nil 2) => (math/numeric "0")
      "round of empty string gives zero"
      (math/round "" 2) => (math/numeric "0")
      "round of numeric string works"
      (math/round "100.2222" 2) => (math/numeric "100.22"))
    (component "rounding mode"
      (assertions
        "defaults to :half-up"
        (math/round 1.255 2) => (math/numeric 1.26)
        (math/round 1.355 2) => (math/numeric 1.36)
        "supports :down"
        (math/round 1.255 2 :down) => (math/numeric 1.25)
        (math/round 1.355 2 :down) => (math/numeric 1.35)
        "supports :up"
        (math/round 1.251 2 :up) => (math/numeric 1.26)
        (math/round 1.351 2 :up) => (math/numeric 1.36)
        "supports :half-even"
        (math/round 5.5 0 :half-even) => (math/numeric 6M)
        (math/round 2.5 0 :half-even) => (math/numeric 2M))))
  (component "Primitive mode"
    (math/with-primitive-ops
      (assertions
        "round on regular number input"
        (math/round 3.145678 2) => (math/numeric "3.15")
        "round works on bigdecimal input"
        (math/round (math/numeric "9.2876487654987654923427862359876") 6) => (math/numeric "9.287649")

        "round on nil gives zero"
        (math/round nil 2) => #?(:clj (math/numeric "0.00")
                                 :cljs (math/numeric "0") )
        "round of empty string gives zero"
        (math/round "" 2) => (math/numeric "0.00")
        "round of numeric string works"
        (math/round "100.2222" 2) => (math/numeric "100.22"))
      (component "rounding mode"
        (assertions
          "defaults to :half-up"
          (math/round 1.255 2) => 1.26
          (math/round 1.355 2) => 1.36
          "supports :down"
          (math/round 1.255 2 :down) => 1.25
          (math/round 1.355 2 :down) => 1.35
          "supports :up"
          (math/round 1.251 2 :up) => 1.26
          (math/round 1.351 2 :up) => 1.36
          "supports :half-even"
          (math/round 5.5 0 :half-even) => 6.0
          (math/round 2.5 0 :half-even) => 2.0)))))

(specification "numeric?" :focus
  #?(:clj
     (assertions
       "Detects clj big decimal constants"
       (math/numeric? 1M) => true))
  (assertions
    "Works in primitive mode"
    (math/with-primitive-ops
      (-> (math/numeric "1.334") math/numeric?)) => true
    "Indicates when a number is numeric"
    (-> (math/numeric "1.334") math/numeric?) => true))

#?(:cljs
   (specification "strip-zeroes"
     (assertions
       "Strips zeros from normal numbers"
       (math/strip-zeroes "099") => "99"
       "Leaves the proper leading zeros on decimal numbers"
       (math/strip-zeroes "0.11") => "0.11"
       "Removes extra leading zeroes"
       (math/strip-zeroes "000.11") => "0.11"
       (math/strip-zeroes "0000099.22") => "99.22")))

(specification "min"
  (assertions
    "returns the minimum of numbers as the smallest bignum"
    (math/min 1 2 3) => (math/numeric 1)
    "can be mixed values"
    (math/min 1 "2" 3 (math/numeric -99)) => (math/numeric -99)
    "single values are returned as the minimum"
    (math/min 1) => (math/numeric 1)))

(specification "max"
  (assertions
    "returns the maximum of numbers as the smallest bignum"
    (math/max 1 2 3) => (math/numeric 3)
    "can be mixed values"
    (math/max 1 "2" 3 (math/numeric -99)) => (math/numeric 3)
    "single values are returned as the max"
    (math/max 1) => (math/numeric 1)))

(specification "floor"
  (assertions
    "Drops decimals from a coerced numeric"
    (math/floor 3.44) => (math/numeric 3)
    "Drops decimals from an existing numeric"
    (math/floor (math/numeric "3.44")) => (math/numeric 3)
    "Accepts integers"
    (math/floor 22) => (math/numeric 22)))
