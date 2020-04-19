(ns com.fulcrologic.rad.type-support.cache-a-bools
  "Support for booleans that include information about their ability to be cached. This allows
   predicate functions in performance-sensitive areas to indicate that their answer is stable,
   allowing the code to cache the answer and avoid future calls."
  #?(:cljs (:require-macros
             [com.fulcrologic.rad.type-support.cache-a-bools
              :refer [True? False? as-boolean And cacheable?]]))
  (:require
    [taoensso.timbre :as log]))

(def cachably-true ::cacheably-true)
(def CT cachably-true)
(def cachably-false ::cachably-false)
(def CF cachably-false)
(def uncachably-true ::uncachably-true)
(def UT uncachably-true)
(def uncachably-false ::uncachably-false)
(def UF uncachably-false)

; #?(:clj (defmacro cacheable? [v] `(or (= v CF) (= v CT))))

#?(:clj
   (defmacro cacheable? [a] `(or (= ~a CF) (= ~a CT))))

#?(:clj (defmacro True? [b] `(or (true? ~b) (= CT ~b) (= UT ~b))))

#?(:clj (defmacro False? [b] `(or (false? ~b) (= CF ~b) (= UF ~b))))

#?(:clj
   (defmacro as-boolean [b] `(case ~b
                               ~CT true
                               ~UT true
                               ~CF false
                               ~UF false
                               true true
                               false false
                               nil false
                               true)))

#?(:clj
   (defmacro And
     "Returns a Cache-a-bool result with short-circuiting like `and`. The result will be marked as cacheable if
      all of the elements evaluated were cacheable."
     ([] UT)
     ([a] a)
     ([a & next]
      `(let [evaluated-a# ~a]
         (if (True? evaluated-a#)
           (let [evaluated-rest# (And ~@next)
                 ac#             (cacheable? evaluated-a#)
                 bc#             (cacheable? evaluated-rest#)
                 cacheable#      (and ac# bc#)]
             (cond
               (True? evaluated-rest#) (if cacheable# CT (if bc# UT evaluated-rest#))
               (False? evaluated-rest#) (if cacheable# CF (if bc# UF evaluated-rest#))
               :else evaluated-rest#))
           (if (cacheable? evaluated-a#) CF (and evaluated-a# ~@next)))))))

#?(:clj
   (defmacro Or
     "Returns a Cache-a-bool result with short-circuiting like `or`. The result will be marked as cacheable if
      all of the elements evaluated were cacheable."
     ([] nil)
     ([a] a)
     ([a & next]
      `(let [evaluated-a# ~a]
         (cond
           (True? evaluated-a#) (if (cacheable? evaluated-a#) CT UT)
           (False? evaluated-a#) (let [evaluated-rest# (Or ~@next)
                                       cacheable#      (and (cacheable? evaluated-a#) (cacheable? evaluated-rest#))]
                                   (cond
                                     (True? evaluated-rest#) (if cacheable# CT UT)
                                     (False? evaluated-rest#) (if cacheable# CF UF)
                                     :else evaluated-rest#))
           :else
           (or evaluated-a# ~@next))))))

(defn Not [a]
  (cond
    (true? a) UF
    (false? a) UT
    (and (True? a)) (if (cacheable? a) CF UF)
    (and (False? a)) (if (cacheable? a) CT UT)))

