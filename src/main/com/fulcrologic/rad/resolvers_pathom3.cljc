(ns com.fulcrologic.rad.resolvers-pathom3
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.resolvers-common :as resolvers-common]
    ; Pathom3 is not a dependency of fulcro-rad, and should not be
    ; It is assumed that you have it on your classpath if you
    ; require this ns
    [com.wsscode.pathom3.connect.operation :as pco]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn just-pathom3-keys [m]
  (into {}
        (keep (fn [k]
                (when (= (namespace k) "com.wsscode.pathom3.connect.operation")
                  [k (get m k)])))
        (keys m)))

(defn attribute-resolver
  "Generate a resolver for an attribute that specifies a :com.wsscode.pathom3.connect.operation/resolve key. Returns a resolver
  or nil.

  Accepts any keys in the com.wsscode.pathom3.connect.operation ns,
  thus any keys defresolver accepts are valid."
  [attr]
  (enc/when-let [resolver        (::pco/resolve attr)
                 k               (::attr/qualified-key attr)
                 output          [k]
                 resolve-sym     (symbol (str k "-resolver"))]
    (log/info "Building Pathom3 attribute resolver for" (::attr/qualified-key attr))
    (pco/resolver
      (merge
        {::pco/output  output}
        ; Allow output to be overridden
        (just-pathom3-keys attr)
        ; But not op-name or resolve
        {::pco/op-name resolve-sym
         ::pco/resolve (resolvers-common/secure-resolver resolver)}))))

(defn generate-resolvers
  "Generate resolvers for attributes that directly define pathom3 :com.wsscode.pathom3.connect.operation/resolve keys"
  [attributes]
  (into []
    (keep attribute-resolver)
    attributes))