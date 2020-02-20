(ns com.fulcrologic.rad.blob-storage
  (:import
    (java.io File)))

(defprotocol Storage
  (save-blob! [this name file] "Save the given file under the given name.")
  (blob-url! [this name] "Optionally return a global URL to access the file content.")
  (delete-blob! [this name] "Delete the given thing from storage by name.")
  (blob-file! [this name] "Return the given blob with name as a File.")
  (move-blob! [this name target-storage] "Move the blob with the given name from this store to a target store."))

(defrecord LeakyBlobStore [sha->file]
  Storage
  (save-blob! [this name file] (swap! sha->file assoc name file))
  (blob-url! [this name] nil)
  (delete-blob! [this name]
    (some->> name (blob-file! this) (.delete))
    (swap! sha->file dissoc name))
  (blob-file! [_ name] (get @sha->file name))
  (move-blob! [this name target-storage]
    (some->> (blob-file! this name) (save-blob! target-storage name))
    (delete-blob! this name)))

(defn leaky-blob-store
  "Create a dev-time blob store that uses an ever-growing map of entries to track file uploads. This leaks
  memory slowly over time (thus the name). Calling `delete-blob!` on this store will clean up the association
  and can be used to manually clear the leaks. This blob store is destroyed on code reload, though the files it
  tracks are not."
  []
  (->LeakyBlobStore (atom {})))

