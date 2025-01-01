(ns com.fulcrologic.rad.pathom-async
  (:require
    [com.fulcrologic.rad.pathom :as rp]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]))

(defn parser-args [{{:keys [trace? log-requests? log-responses?]} ::config} plugins resolvers]
  {::p/mutate  pc/mutate-async
   ::p/env     {::p/reader                 [p/map-reader pc/async-reader2 pc/index-reader
                                            pc/open-ident-reader p/env-placeholder-reader]
                ::p/placeholder-prefixes   #{">"}
                ::pc/mutation-join-globals [:tempids]}
   ::p/plugins (into []
                 (keep identity
                   (concat
                     [(pc/connect-plugin {::pc/register resolvers})]
                     plugins
                     [(p/env-plugin {::p/process-error rp/process-error})
                      (when log-requests? (rp/preprocess-parser-plugin rp/log-request!))
                      ;; TODO: Do we need this, and if so, we need to pass the attribute map
                      ;(p/post-process-parser-plugin add-empty-vectors)
                      (p/post-process-parser-plugin p/elide-not-found)
                      (p/post-process-parser-plugin rp/elide-reader-errors)
                      (when log-responses? (rp/post-process-parser-plugin-with-env rp/log-response!))
                      rp/query-params-to-env-plugin
                      p/error-handler-plugin
                      (when trace? p/trace-plugin)])))})

(defn new-async-parser
  "Create a new pathom parser. `config` is a map containing a ::config key with parameters
  that affect the parser. `extra-plugins` is a sequence of pathom plugins to add to the parser. The
  plugins will typically need to include plugins from any storage adapters that are being used,
  such as the `datomic/pathom-plugin`.
  `resolvers` is a vector of all of the resolvers to register with the parser, which can be a nested collection.

  Supported config options under the ::config key:

  - `:trace? true` Enable the return of pathom performance trace data (development only, high overhead)
  - `:log-requests? boolean` Enable logging of incoming queries/mutations.
  - `:log-responses? boolean` Enable logging of parser results."
  [config extra-plugins resolvers]
  (let [real-parser (p/async-parser (parser-args config extra-plugins resolvers))
        {:keys [trace?]} (get config ::config {})]
    (fn wrapped-parser [env tx]
      (real-parser env (if trace?
                         (conj tx :com.wsscode.pathom/trace)
                         tx)))))
