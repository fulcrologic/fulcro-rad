(ns com.fulcrologic.rad.pathom3
  "Support for Pathom 3 as the EQL processor for RAD"
  (:require
    [com.wsscode.pathom3.cache :as p.cache]
    [com.wsscode.pathom3.connect.built-in.resolvers :as pbir]
    [com.wsscode.pathom3.connect.built-in.plugins :as pbip]
    [com.wsscode.pathom3.connect.foreign :as pcf]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.operation.transit :as pcot]
    [com.wsscode.pathom3.connect.planner :as pcp]
    [com.wsscode.pathom3.connect.runner :as pcr]
    [com.wsscode.pathom3.error :as p.error]
    [com.wsscode.pathom3.format.eql :as pf.eql]
    [com.wsscode.pathom3.interface.async.eql :as p.a.eql]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.wsscode.pathom3.interface.smart-map :as psm]
    [com.wsscode.pathom3.path :as p.path]
    [com.wsscode.pathom3.plugin :as p.plugin]
    [com.fulcrologic.rad.pathom-common :as rpc]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn- p2-resolver? [r] (and (map? r) (contains? r :com.wsscode.pathom.connect/resolve)))
(defn- p2-mutation? [r] (and (map? r) (contains? r :com.wsscode.pathom.connect/mutate)))
(defn- p2? [r] (or (p2-resolver? r) (p2-mutation? r)))

(defn pathom2->pathom3
  "Converts a Pathom 2 resolver or mutation into one that will work with Pathom 3.

  Pathom 2 uses plain maps for these, and the following keys are recognized and supported:

  ::pc/sym -> ::pco/op-name
  ::pc/input -> ::pco/input as EQL
  ::pc/output -> ::pco/output
  ::pc/transform -> applied before conversion
  ::pc/mutate
  ::pc/resolve

  Returns the input unchanged of the given item is not a p2 artifact.

  NOTE: Any `transform` is applied at conversion time. Also, if your Pathom 2 resolver returns a value
  using Pathom 2 `final`, then that will not be converted into Pathom 3 by this function.

  You should manually convert that resolver by hand and use the new final support in Pathom 3.
   "
  [resolver-or-mutation]
  (if (p2? resolver-or-mutation)
    (let [{:com.wsscode.pathom.connect/keys [transform]} resolver-or-mutation
          {:com.wsscode.pathom.connect/keys [resolve sym input output mutate]} (cond-> resolver-or-mutation
                                                                                 transform (transform))
          config (cond-> {}
                   input (assoc ::pco/input (vec input))
                   output (assoc ::pco/output output))]
      (if resolve
        (pco/resolver sym config resolve)
        (pco/mutation sym config mutate)))
    resolver-or-mutation))

(defn convert-resolvers
  "Convert a single or sequence (or nested sequences) of P2 resolvers (and/or mutations) to P3."
  [resolver-or-resolvers]
  (cond
    (sequential? resolver-or-resolvers) (mapv convert-resolvers resolver-or-resolvers)
    (p2? resolver-or-resolvers) (pathom2->pathom3 resolver-or-resolvers)
    :else resolver-or-resolvers))

(letfn [(wrap-error [_] (fn [_ ast error] (log/error error "Mutation error on" (:key ast))))]
  (defn new-processor
    "Create a new EQL processor. You may pass Pathom 2 resolvers or mutations to this function, but beware
     that the translation is not 100% perfect, since the `env` is different between the two versions.

     The config options go under :com.fulcrologic.rad.pathom/config, and include:

     - `:log-requests? boolean` Enable logging of incoming queries/mutations.
     - `:log-responses? boolean` Enable logging of parser results.
     - `:sensitive-keys` a set of keywords that should not have their values logged
     "
    [{{:keys [trace? log-requests? log-responses?]} :com.fulcrologic.rad.pathom/config :as config} env-middleware extra-plugins resolvers]
    (let [base-env (-> (reduce p.plugin/register-plugin {} extra-plugins)
                     (p.plugin/register-plugin {::p.plugin/id             'log-mutation-error
                                                ::pcr/wrap-mutation-error wrap-error})
                     (p.plugin/register-plugin {::p.plugin/id             'log-resolver-error
                                                ::pcr/wrap-resolver-error wrap-error})
                     (pci/register (convert-resolvers resolvers))
                     (assoc :config config))
          process  (p.eql/boundary-interface base-env)]
      (fn [env tx]
        (when log-requests?
          (rpc/log-request! {:env env :tx tx}))
        (let [ast      (eql/query->ast tx)
              env      (assoc
                         (env-middleware env)
                         ;; for p2 compatibility
                         :parser p.eql/process
                         ;; legacy param support
                         :query-params (rpc/combined-query-params ast))
              response (process env {:pathom/ast           ast
                                     :pathom/lenient-mode? true})]
          (when log-responses? (rpc/log-response! env response))
          response)))))