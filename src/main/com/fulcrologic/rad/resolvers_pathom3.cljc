(ns com.fulcrologic.rad.resolvers-pathom3
  "Support for allowing Pathom 3 resolvers to be declared on attributes via ::pco/input ::pco/output and ::pco/resolve.
  This is useful because it allows custom resolution of an attribute that then has all of the RAD abilities that attributes have.

  Further, all keys in `::pco/*` participates in the generation of the resolvers. The full Pathom3 `defresolver` API is available.

  fulcro-rad has no explicit dependency on pathom3, and should not have.
  It is assumed that you have it on your classpath if you require this ns.

  ```
  (defattr server-time :server/time :inst
    {::pco/output [:server/time]
     ::pco/resolver (fn [_ _] (java.util.Date.))
     ::form/label \"Current Server Time\"
     ::form/field-style :some-custom-style
     ...})
  ```

  To register the resolvers in your index:

  ```
  (let [all-attribute-resolvers (resolvers/generate-resolvers all-attributes)
        base-env (pci/register all-attribute-resolvers)]
    ... now wrap base-env, see at least com.fulcrologic.rad.attributes/wrap-env)
  ```

  You may also, of course, define resolvers using `pco/defresolver` and other pathom3 functions, but you must register those
  separately. `pci/register` takes multiple args."
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.resolvers-common :as resolvers-common]
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
                 resolve-sym     (symbol (namespace k) (str (name k) "-resolver"))]
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
