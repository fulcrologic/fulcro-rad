(ns com.fulcrologic.rad.blob
  "Support for dealing with binary large objects (e.g. file upload, storage, and retrieval of images, documents, etc.)

  To use this support you must:

  - Add wrap-persist-images from this ns to your form save middleware
  - Install the Fulcro Ring middleware `file-upload/wrap-mutation-file-uploads`.
  - Configure an HTTP remote on the client with `file-upload/wrap-file-upload` HTTP remote request middleware.
  - Add the and install the `upload-file` mutation from this ns into your pathom resolver.
  - Add `::blob/temporary-store` to your Pathom env.
  - Add a `::blob/stores` map to associate store names to Storage components to your Pathom env.
  - Configure attributes that will handle files. This support assumes some storage adapter will store the URL
  of the file uploaded, and the file data will go in some other store (e.g. S3, disk, etc.). So, you configure
  a to-one string attribute with:
  ** The form field style to use a renderer that supports file uploads.
  ** ::blob/store to indicate the identifer of an implementation of blob-storage/Storage. "
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
    [com.rpl.specter :as sp]
    [com.wsscode.pathom.connect :as pc]
    [clojure.core.async :as async]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.networking.file-upload :as file-upload]
    #?@(:cljs [[goog.crypt :as crypt]
               [com.fulcrologic.fulcro.networking.http-remote :as net]]
        :clj  [[com.fulcrologic.rad.blob-storage :as storage]
               [clojure.pprint :refer [pprint]]
               [clojure.java.io :as jio]])
    [com.wsscode.pathom.core :as p])
  (:import
    #?(:clj  (org.apache.commons.codec.digest DigestUtils)
       :cljs [goog.crypt Sha256])))

(defn file-sha256
  "Finds the SHA256 from the given Blob/File

  Returns an async channel that will eventually contain the hash or nil (if the input type was not understood)."
  [blob]
  #?(:clj
     (async/go
       (async/<!
         (async/thread
           (with-open [in (jio/input-stream (jio/as-file blob))]
             (DigestUtils/sha256Hex in)))))                 ;
     :cljs
     (let [c       (async/chan)
           digest  (fn [hasher bytes] (.update hasher bytes) (.digest hasher))
           handler (fn [evt]
                     (let [buffer (.. evt -target -result)
                           hash   (crypt/byteArrayToHex
                                    (digest (new Sha256) (new js/Uint8Array buffer)))]
                       (js/console.log buffer)
                       (async/go
                         (async/>! c hash))))]
       (when (instance? js/Blob blob)
         (let [reader (new js/FileReader)]
           (set! (.-onloadend reader) handler)
           (.readAsArrayBuffer reader blob)))
       (async/go
         (async/<! c)))))

(defn sha256
  "Finds the SHA256 of the given string-or-bytes.

  On the JVM the argument can be a string or a byte array.
  In CLJS the argument can be a low-level string, Uint8Array, ArrayBuffer.

  Returns the hash or nil (if the input type was not understood)."
  [string-or-bytes]
  #?(:clj
     (cond
       (string? string-or-bytes) (DigestUtils/sha256Hex (str string-or-bytes))
       (bytes? string-or-bytes) (DigestUtils/sha256Hex (bytes string-or-bytes))) ;
     :cljs
     (letfn [(digest [hasher bytes]
               (.update hasher bytes)
               (.digest hasher))]
       (cond
         (string? string-or-bytes)
         (crypt/byteArrayToHex
           (digest (new Sha256) (crypt/stringToByteArray string-or-bytes)))

         (= js/Uint8Array (type string-or-bytes))
         (crypt/byteArrayToHex (digest (new Sha256) string-or-bytes))

         (= js/ArrayBuffer (type string-or-bytes))
         (crypt/byteArrayToHex
           (digest (new Sha256) (new js/Uint8Array string-or-bytes)))))))

(defsc Blob [_ _]
  {:query       [:ui/uploading? :ui/percent-complete ::id ::local-filename ::file-sha fs/form-config-join]
   :form-fields #{::file-sha ::local-filename}
   :ident       ::id
   :pre-merge   (fn [{:keys [data-tree]}]
                  (merge {:ui/uploading?       false
                          :ui/percent-complete 0
                          ::local-filename     "file"}
                    data-tree))})

(def ui-blob (comp/factory Blob {:keyfn ::id}))

(m/declare-mutation upload-file `upload-file)

(defmethod m/mutate `upload-file [{:keys [ast]}]
  (let [{::keys      [id file-sha local-filename]
         ::attr/keys [qualified-key]
         :keys       [remote form-ident]} (get ast :params)
        remote-key (or remote :remote)]
    {:action          (fn action [{:keys [state]}]
                        (let [new-blob (fs/add-form-config
                                         Blob
                                         {::id             id
                                          ::local-filename local-filename
                                          ::file-sha       file-sha})]
                          (log/info "Upload starting")
                          (fns/swap!-> state
                            (merge/merge-component Blob new-blob :append (conj form-ident ::blobs))
                            (update-in [::id id] assoc :ui/uploading? true :ui/percent-complete 0)
                            (assoc-in (conj form-ident qualified-key) file-sha))))
     :progress-action (fn progress-action [{:keys [state] :as env}]
                        (log/info "Progress update")
                        #?(:cljs
                           (let [pct (net/overall-progress env)]
                             (swap! state assoc-in [::id id :ui/percent-complete] pct))))
     :result-action   (fn result-action [{:keys [state result]}]
                        ;; TODO: Error handling
                        (log/info "Upload complete" result)
                        (let [ok? (= 200 (:status-code result))]
                          (swap! state update-in [::id id] assoc
                            :ui/uploading? false
                            :ui/status (if ok? :ready :failed)
                            :ui/percent-complete 100)))
     remote-key       (fn remote [env] true)}))

