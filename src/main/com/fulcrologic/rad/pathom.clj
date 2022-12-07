(ns com.fulcrologic.rad.pathom
  "PATHOM 2 Support. If you're using pathom 3, do NOT use/require this namespace!

   Plugins and a default generated parser for working with RAD applications. You may use the source of this namespace
   as reference for making your own custom parser, or simply start with the `new-parser` function."
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.walk :as walk]
    [com.wsscode.common.async-clj :refer [let-chan]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.attributes :as attr]))

(defn preprocess-parser-plugin
  "Helper to create a plugin that can view/modify the env/tx of a top-level request.

  f - (fn [{:keys [env tx]}] {:env new-env :tx new-tx})

  If the function returns no env or tx, then the parser will not be called (aborts the parse)"
  [f]
  {::p/wrap-parser
   (fn transform-parser-out-plugin-external [parser]
     (fn transform-parser-out-plugin-internal [env tx]
       (let [{:keys [env tx] :as req} (f {:env env :tx tx})]
         (if (and (map? env) (seq tx))
           (parser env tx)
           {}))))})

(defn remove-omissions
  "Replaces black-listed keys from tx with ::omitted, meant for logging tx's
  without logging sensitive details like passwords."
  [config tx]
  (let [sensitive-keys (get-in config [::config :sensitive-keys] #{})]
    (walk/postwalk
      (fn [x]
        (if (and (vector? x) (= 2 (count x)) (contains? sensitive-keys (first x)))
          [(first x) ::omitted]
          x))
      tx)))

(defn- log! [env msg value]
  (binding [*print-level* 8 *print-length* 8]
    (let [{:keys [config]} env]
      (log/info msg
        (try
          (pr-str (remove-omissions config value))
          (catch Throwable e
            (log/error (.getMessage e))
            "<failed to serialize>"))))))

(defn log-request! [{:keys [env tx] :as req}]
  (let [config         (:config env)
        sensitive-keys (conj
                         (get-in config [::config :sensitive-keys] #{})
                         :com.wsscode.pathom/trace)]
    (log! env "transaction: " (remove-omissions sensitive-keys tx))
    req))

(defn log-response!
  [{:keys [config] :as env} response]
  (let [sensitive-keys (conj
                         (get-in config [::config :sensitive-keys] #{})
                         :com.wsscode.pathom/trace)]
    (log! env "response: " (remove-omissions sensitive-keys response))
    response))

(defn process-error
  "If there were any exceptions in the parser that cause complete failure we
  respond with a well-known message that the client can handle."
  [env err]
  (let [msg  (.getMessage err)
        data (or (ex-data err) {})]
    (log/error err "Parser Error:" msg data)
    {::errors {:message msg
               :data    data}}))

(defn elide-reader-errors
  [input]
  (with-meta
    (p/transduce-maps (map (fn [[k v]]
                             (if (= ::p/reader-error v)
                               [k nil]
                               [k v])))
      input)
    (meta input)))

(defn post-process-parser-plugin-with-env
  "Helper to create a plugin to work on the parser output. `f` will run once with the parser final result."
  [f]
  {::p/wrap-parser
   (fn transform-parser-out-plugin-external [parser]
     (fn transform-parser-out-plugin-internal [env tx]
       (let-chan [res (parser env tx)]
         (f env res))))})

;; FIXME: to-many? now takes an attr, not a key
#_(defn add-empty-vectors
    "For cardinality many attributes, replaces ::p/not-found with an empty vector."
    [input]
    (with-meta
      (p/transduce-maps (map (fn [[k v]]
                               (if (and (= ::p/not-found v) (attr/to-many? k))
                                 [k []]
                                 [k v])))
        input)
      (meta input)))

(def query-params-to-env-plugin
  "Adds top-level load params to env, so nested parsing layers can see them."
  {::p/wrap-parser
   (fn [parser]
     (fn [env tx]
       (let [children     (-> tx eql/query->ast :children)
             query-params (reduce
                            (fn [qps {:keys [type params] :as x}]
                              (cond-> qps
                                (and (not= :call type) (seq params)) (merge params)))
                            {}
                            children)
             env          (assoc env :query-params query-params)]
         (parser env tx))))})

(defn parser-args [{{:keys [trace? log-requests? log-responses?]} ::config} plugins resolvers]
  {::p/mutate  pc/mutate
   ::p/env     {::p/reader                 [p/map-reader pc/reader2 pc/index-reader
                                            pc/open-ident-reader p/env-placeholder-reader]
                ::p/placeholder-prefixes   #{">"}
                ::pc/mutation-join-globals [:tempids]}
   ::p/plugins (into []
                 (keep identity
                   (concat
                     [(pc/connect-plugin {::pc/register resolvers})]
                     plugins
                     [(p/env-plugin {::p/process-error process-error})
                      (when log-requests? (preprocess-parser-plugin log-request!))
                      ;; TODO: Do we need this, and if so, we need to pass the attribute map
                      ;(p/post-process-parser-plugin add-empty-vectors)
                      (p/post-process-parser-plugin p/elide-not-found)
                      (p/post-process-parser-plugin elide-reader-errors)
                      (when log-responses? (post-process-parser-plugin-with-env log-response!))
                      query-params-to-env-plugin
                      p/error-handler-plugin
                      (when trace? p/trace-plugin)])))})

(defn new-parser
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
  (let [real-parser (p/parser (parser-args config extra-plugins resolvers))
        {:keys [trace?]} (get config ::config {})]
    (fn wrapped-parser [env tx]
      (real-parser env (if trace?
                         (conj tx :com.wsscode.pathom/trace)
                         tx)))))
