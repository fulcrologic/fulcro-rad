# File Upload/Download

## Overview

RAD Forms support file uploads with download/preview of previously-uploaded files. Files are tracked by SHA256
fingerprints, uploaded to temporary storage during form editing, then moved to permanent storage (S3, disk, etc.) on
form save. RAD uses Fulcro's file upload mechanisms for transfer and provides middleware for both client and server.

## Core Concepts

From DevelopersGuide.adoc:1866-1880:

**Components**:

1. **BLOB attributes**: Database attributes tracking SHA256, filename, etc.
2. **Temporary storage**: Holds uploaded files until form save
3. **Permanent storage**: Final destination for file content (S3, disk)
4. **URL resolver**: Generates download URLs from SHAs

From DevelopersGuide.adoc:1872-1874:
> "EQL resolvers send transit, so it is not possible to query for the file *content* via a Pathom resolver. Instead you
> must supply a resolver that can, given the current parsing context, resolve the URL of the file's content for download
> by the UI."

From DevelopersGuide.adoc:1875-1879:
> "File transfer support leverages Fulcro's normal file upload mechanisms for upload and the normal HTTP GET mechanisms
> for download. The file is sent as a separate upload mutation during form interaction, and upload progress blocks exiting
> the form until the upload is complete... The file itself is stored on the server as a temporary file until such time as
> you save the form itself (though you can also configure the form to auto-save when upload is complete)."

## General Operation

From DevelopersGuide.adoc:1881-1905:

### SHA256 Fingerprinting

From DevelopersGuide.adoc:1883-1887:
> "RAD's built-in support for BLOBs requires that you define a place in one of your database stores to keep a
> fingerprint for the file. RAD uses SHA256 to generate such a fingerprint for files (much like `git`). The fingerprint is
> treated as the key to the binary data in the store where you place the bytes of the file. This allows you to do things
> like duplicate detection, and can help in situations where many users might upload the same content (your regular
> database would track who has access to what files, but they'd be deduped)."

### Upload Flow

From DevelopersGuide.adoc:1893-1904:

1. **User selects file** in form
2. **RAD generates SHA256** for the file
3. **Upload begins immediately** (progress tracked, save disabled until complete)
4. **SHA stored** in form field (will be saved to database)
5. **File saved** to temporary store (temp disk file)
6. **User saves form** → SHA comes across in save delta
7. **Middleware detects SHA** → moves content from temporary to permanent store
8. **Permanent storage** configured to provide protected URLs for file access

### Keyword Narrowing

From DevelopersGuide.adoc:1935-1941:
> "Since RAD controls the rendering of the file in forms it needs to know how to group together attributes of a file so
> that it knows which is the filename, which is the URL, etc. RAD does this by keyword \"narrowing\", our term for the
> process of using the current attribute's full name as a namespace (by replacing `/` with `.`) and adding a new name.
>
> Thus, if you define a blob attribute `:file/sha` then the filename attribute will *be assumed* to be
`:file.sha/filename` by the auto-generated UI in RAD."

**Pattern**:

- Base attribute: `:file/sha`
- Derived attributes: `:file.sha/filename`, `:file.sha/url`, `:file.sha/progress`, `:file.sha/status`

## Defining BLOB Attributes

From DevelopersGuide.adoc:1942-1975:

### defblobattr Macro

```clojure
(ns com.example.model.file
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.blob :as blob]))

(defattr id :file/id :uuid
  {ao/identity? true
   ao/schema    :production})

;; :files is the name of the BLOB store, :remote is the Fulcro remote for uploads
(blob/defblobattr sha :file/sha :files :remote
  {ao/identities #{:file/id}
   ao/schema     :production})

(defattr filename :file.sha/filename :string
  {ao/schema     :production
   ao/identities #{:file/id}})

(defattr uploaded-on :file/uploaded-on :instant
  {ao/schema     :production
   ao/identities #{:file/id}})

(def attributes [id sha filename uploaded-on])
```

From DevelopersGuide.adoc:1977-1978:
> "The `defblobattr` requires you supply a keyword for the attribute, the name of the permanent store for the content (
`:files` in this example), and the name of the Fulcro client remote (`:remote` in this example) that can transmit the
> file bytes."

**Signature**: `(blob/defblobattr sym keyword store-name remote-name options)`

## Client Setup

From DevelopersGuide.adoc:1979-1993:

### HTTP Remote with File Upload Middleware

