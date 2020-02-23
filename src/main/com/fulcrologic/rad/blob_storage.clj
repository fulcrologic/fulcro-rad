(ns com.fulcrologic.rad.blob-storage
  (:require [clojure.java.io :as jio]
            [taoensso.timbre :as log])
  (:import
    (java.io File)))

;:deposit/check-front ;; a SHA
;:deposit.check-front/url
;:deposit.check-front/mime-type
;:deposit.check-front/size

(defprotocol Storage
  (blob-exists? [this name] "Returns true if the SHA already exists in the store")
  (save-blob! [this name input-stream] "Save the data from an InputStream as the given name. This function does not close the stream.")
  (blob-url [this name] "Return a global URL for the file content.")
  (delete-blob! [this name] "Delete the given thing from storage by name.")
  (blob-stream [this name] "Returns an open InputStream for the given blob with name. You must close it (use with-open).")
  (move-blob! [this name target-storage] "Move the blob with the given name from this store to a target store."))

(defrecord LeakyBlobStore [sha->file base-url]
  Storage
  (save-blob! [this name input-stream]
    (let [f (java.io.File/createTempFile name "-upload")]
      (log/info "Copying stream to file" f)
      (jio/copy input-stream f)
      (log/info "resulting file size " (.length f))
      (swap! sha->file assoc name f)))
  (blob-url [this name] (str base-url "/" name))
  (delete-blob! [this name]
    (some->> (get @sha->file name) (.delete))
    (swap! sha->file dissoc name))
  (blob-exists? [this name]
    (boolean
      (when-let [file ^File (get @sha->file name)]
        (.exists file))))
  (blob-stream [_ name]
    (when-let [file (get @sha->file name)]
      (log/info "getting stream for" file)
      (jio/make-input-stream file {})))
  (move-blob! [this name target-storage]
    (if-let [stream (blob-stream this name)]
      (with-open [in stream]
        (log/info "Moving blob" name "to new store")
        (save-blob! target-storage name in)
        (delete-blob! this name))
      (log/error "Failed to move blob. No stream for " name))))

(defn leaky-blob-store
  "Create a dev-time blob store that uses an ever-growing map of entries to track file uploads. This leaks
  memory slowly over time (thus the name). Calling `delete-blob!` on this store will clean up the association
  and can be used to manually clear the leaks. This blob store is destroyed on code reload, though the files it
  tracks are not.

  * `base-url` is the prefix to use for returning URLs for a blob in this store."
  [base-url]
  (->LeakyBlobStore (atom {}) base-url))

