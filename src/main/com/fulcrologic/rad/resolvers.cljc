(ns com.fulcrologic.rad.resolvers
  "Support for allowing Pathom 2 resolvers to be declared on attributes via ::pc/input ::pc/output and ::pc/resolve. This is
  useful because it allows custom resolution of an attribute that then has all of the RAD abilities attributes have.

  ```
  (defattr server-time :server/time :inst
    {::pc/output [:server/time]
     ::pc/resolver (fn [_ _] (java.util.Date.))
     ::form/label \"Current Server Time\"
     ::form/field-style :some-custom-style
     ...})
  ```

  Then install them in your parser as a list of resolvers you can obtain from `(resolvers/generate-resolvers all-attributes)`.

  You may also, of course, define resolvers using `defresolver` and other pathom functions, but you must install those
  separately."
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.resolvers-common :as resolvers-common]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(defn just-pc-keys [m]
  (into {}
    (keep (fn [k]
            (when (or
                    (= (namespace k) "com.wsscode.pathom.connect")
                    (= (namespace k) "com.wsscode.pathom.core"))
              [k (get m k)])))
    (keys m)))

(defn attribute-resolver
  "Generate a resolver for an attribute that specifies a :com.wsscode.pathom.connect/resolve key. Returns a resolver
  or nil."
  [attr]
  (enc/when-let [resolver        (:com.wsscode.pathom.connect/resolve attr)
                 k               (::attr/qualified-key attr)
                 output          [k]]
    (log/info "Building attribute resolver for" (::attr/qualified-key attr))
    (let [transform (:com.wsscode.pathom.connect/transform attr)]
      (cond-> (merge
                {:com.wsscode.pathom.connect/output output}
                (just-pc-keys attr)
                {:com.wsscode.pathom.connect/sym     (symbol (str k "-resolver"))
                 :com.wsscode.pathom.connect/resolve (resolvers-common/secure-resolver resolver)})
        transform transform))))

(defn generate-resolvers
  "Generate resolvers for attributes that directly define pathom :com.wsscode.pathom.connect/resolve keys"
  [attributes]
  (into []
    (keep attribute-resolver)
    attributes))

