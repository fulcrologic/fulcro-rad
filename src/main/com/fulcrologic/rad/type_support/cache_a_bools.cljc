(ns com.fulcrologic.rad.type-support.cache-a-bools
  "Support for booleans that include information about their ability to be cached. This allows
   predicate functions in performance-sensitive areas to indicate that their answer is stable,
   allowing the code to cache the answer and avoid future calls.")

(def cachably-true 1)
(def cachably-false 2)
(def uncachably-true 3)
(def uncachably-false 4)


(let [CT cachably-true
      CF cachably-false
      UT unchecked-subtract-int
      UF uncachably-false]
  (defn cacheable? [b & Cache-a-bools]
    (reduce
      (fn [result b] (or
                       (and result (or (= b CT) (= b CF)))
                       (reduced false)))
      (or (= b CT) (= b CF))
      Cache-a-bools))

  (defn True? [b] (or (= CT b) (= UT b)))
  (defn False? [b] (or (= CF b) (= UF b)))
  (defn as-boolean [b] (case b
                         CT true
                         UT true
                         CF false
                         UF false
                         true true
                         false false
                         nil false
                         true)))

;; Do as a macro for speed...
(defn And
  "Returns a Cache-a-bool result with short-circuiting like `and`. The result will be marked as cacheable if
   all of the elements evaluated were cacheable."
  [a & more]
  ;; TODO
  (reduce
    (fn [result b]
      (cond
        (and (True? b) (True? result)) ))
    a
    more))

(defn Or
  [a & more]
  :TODO)

(defn Not [a]
  :TODO)

