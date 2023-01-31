(ns com.fulcrologic.rad.pathom3
  "Support for Pathom 3 as the EQL processor for RAD"
  (:require
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.runner :as pcr]
    [com.wsscode.pathom3.error :as p.error]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.wsscode.pathom3.plugin :as p.plugin]
    [com.fulcrologic.rad.pathom-common :as rpc]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(letfn [(has-cause? [err desired-cause] (boolean
                                          (some
                                            (fn [{::p.error/keys [cause]}] (= cause desired-cause))
                                            (some->> err ::p.error/node-error-details (vals)))))
        (missing? [err] (has-cause? err ::p.error/attribute-missing))
        (unreachable? [err] (= (::p.error/cause err) ::p.error/attribute-unreachable))
        (exception? [err] (has-cause? err ::p.error/node-exception))
        (node-exception [err] (some
                                (fn [{::p.error/keys [exception]}] exception)
                                (some->> err ::p.error/node-error-details (vals))))]
  (p.plugin/defplugin attribute-error-plugin
    {::p.error/wrap-attribute-error
     (fn [attribute-error]
       (fn [response attribute]
         (when-let [err (attribute-error response attribute)]
           (cond
             (missing? err) nil
             (unreachable? err) (log/errorf "EQL query for %s cannot be resolved. Is it spelled correctly? Pathom error: %s" attribute err)
             (exception? err) (log/error (node-exception err) "Resolver threw an exception while resolving" attribute)
             :else nil))))}))

(letfn [(p2-resolver? [r] (and (map? r) (contains? r :com.wsscode.pathom.connect/resolve)))
        (p2-mutation? [r] (and (map? r) (contains? r :com.wsscode.pathom.connect/mutate)))
        (p2? [r] (or (p2-resolver? r) (p2-mutation? r)))]
  (defn pathom2->pathom3
    "Converts a Pathom 2 resolver or mutation into one that will work with Pathom 3.

    Pathom 2 uses plain maps for these, and the following keys are recognized and supported:

    ::pc/sym -> ::pco/op-name
    ::pc/input -> ::pco/input as EQL
    ::pc/output -> ::pco/output
    ::pc/batch? -> ::pco/batch?
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
            {:com.wsscode.pathom.connect/keys [resolve batch? sym input output mutate]} (cond-> resolver-or-mutation
                                                                                          transform (transform))
            config (cond-> {}
                     input (assoc ::pco/input (vec input))
                     batch? (assoc ::pco/batch? batch?)
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
      :else resolver-or-resolvers)))



(letfn [(wrap-mutate-exceptions [mutate]
          (fn [env ast]
            (try
              (mutate env ast)
              (catch Throwable e
                (log/errorf e "Mutation %s failed." (:key ast))
                ;; FIXME: Need a bit more work on returning errors that are handled globally.
                ;; Probably should just propagate exceptions out, so the client sees a server error
                ;; Pathom 2 compatible message so UI can detect the problem
                {:com.wsscode.pathom.core/errors [{:message (ex-message e)
                                                   :data    (ex-data e)}]}))))]
  (p.plugin/defplugin rewrite-mutation-exceptions {::pcr/wrap-mutate wrap-mutate-exceptions})
  (defn new-processor
    "Create a new EQL processor. You may pass Pathom 2 resolvers or mutations to this function, but beware
     that the translation is not 100% perfect, since the `env` is different between the two versions.

     The config options go under :com.fulcrologic.rad.pathom/config, and include:

     - `:log-requests? boolean` Enable logging of incoming queries/mutations.
     - `:log-responses? boolean` Enable logging of parser results.
     - `:sensitive-keys` a set of keywords that should not have their values logged

     Optional arguments:

     - `update-base-env` a `(fn [env] env)` that gets the initialized base env with all resolvers and plugins and can
       modify it before it is used to create a Pathom processor. F.ex. you can set it to `pathom-viz-connector`'s
        `com.wsscode.pathom.viz.ws-connector.pathom3/connect-env` to enable Pathom Viz connections.
     "
    ([config env-middleware extra-plugins resolvers] (new-processor config env-middleware extra-plugins resolvers nil))
    ([{{:keys [log-requests? log-responses?]} :com.fulcrologic.rad.pathom/config :as config} env-middleware extra-plugins resolvers update-base-env]
     (let [base-env (cond-> (-> {}
                                (p.plugin/register extra-plugins)
                                (p.plugin/register-plugin attribute-error-plugin)
                                (p.plugin/register-plugin rewrite-mutation-exceptions)
                                ;(p.plugin/register-plugin log-resolver-error)
                                (pci/register (convert-resolvers resolvers))
                                (assoc :config config))
                            update-base-env (update-base-env))
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
           response))))))
