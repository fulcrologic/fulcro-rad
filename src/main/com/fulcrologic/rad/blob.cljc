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
  #?(:cljs (:require-macros com.fulcrologic.rad.blob))
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.options-util :refer [narrow-keyword]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [deep-merge]]
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
    [com.wsscode.pathom.core :as p]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.application :as app])
  (:import
    #?(:clj  (org.apache.commons.codec.digest DigestUtils)
       :cljs [goog.crypt Sha256])))

(defn url-key [k] (narrow-keyword k "url"))
(defn progress-key [k] (narrow-keyword k "progress"))
(defn status-key [k] (narrow-keyword k "status"))
(defn filename-key [k] (narrow-keyword k "filename"))
(defn size-key [k] (narrow-keyword k "size"))

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
  (let [{::keys      [file-sha filename]
         ::attr/keys [qualified-key]
         :keys       [remote file-ident]} (get ast :params)
        remote-key (or remote :remote)]
    (let [name-path     (conj file-ident (filename-key qualified-key))
          status-path   (conj file-ident (status-key qualified-key))
          progress-path (conj file-ident (progress-key qualified-key))]
      {:action          (fn progress-action [{:keys [state] :as env}]
                          #?(:cljs
                             (fns/swap!-> state
                               (assoc-in (conj file-ident qualified-key) file-sha)
                               (assoc-in name-path filename)
                               (assoc-in progress-path 0)
                               (assoc-in status-path :uploading))))
       :progress-action (fn progress-action [{:keys [state] :as env}]
                          #?(:cljs
                             (let [pct (net/overall-progress env)]
                               (log/debug "Progress update" pct)
                               (swap! state assoc-in progress-path pct))))
       :result-action   (fn result-action [{:keys [state result]}]
                          ;; TODO: Error handling
                          (log/debug "Upload complete" result)
                          (let [ok? (= 200 (:status-code result))]
                            (fns/swap!-> state
                              (assoc-in status-path (if ok? :available :failed))
                              (assoc-in progress-path (if ok? 100 0)))))
       remote-key       (fn remote [env] true)})))

(defn upload-file!
  "This computes a SHA for the js-file, starts the upload (with progress tracking), and
  sets the form attribute to the SHA. The narrowed attributes (e.g. :file.sha/progress) will be updated as the file
  upload progresses. The rendering layer will auto-detect when a file upload attribute is a SHA
  and can render the progress of the upload (possibly with a preview, etc.).

  The upload can be aborted using the SHA."
  [form-instance {::keys      [remote]
                  ::attr/keys [qualified-key]} js-file {:keys [file-ident]}]
  #?(:cljs
     (async/go
       (let [sha      (async/<! (file-sha256 js-file))
             filename (or (.-name js-file) "file")
             uploads  [(file-upload/new-upload filename js-file)]]
         (comp/transact! form-instance
           [(upload-file (file-upload/attach-uploads
                           {:file-ident          file-ident
                            :remote              (or remote :remote)
                            ::attr/qualified-key qualified-key
                            ::filename           filename
                            ::file-sha           sha}
                           uploads))]
           {:abort-id sha})))))

