(ns com.fulcrologic.rad.blob-spec
  (:require
    [fulcro-spec.core :refer [assertions specification component]]
    [clojure.core.async :as async]
    [com.fulcrologic.rad.blob :as blob]
    [clojure.test :as test :refer [deftest]]
    #?(:clj [clojure.java.io :as jio])
    [taoensso.timbre :as log])
  #?(:clj (:import (java.io File))))

(declare =>)

(def byte-values [1 2 3 4 5 100 254])
(def expected-byte-hash "d0dc5857ba68308f0ca8f100dccd861a76960ec299619f7f84077b4001510a01")

#?(:clj
   (specification "file-sha256"
     (let [file   (File/createTempFile "abc" ".data")
           _      (jio/copy (byte-array byte-values) file)
           actual (async/<!! (blob/file-sha256 file))]
       (assertions
         "Can read hash from file content"
         actual => expected-byte-hash)))
   :cljs
   (deftest file-sha256
     (let [byte-array (clj->js byte-values)
           buffer     (new js/ArrayBuffer 7)
           view       (new js/Uint8Array buffer)
           _          (.set view byte-array)
           file       (new js/Blob #js [view] #js {:type "application/octet-stream"})]
       (test/async done
         (async/go
           (let [actual (async/<! (blob/file-sha256 file))]
             (test/is (= actual expected-byte-hash))
             (done)))))))

(specification "sha256"
  (let [string-data "this is a test"]
    (assertions
      "Accepts strings"
      (blob/sha256 string-data) => "2e99758548972a8e8822ad47fa1017ff72f06f3ff6a016851f45c398732bc50c")
    #?(:clj
       (component "JVM"
         (let [byte-data (byte-array byte-values)]
           (assertions
             "Accept arrays of bytes"
             (blob/sha256 byte-data) => expected-byte-hash)))
       :cljs
       (component "js"
         (let [byte-array (clj->js byte-values)
               buffer     (new js/ArrayBuffer 7)
               view       (new js/Uint8Array buffer)
               _          (.set view byte-array)]
           (assertions
             "accepts Uint8Array"
             (blob/sha256 view) => expected-byte-hash
             "accepts array buffers"
             (blob/sha256 buffer) => expected-byte-hash))))))