```clojure
(ns com.example.client
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.networking.file-upload :as file-upload]))

(def request-middleware
  (->
    (http/wrap-fulcro-request)
    (file-upload/wrap-file-upload)))

(defonce app
  (app/fulcro-app
    {:remotes {:remote (http/fulcro-http-remote
                         {:url                "/api"
                          :request-middleware request-middleware})}}))
```

From DevelopersGuide.adoc:1981-1982:
> "You must configure an HTTP remote on the client that includes the Fulcro file upload middleware. This is covered in
> the Fulcro Developer's guide..."

## Server Setup

From DevelopersGuide.adoc:1995-2080:

### Storage Protocol

From DevelopersGuide.adoc:1999-2005:
> "First, you need to define a temporary and permanent store. RAD requires a store to implement the
`com.fulcrologic.rad.blob-storage/Storage` protocol. The temporary store can just use the pre-supplied transient store,
> which uses (and tries to garbage collect) temporary disk files on your server's disk. RAD's transient store requires
> connection stickiness so that the eventual form save will go to the save server as the temporary store."

### Define Blob Stores

```clojure
(ns com.example.components.blob-store
  (:require
    [com.fulcrologic.rad.blob-storage :as storage]
    [mount.core :refer [defstate]]))

(defstate temporary-blob-store
  :start
  (storage/transient-blob-store "" 1))

(defstate file-blob-store
  :start
  (storage/transient-blob-store "/files" 10000))
```

**`storage/transient-blob-store` Parameters**:

- `url-prefix` - URL prefix for file downloads (e.g., "/files")
- `max-age-seconds` - How long to keep orphaned files (1 sec for temp, 10000 for permanent)

### Ring Middleware

From DevelopersGuide.adoc:2006-2052:

```clojure
(ns com.example.components.ring-middleware
  (:require
    [com.example.components.blob-store :as bs]
    [com.example.components.config :as config]
    [com.fulcrologic.fulcro.networking.file-upload :as file-upload]
    [com.fulcrologic.fulcro.server.api-middleware :as server]
    [com.fulcrologic.rad.blob :as blob]
    [mount.core :refer [defstate]]
    [ring.middleware.defaults :refer [wrap-defaults]]
    [ring.util.response :as resp]
    [taoensso.timbre :as log]))

(defstate middleware
  :start
  (let [defaults-config (:ring.middleware/defaults-config config/config)]
    (-> not-found-handler
      (wrap-api "/api")
      ;; Fulcro support for integrated file uploads
      (file-upload/wrap-mutation-file-uploads {})
      ;; RAD integration for *serving* files FROM RAD blob store (at /files URI)
      (blob/wrap-blob-service "/files" bs/file-blob-store)
      (server/wrap-transit-params {})
      (server/wrap-transit-response {})
      (wrap-defaults defaults-config))))
```

From DevelopersGuide.adoc:2008-2009:
> "There are two parts to the Ring middleware, and one is optional and is only necessary if you plan to serve the BLOB
> URLs from your server."

**Required**:

- `file-upload/wrap-mutation-file-uploads` - Handles incoming file uploads

**Optional**:

- `blob/wrap-blob-service` - Serves file content at specified URI (e.g., `/files/<SHA>`)

### Pathom Parser Integration

From DevelopersGuide.adoc:2055-2080:

```clojure
(ns com.example.components.parser
  (:require
    [com.example.components.blob-store :as bs]
    [com.example.model.attributes :refer [all-attributes]]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.pathom :as pathom]
    [mount.core :refer [defstate]]))

(defstate parser
  :start
  (pathom/new-parser config
    [; ... other plugins ...
     ;; Enables binary object upload integration with RAD
     (blob/pathom-plugin bs/temporary-blob-store {:files bs/file-blob-store})
     ; ... other plugins ...
     ]
    [resolvers
     ; ... other resolvers ...
     (blob/resolvers all-attributes)]))
```

From DevelopersGuide.adoc:2082-2084:
> "The blob plugin mainly puts the temporary store and permanent store(s) into the parsing env so that they are
> available when built-in blob-related reads/mutations are called. The BLOB resolvers use the keyword narrowing of your
> SHA attribute and the `env` to provide values that can be derived from the SHA and the store (i.e. `:file.sha/url`)."

## File Arity (Single vs. Multiple Files)

From DevelopersGuide.adoc:2085-2090:

### Single File

Define one SHA attribute on the entity:

