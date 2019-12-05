(ns com.fulcrologic.rad.resolvers
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.database :as db]
    [com.fulcrologic.rad.database-adapters.db-adapter :as dba]
    [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]
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

(>defn attribute-resolver
  "Generate a resolver for an attribute that specifies a ::pc/resolve key. Returns a resolver
  or nil."
  [attr]
  [::attr/attribute => (? ::pc/resolver)]
  (enc/when-let [resolver (::pc/resolve attr)
                 secure-resolver (fn [env input]
                                   (->>
                                     (resolver env input)
                                     (auth/redact env)))
                 k (::attr/qualified-key attr)
                 output [k]]
    (log/info "Building attribute resolver for" (::attr/qualified-key attr))
    (merge
      {::pc/output output}
      (just-pc-keys attr)
      {::pc/sym     (symbol (str k "-resolver"))
       ::pc/resolve secure-resolver})))

(>defn generate-resolvers
  "Generate resolvers for attributes that directly define pathom ::pc/resolve keys"
  [attributes]
  [::attr/attributes => (s/every ::pc/resolver :kind vector?)]
  (into []
    (keep attribute-resolver)
    attributes))