(defn upload-file!
  "This adds a new Blob instance to the form, computes a SHA for the file, starts the upload (with progress tracking), and
  sets the form attribute to the SHA. The rendering layer will auto-detect when a file upload attribute is a SHA
  and can render the progress of the upload (possibly with a preview, etc.).

  The Blob itself will include the local filename and a tempid so it will be sent with form save (and remapped to real
  ID on completion).

  The server save middleware will receive the uploaded but unprocessed blobs on save, and must use the SHA to
  claim, relocate, and fix the form data."
  [{:com.fulcrologic.rad.form/keys [form-instance]} {::keys      [remote]
                                                     ::attr/keys [qualified-key]} js-file]
  #?(:cljs
     (async/go
       (let [sha      (async/<! (file-sha256 js-file))
             filename (or (.-name js-file) "file")
             blob-id  (tempid/tempid)
             uploads  [(file-upload/new-upload filename js-file)]]
         (comp/transact! form-instance
           [(upload-file (file-upload/attach-uploads
                           {:form-ident          (comp/get-ident form-instance)
                            :remote              (or remote :remote)
                            ::attr/qualified-key qualified-key
                            ::id                 blob-id
                            ::local-filename     filename
                            ::file-sha           sha}
                           uploads))]
           {:abort-id sha})))))

#?(:clj
   (pc/defmutation upload-file [{::keys [temporary-storage] :as env}
                                {::keys             [file-sha id local-filename]
                                 ::file-upload/keys [files] :as params}]
     {::pc/doc "Server-side handler for an uploaded file in the RAD Blob system"}
     (log/info "Received file" local-filename)
     (let [file (-> files first :tempfile)]
       (cond
         (nil? file) (log/error "No file was attached. Perhaps you forgot to install file upload middleware?")
         (nil? temporary-storage) (log/error "No blob storage. Perhaps you forgot to add ::blob/temporary-storage to your pathom env")
         :else (storage/save-blob! temporary-storage file-sha file)))
     {:tempids {id file-sha}}))

#?(:clj
   (defn wrap-persist-images
     "Form save middleware that examines the incoming transaction for Blobs and moves them from temporary storage into
     a permanent store based on attribute configuration. This middleware requires you've also installed the Fulcro
     Ring middleware `file-upload/wrap-mutation-file-uploads` and configured an HTTP remote on the client with
     `file-upload/wrap-file-upload` HTTP remote request middleware. You must also use a form field that supports file uploads
     and install the `upload-file` mutation from this ns into your pathom resolver list."
     [handler all-attributes]
     (let [blob-attributes (into {}
                             (keep (fn [{::keys      [store]
                                         ::attr/keys [qualified-key] :as attr}]
                                     (when store [qualified-key attr])))
                             all-attributes)
           blob-keys       (set (keys blob-attributes))]
       (log/info "Wrapping persist-images with image keys" blob-keys)
       (fn [save-env]
         (let [{:com.fulcrologic.rad.form/keys [pathom-env params]} save-env
               {::keys [temporary-store permanent-stores]} pathom-env
               handler-result (handler save-env)]
           (try
             (log/info "Check for files to persist in " params)
             (when-not temporary-store
               (log/error "No temporary storage in pathom env."))
             (when-not (map? permanent-stores)
               (log/error "No permanent file storage in pathom env. Cannot save file(s)."))
             (when-not (seq blob-keys)
               (log/warn "wrap-persist-images is installed in form middleware, but no attributes are marked to be stored as Blobs."))
             (pprint params)
             (let [delta        (:com.fulcrologic.rad.form/delta params)
                   blob-tempids (sp/select [sp/MAP-KEYS (sp/pred #(= ::id (first %))) sp/LAST tempid/tempid?] delta)
                   tid->rid     (zipmap blob-tempids (repeatedly #(java.util.UUID/randomUUID)))
                   pruned-delta (sp/transform [sp/MAP-VALS (sp/pred map?)] #(select-keys % blob-keys) delta)]
               (doseq [entity (vals pruned-delta)
                       [k {:keys [before after]}] entity
                       :let [{::keys [store]} (get blob-attributes k)
                             permanent-storage (get permanent-stores store)]]
                 ;; TODO: Not right...may have remapped name...need to extract SHA???
                 (when (and before (not= before after))
                   (storage/delete-blob! permanent-storage before))
                 (when after
                   (log/info "Moving file to permanent storage" after)
                   (storage/move-blob! temporary-store after permanent-storage)))
               (deep-merge {:tempids tid->rid} handler-result))
             (catch Exception e
               (log/error e "Failed to process file(s).")
               handler-result)))))))

#?(:clj
   (defn pathom-plugin
     "A pathom plugin to configure blob stores.

     - temporary-store: A Storage object that is used to track temporary files between upload and final form save.
     - permanent-stores: A map from store name (keyword) to Storage objects that act as the permanent location for the
     file data."
     [temporary-store permanent-stores]
     (p/env-wrap-plugin
       (fn [env]
         (assoc env
           ::temporary-store temporary-store
           ::permanent-stores permanent-stores)))))

#?(:clj
   (def resolvers [upload-file]))