```clojure
(blob/defblobattr avatar :user/avatar :files :remote
  {ao/identities #{:user/id}})

(defattr avatar-filename :user.avatar/filename :string
  {ao/identities #{:user/id}})
```

### Multiple Fixed Files

Define multiple SHA attributes:

```clojure
(blob/defblobattr thumbnail :product/thumbnail :files :remote
  {ao/identities #{:product/id}})

(blob/defblobattr main-image :product/main-image :files :remote
  {ao/identities #{:product/id}})
```

### Variable Number of Files

From DevelopersGuide.adoc:2087-2090:
> "You can also support general to-many support for files simply by creating a `ref` attribute that refers to a
> entity/row/document that has a file SHA on it."

```clojure
;; Attachment entity
(defattr id :attachment/id :uuid
  {ao/identity? true})

(blob/defblobattr content :attachment/content :files :remote
  {ao/identities #{:attachment/id}})

(defattr filename :attachment.content/filename :string
  {ao/identities #{:attachment/id}})

;; Invoice references many attachments
(defattr attachments :invoice/attachments :ref
  {ao/target      :attachment/id
   ao/cardinality :many
   ao/identities  #{:invoice/id}})
```

## Rendering File Upload Controls

From DevelopersGuide.adoc:2091-2109:

### Using blob/upload-file!

From DevelopersGuide.adoc:2097-2109:

```clojure
(ns com.example.ui.file-form
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.form :as form]))

(form/defsc-form FileForm [this props]
  {fo/id         file/id
   fo/attributes [file/sha file/uploaded-on]
   fo/subforms   {}}
  (dom/div
    (dom/input {:type     "file"
                :onChange (fn [evt]
                            (when-let [js-file (-> evt .-target .-files (aget 0))]
                              (blob/upload-file! this file/sha js-file
                                {:file-ident (comp/get-ident this)})))})))
```

From DevelopersGuide.adoc:2103-2109:
> "Assuming `this` represents the UI instance that has the file upload field, the call to start an upload is:
>
> `(blob/upload-file! this blob-attribute js-file {:file-ident (comp/get-ident this)})`
>
> If your `blob-attribute` had the keyword `:file/sha` then you'd see a `:file.sha/progress` and `:file.sha/status`
> appear on that entity and update as the file upload progresses. Saving the form should then automatically move the file
> content (named by SHA) from temporary to permanent storage."

**Auto-Generated Attributes**:

- `:file.sha/progress` - Upload percentage (0-100)
- `:file.sha/status` - Upload status (`:uploading`, `:complete`, `:failed`)

## Downloading Files

From DevelopersGuide.adoc:2110-2120:

### blob-url Method

From DevelopersGuide.adoc:2112-2120:
> "The `Storage` protocol defines a `blob-url` method. This method is under the control of the implementation, of
> course, and may do nothing more than return the SHA you hand it. You are really responsible for hooking RAD up to a
> binary store that works for your deployment. The built-in support assumes that you'll serve the file content *through*
> your server for access control. The provided middleware simply asks the Storage protocol for a stream of the file's
> bytes, and serves them at a URI on your server.
>
> Thus, you might configure your permanent blob store to return the URL `/files/<SHA>`, and then configure your Ring
> middleware to provide the correct file when asked for `/files/<SHA>`. This is what the middleware configuration shown
> earlier will do."

**Flow**:

1. BLOB attribute resolves to `:file.sha/url` via `blob/resolvers`
2. URL typically `/files/<SHA>`
3. Browser requests URL
4. `blob/wrap-blob-service` middleware intercepts
5. Asks Storage for file stream
6. Serves file bytes with appropriate headers

## Common Patterns

### Pattern 1: User Profile Picture

```clojure
(ns com.example.model.user
  (:require
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defattr id :user/id :uuid
  {ao/identity? true
   ao/schema    :production})

(blob/defblobattr avatar :user/avatar :files :remote
  {ao/identities #{:user/id}
   ao/schema     :production})

(defattr avatar-filename :user.avatar/filename :string
  {ao/identities #{:user/id}
   ao/schema     :production})
```

### Pattern 2: Document Management

```clojure
;; Document entity with file
(defattr id :document/id :uuid
  {ao/identity? true})

(blob/defblobattr content :document/content :files :remote
  {ao/identities #{:document/id}})

(defattr content-filename :document.content/filename :string
  {ao/identities #{:document/id}})

(defattr uploaded-by :document/uploaded-by :ref
  {ao/target     :user/id
   ao/identities #{:document/id}})

;; Project has many documents
(defattr documents :project/documents :ref
  {ao/target      :document/id
   ao/cardinality :many
   ao/identities  #{:project/id}})
```

