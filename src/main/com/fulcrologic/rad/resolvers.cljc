(ns com.fulcrologic.rad.resolvers
  "Support for allowing resolvers to be declared on attributes via ::pc/input ::pc/output and ::pc/resolve. This is
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
  separately.

  "
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

;; TASK: Add read middleware for things like security and such

(defn just-pc-keys [m]
  (into {}
    (keep (fn [k]
            (when (or
                    (= (namespace k) "com.wsscode.pathom.connect")
                    (= (namespace k) "com.wsscode.pathom.core"))
              [k (get m k)])))
    (keys m)))

(>defn attribute-resolver
  "Generate a resolver for an attribute that specifies a ::pc/resolve key. Returns a resolver
  or nil."
  [attr]
  [::attr/attribute => (? ::pc/resolver)]
  (enc/when-let [resolver        (::pc/resolve attr)
                 secure-resolver (fn [env input]
                                   (->>
                                     (resolver env input)
                                     (auth/redact env)))
                 k               (::attr/qualified-key attr)
                 output          [k]]
    (log/info "Building attribute resolver for" (::attr/qualified-key attr))
    (let [transform (::pc/transform attr)]
      (cond-> (merge
                {::pc/output output}
                (just-pc-keys attr)
                {::pc/sym     (symbol (str k "-resolver"))
                 ::pc/resolve secure-resolver})
        transform transform
        ))))

(>defn generate-resolvers
  "Generate resolvers for attributes that directly define pathom ::pc/resolve keys"
  [attributes]
  [::attr/attributes => (s/every ::pc/resolver :kind vector?)]
  (into []
    (keep attribute-resolver)
    attributes))

