(ns com.fulcrologic.rad.type-support.cache-a-bools-spec
  (:require
    [com.fulcrologic.rad.type-support.cache-a-bools :as cb :refer [And Or Not True? False? as-boolean]]
    [taoensso.timbre :as log]
    [fulcro-spec.core :refer [specification assertions component behavior]]))

(declare =>)

(let [ct cb/cachably-true
      cf cb/cachably-false
      ut cb/uncachably-true
      uf cb/uncachably-false]
  (specification "Cachable booleans"
    (component "Interop with raw booleans"
      (behavior "Cache behavior"
        (assertions
          "Inclusion of a raw boolean always results in a non-cacheable result"
          (cb/cacheable? true) => false
          (cb/cacheable? false) => false
          (cb/cacheable? nil) => false
          (cb/cacheable? 42) => false
          (cb/cacheable? (And true ct)) => false
          (cb/cacheable? (And ct true)) => false)))
    (component "Basic use as booleans"
      (assertions
        "True? on cache-a-bools"
        (True? ct) => true
        (True? ut) => true
        (True? cf) => false
        (True? uf) => false)
      (assertions
        "True? on real booleans"
        (True? true) => true
        (True? false) => false)
      (assertions "as-boolean on raw data types"
        (identical? true (as-boolean true)) => true
        (identical? false (as-boolean false)) => true
        (identical? false (as-boolean nil)) => true
        (identical? true (as-boolean 0)) => true)
      (assertions
        "as-boolean on cache-a-bools (macro)"
        (identical? true (as-boolean ct)) => true
        (identical? true (as-boolean ut)) => true
        (identical? false (as-boolean cf)) => true
        (identical? false (as-boolean uf)) => true))
    (component "And"
      (component "On raw types"
        (assertions
          (And true false) => false
          (And false true) => false
          (And true true) => true
          (And 1 "hello") => "hello"))
      (component "Truth table"
        (assertions
          (False? (And cf uf)) => true
          (False? (And cf ct)) => true
          (False? (And ct cf)) => true
          (False? (And ut uf)) => true
          (False? (And ut uf)) => true
          (True? (And ct ct ct ut ut ct)) => true
          (False? (And ct cf ct ut ut ct)) => true))
      (component "Cachability"
        (assertions
          "A boolean true answer is uncacheable if any element in not cacheable."
          (cb/cacheable? (And ut ct ct)) => false
          (cb/cacheable? (And ct ut ct)) => false
          (cb/cacheable? (And ct ct ut)) => false
          "A short-circuited answer is cacheable based on the cacheability of the elements that were evaluated"
          (cb/cacheable? (And ct ct cf)) => true
          (cb/cacheable? (And ct cf ct)) => true
          (cb/cacheable? (And ct ct uf)) => false
          (cb/cacheable? (And ct cf uf)) => true
          (cb/cacheable? (And ct cf ut uf)) => true)))
    (component "Or"
      (component "Behavior with non CAB types"
        (assertions
          "With no args returns nil"
          (Or) => nil
          "Returns truthy raw values"
          (Or 1) => 1
          (Or nil "a") => "a"
          (Or false "hello") => "hello"
          (Or cf "hello") => "hello"
          (Or ct nil) => ct
          (Or cf cf "a") => "a"))
      (component "Truth table"
        (assertions
          (nil? (Or)) => true
          (False? (Or uf)) => true
          (False? (Or cf cf cf cf)) => true
          (False? (Or uf cf uf cf)) => true
          (True? (Or cf ct)) => true
          (True? (Or ct cf)) => true
          (True? (Or ut uf)) => true
          (True? (Or ut uf)) => true
          (True? (Or ct ct ct ut ut ct)) => true
          (True? (Or ct cf ct ut ut ct)) => true))
      (component "Cachability"
        (assertions
          "A boolean true answer is uncacheable if any evaluated element in not cacheable."
          (cb/cacheable? (Or ut ct ct)) => false
          (cb/cacheable? (Or cf ut ct)) => false
          (cb/cacheable? (Or cf cf ut)) => false
          "A short-circuited answer is cacheable based on the cacheability of the elements that were evaluated"
          (cb/cacheable? (Or ct ut uf)) => true
          (cb/cacheable? (Or uf ct ut)) => false
          (cb/cacheable? (Or cf cf ut)) => false
          (cb/cacheable? (Or)) => false
          (cb/cacheable? (Or true)) => false
          (cb/cacheable? (Or 32)) => false)))))