#?(:clj
   (pc/defmutation upload-file [{::keys [temporary-store] :as env}
                                {::keys             [file-sha id local-filename]
                                 ::file-upload/keys [files] :as params}]
     {::pc/doc "Server-side handler for an uploaded file in the RAD Blob system"}
     (log/debug "Received file" local-filename)
     (let [file (-> files first :tempfile)]
       (cond
         (nil? file) (log/error "No file was attached. Perhaps you forgot to install file upload middleware?")
         (nil? temporary-store) (log/error "No blob storage. Perhaps you forgot to add ::blob/temporary-storage to your pathom env")
         :else (storage/save-blob! temporary-store file-sha file)))
     {}))

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
       (fn [pathom-env]
         (let [{:com.fulcrologic.rad.form/keys [params]
                ::keys                         [temporary-store permanent-stores]} pathom-env
               handler-result (handler pathom-env)]
           (log/debug "Check for files to persist in " params)
           (when-not temporary-store
             (log/error "No temporary storage in pathom env."))
           (when-not (map? permanent-stores)
             (log/error "No permanent file storage in pathom env. Cannot save file(s)."))
           (when-not (seq blob-keys)
             (log/warn "wrap-persist-images is installed in form middleware, but no attributes are marked to be stored as Blobs."))
           (let [delta        (:com.fulcrologic.rad.form/delta params)
                 pruned-delta (reduce-kv
                                (fn [result k v]
                                  (let [v (if (map? v) (select-keys v blob-keys) v)]
                                    (assoc result k v)))
                                {}
                                delta)]
             (doseq [entity (vals pruned-delta)
                     [k {:keys [before after]}] entity
                     :let [{::keys [store]} (get blob-attributes k)
                           permanent-storage (get permanent-stores store)]]
               (when-not permanent-storage
                 (log/error "Cannot find permanent store" store))
               ;; TODO: Not right...may have remapped name...need to extract SHA???
               (when (and permanent-storage before (not= before after))
                 (try
                   (storage/delete-blob! permanent-storage before)
                   (catch Exception _
                     (log/error "Delete failed."))))
               (when (and temporary-store permanent-storage after)
                 (log/info "Moving file to permanent storage" after)
                 (try
                   (storage/move-blob! temporary-store after permanent-storage)
                   (catch Exception e
                     (log/error e "Failed to persist blob" after)))))
             handler-result))))))

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
   (defn blob-resolvers
     "Generates the extended blob resolvers for a given attribute."
     [{::keys      [store]
       ::attr/keys [qualified-key] :as attribute}]
     (let
       [url-key           (url-key qualified-key)
        url-resolver      (pc/resolver `url {::pc/input  #{qualified-key}
                                             ::pc/output [url-key]}
                            (fn [{::keys [permanent-stores]} input]
                              (let [sha        (get input qualified-key)
                                    file-store (get permanent-stores store)]
                                (when-not (seq sha)
                                  (log/error "Could not file file URL. No sha." qualified-key))
                                (when-not file-store
                                  (log/error "Attempt to retrieve a file URL, but there was no store in parsing env: " store))
                                (when (and (seq sha) file-store)
                                  {url-key (storage/blob-url file-store sha)}))))
        sha-exists?       (fn [{::keys [permanent-stores]} input]
                            (let [sha        (get input qualified-key)
                                  file-store (get permanent-stores store)]
                              (when-not sha
                                (log/error "Could not check file. No sha." qualified-key))
                              (when-not file-store
                                (log/error "Attempt to retrieve a file, but there was no store in parsing env: " store))
                              (boolean (and sha file-store (storage/blob-exists? file-store sha)))))
        progress-key      (progress-key qualified-key)
        status-key        (status-key qualified-key)
        progress-resolver (pc/resolver `progress {::pc/input  #{qualified-key}
                                                  ::pc/output [progress-key]}
                            (fn [env input]
                              (if (sha-exists? env input)
                                {progress-key 100}
                                {progress-key 0})))
        status-resolver   (pc/resolver `status {::pc/input  #{qualified-key}
                                                ::pc/output [progress-key]}
                            (fn [env input]
                              (if (sha-exists? env input)
                                {status-key :available}
                                {status-key :not-found})))]
       [url-resolver progress-resolver status-resolver])))

#?(:clj
   (defn wrap-blob-service
     "Middleware that can serve a blob-store file at URI `base-path`/SHA from `blob-store`. A query parameter of `filename`
      should be included in the request so that the user-visible filename is sent as that instead of the SHA."
     [handler base-path blob-store]
     (fn [{:keys [uri params] :as req}]
       (if (str/starts-with? uri base-path)
         (let [sha      (last (str/split uri #"/"))
               filename (:filename params)]
           (log/info "Trying to serve file " sha)
           (if-let [stream (storage/blob-stream blob-store sha)]
             {:status  200
              :headers {"Content-Disposition" (str "attachment; filename=" filename)
                        "Cache-Control"       "max-age=31536000, public, immutable"}
              :body    stream}
             {:status  400
              :headers {"content-type" "text/plain"}
              :body    "Not found"}))
         (handler req)))))

#?(:clj
   (defn resolvers [all-attributes]
     (let [blob-attributes (filterv ::store all-attributes)]
       (into [upload-file]
         (map blob-resolvers blob-attributes)))))

#?(:clj
   (defmacro defblobattr
     "Use this to create a Blob SHA (string) attribute that will track a file upload:

     ```
     (defblobattr sha :file/sha :remote-file-store :remote
       {... normal attribute map ...})
     ```

     The `remote-file-store` is the name of the store that the file will be stored in, and the `fulcro-http-remote` is the Fulcro
     client HTTP remote to use for the file transfer (which must be configured with the proper upload middleware on the
     client and server).
     "
     [sym k remote-store-name fulcro-http-remote attribute-map]
     (let [url-key      (url-key k)
           progress-key (progress-key k)
           status-key   (status-key k)]
       `(def ~sym (-> (assoc (attr/new-attribute ~k :string ~attribute-map)
                        ;; FIXME: Should NOT override user desire
                        :com.fulcrologic.rad.form/field-style ::file-upload
                        ::remote ~fulcro-http-remote
                        ::store ~remote-store-name)
                    (update :com.fulcrologic.rad.form/query-inclusion (fnil conj [])
                      ~url-key ~progress-key ~status-key))))))

(defn evt->js-files
  "Convert a file input change event into a sequence of the js File objects."
  [evt]
  #?(:cljs
     (let [js-file-list (.. evt -target -files)]
       (map (fn [file-idx]
              (let [js-file (.item js-file-list file-idx)
                    name    (.-name js-file)]
                js-file))
         (range (.-length js-file-list))))))

(defn blob-downloadable?
  "Returns true if the blob tracked by `sha-key` in the given `form-props` is in a state that would allow for a download."
  [form-props sha-key]
  (let [status (get form-props (status-key sha-key))
        sha    (get form-props sha-key)
        url    (get form-props (url-key sha-key))]
    (and (= :available status) (seq sha) (seq url))))

(defn uploading?
  "Returns true of the blob tracked by sha-key is actively being uploaded."
  [form-props sha-key]
  (let [status (get form-props (status-key sha-key))
        sha    (get form-props sha-key)]
    (and (= :uploading status) (seq sha))))

(defn failed-upload?
  "Returns true of the blob tracked by sha-key failed to upload."
  [form-props sha-key]
  (let [status (get form-props (status-key sha-key))]
    (= :failed status)))

(defn upload-percentage
  "Returns a string of the form \"n%\" which represents what percentage of the given blob identified by
  sha-key has made it to the server."
  [props sha-key]
  (str (get props (progress-key sha-key) 0) "%"))
