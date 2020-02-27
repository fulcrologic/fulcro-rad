(ns com.fulcrologic.rad.blob-storage-spec
  (:require
    [fulcro-spec.core :refer [assertions specification component when-mocking
                              behavior]]
    [com.fulcrologic.rad.blob-storage :as bs]
    [clojure.java.io :as jio]
    [com.fulcrologic.rad.type-support.date-time :as dt]))

(declare =>)

(specification "Transient Blob Store"
  (component "Old file cleanup"
    (let [sha                     "123"
          cleanup!                #'bs/clean-old-files!
          real-now                (dt/now-ms)
          five-minutes-from-now   (+ real-now (* 5 60 1000))
          twelve-minutes-from-now (+ real-now (* 12 60 1000))
          data                    (byte-array [1 2 3])
          store                   (bs/transient-blob-store "/images" 10)]
      (with-open [input (jio/input-stream data)]
        (bs/save-blob! store sha input))

      (behavior "cleanup leaves young files in the store"
        (when-mocking
          (dt/now-ms) => five-minutes-from-now

          (cleanup! store))

        (assertions (bs/blob-exists? store sha) => true))

      (behavior "cleanup removes old files in the store"
        (when-mocking
          (dt/now-ms) => twelve-minutes-from-now

          (cleanup! store))

        (assertions (bs/blob-exists? store sha) => false))

      )))