### Pattern 3: S3 Storage

```clojure
(ns com.example.storage.s3
  (:require
    [com.fulcrologic.rad.blob-storage :as storage]))

(defrecord S3BlobStore [bucket prefix]
  storage/Storage
  (save-blob [this sha input-stream]
    ; Upload to S3 bucket
    (s3/put-object bucket (str prefix sha) input-stream))

  (blob-url [this sha]
    ; Return CloudFront URL or signed S3 URL
    (str "https://cdn.example.com/" sha))

  (blob-stream [this sha]
    ; Download from S3
    (:input-stream (s3/get-object bucket (str prefix sha))))

  (delete-blob [this sha]
    (s3/delete-object bucket (str prefix sha))))

(defn s3-blob-store [bucket prefix]
  (->S3BlobStore bucket prefix))
```

### Pattern 4: File Preview in Form

```clojure
(form/defsc-form DocumentForm [this {:document/keys [id] :as props}]
  {fo/id         document/id
   fo/attributes [document/content document/uploaded-by]}
  (let [url (get props :document.content/url)
        filename (get props :document.content/filename)]
    (dom/div
      (when url
        (dom/div
          (dom/h3 "Current File")
          (dom/a {:href url :target "_blank"} filename)))
      (dom/input {:type     "file"
                  :onChange (fn [evt]
                              (when-let [js-file (-> evt .-target .-files (aget 0))]
                                (blob/upload-file! this document/content js-file
                                  {:file-ident [:document/id id]})))}))))
```

## Important Notes

### 1. Temporary Storage Requires Connection Stickiness

From DevelopersGuide.adoc:2003-2005:
> "RAD's transient store requires connection stickiness so that the eventual form save will go to the save server as the
> temporary store. If that is not possible in your deployment then you may wish to use your permanent store as the
> temporary store and just plan on cleaning up stray files at some future time."

### 2. SHA is the Primary Key

Files are identified by SHA256. This enables:

- Duplicate detection
- Deduplication across users
- Content-addressable storage (like Git)

### 3. Keyword Narrowing is Automatic

From DevelopersGuide.adoc:1936-1941:

Given `:file/sha`, RAD automatically looks for:

- `:file.sha/filename`
- `:file.sha/url`
- `:file.sha/progress`
- `:file.sha/status`

**Convention**: Follow this pattern for greenfield projects. Use rewrite middleware if legacy schema differs.

### 4. Upload Progress Blocks Form Save

From DevelopersGuide.adoc:1876-1877:
> "Upload progress blocks exiting the form until the upload is complete (the form field itself for the upload relies on
> correctly-installed validation for this to function)."

### 5. File Content Not Queryable via EQL

From DevelopersGuide.adoc:1872-1874:

You cannot query for file bytes through Pathom. Instead, query for the URL and use HTTP GET to download.

### 6. Middleware Order Matters

```clojure
(-> handler
  (wrap-api "/api")
  (file-upload/wrap-mutation-file-uploads {})  ; Must be before wrap-api
  (blob/wrap-blob-service "/files" store)      ; Can be anywhere
  (server/wrap-transit-params {})
  (server/wrap-transit-response {}))
```

### 7. Storage Protocol is Pluggable

Implement `com.fulcrologic.rad.blob-storage/Storage` for custom storage:

- S3/CloudFront
- Google Cloud Storage
- Azure Blob Storage
- Database BLOBs
- Custom solutions

## Related Topics

- **Forms Basics** (04-forms-basics.md): Understanding form save flow that triggers file moves
- **Server Setup** (10-server-setup.md): Ring middleware configuration
- **Database Adapters** (11-database-adapters.md): How save middleware integrates with file upload
- **Client Setup** (12-client-setup.md): Configuring HTTP remotes

## Source References

- DevelopersGuide.adoc:1866-2120 (File Upload/Download section)
- DevelopersGuide.adoc:1881-1905 (General Operation)
- DevelopersGuide.adoc:1942-1975 (Defining BLOB attributes)
- DevelopersGuide.adoc:1979-1993 (Client setup)
- DevelopersGuide.adoc:1995-2080 (Server setup)
- DevelopersGuide.adoc:2085-2090 (File arity)
- DevelopersGuide.adoc:2097-2109 (Upload controls)
- DevelopersGuide.adoc:2110-2120 (Downloading files)
