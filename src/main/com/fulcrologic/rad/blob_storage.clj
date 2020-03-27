(ns com.fulcrologic.rad.blob-storage
  "The protocol and a sample implementation of binary large object storage. Blob storage is used by the file upload
  support to first place files in a temporary holding area, and then support moving them to a more permanent store
  if/when the form that refers to the file is saved.  Of course, storage can be persistent and just skip the
  transient bits if you don't need to track the files in some other kind of database."
  (:require [clojure.java.io :as jio]
            [taoensso.timbre :as log]
            [com.fulcrologic.rad.type-support.date-time :as dt])
  (:import
    (java.io File)))

(defprotocol Storage
  (blob-exists? [this name] "Returns true if the SHA already exists in the store")
  (save-blob! [this name input-stream] "Save the data from an InputStream as the given name. This function does not close the stream.")
  (blob-url [this name] "Return a global URL for the file content.")
  (delete-blob! [this name] "Delete the given thing from storage by name.")
  (blob-stream [this name] "Returns an open InputStream for the given blob with name. You must close it (use with-open).")
  (move-blob! [this name target-storage] "Move the blob with the given name from this store to a target store."))

(defn- clean-old-files!
  "Clean old temp files out of the store. Runs on new saves to clean up stuff that should no longer be stored."
  [store]
  (try
    (let [sha->file            (:sha->file store)
          tracked-shas         (set (keys @sha->file))
          oldest-creation-time (- (dt/now-ms) (* (:max-store-mins store) 60000))]
      (doseq [sha tracked-shas
              :let [f          (get @sha->file sha)
                    created-tm (.lastModified f)]]
        (when (< created-tm oldest-creation-time)
          (when (.exists f) (.delete f))
          (swap! sha->file dissoc name))))
    (catch Exception e
      (log/error e "Unable to clean up files."))))

(defrecord TransientBlobStore [sha->file base-url max-store-mins]
  Storage
  (save-blob! [this name input-stream]
    (let [f (java.io.File/createTempFile name "-upload")]
      (clean-old-files! this)
      (jio/copy input-stream f)
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
      (jio/make-input-stream file {})))
  (move-blob! [this name target-storage]
    (if-let [stream (blob-stream this name)]
      (with-open [in stream]
        (save-blob! target-storage name in)
        (delete-blob! this name))
      (log/error "Failed to move blob. No stream for " name))))

(defn transient-blob-store
  "Create a blob store that uses a map of entries to track file uploads and saves the actual files to local
  temp files on disk. The `max-store-mins` is a time (in minutes) after which blobs in this store will disappear.

  This store is useful as the temporary store for file uploads on forms that have not yet been saved. Be sure to set
  the time high enough that it won't clean up uploaded things that have not been put in the database yet. You might
  also think about adding auto-save to forms that have uploads.

  This store can also be used as a development-time store if you're ok with them disappearing on restarts.

  * `base-url` is the prefix to use for returning URLs for a blob in this store.
  * `max-store-mins` is the maximum time after which files will be removed from disk.

  NOTE: file cleanup happens on the next `save-blob!`. Restarting the server can cause some of the temporary files
  that were being tracked by this store to be left on disk."
  [base-url max-store-mins]
  (->TransientBlobStore (atom {}) base-url max-store-mins))

