(ns com.example.components.parser
  (:require
    [clojure.walk :as walk]
    [com.example.components.config :as s.config]
    [com.example.model.account :as account]
    [com.wsscode.common.async-clj :refer [let-chan]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [edn-query-language.core :as eql]
    [mount.core :refer [defstate]]
    [taoensso.timbre :as log]))

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

(def omitted-from-logs #{:password :user/password :data-uris})

(defn remove-omissions
  "Replaces black-listed keys from tx with ::omitted, meant for logging tx's
  without logging sensitive details like passwords."
  [tx]
  (walk/postwalk
    (fn [x]
      (if (and (vector? x) (= 2 (count x)) (contains? omitted-from-logs (first x)))
        [(first x) ::omitted]
        x))
    tx))

(defn log-requests [{:keys [env tx] :as req}]
  (binding [*print-level* 4 *print-length* 4]
    (let [{:current/keys [user firm]} env]
      (log/info
        (str "user-id: " (:db/id user)
          (when (:db/id firm) (str "firm-id: " (:db/id firm))))
        "transaction:"
        (try
          (pr-str (remove-omissions tx))
          (catch Throwable e
            (log/error (.getMessage e))
            "<failed to serialize tx>")))))
  req)

(defn env-with-current-info
  "Adds stuff to mutation/resolver env."
  [env]
  (let [;db      (d/db s.database/connection)
        request (:request env)
        env*    (assoc env
                  ;; :db-atom (atom db)
                  ;; :conn s.database/connection
                  :request request)]
    env*))

(defn add-current-info
  [{:keys [env tx] :as req}]
  (let [env* (env-with-current-info env)]
    {:env env* :tx tx}))

(defn process-error
  "If there were any exceptions in the parser their details are put in
  a place pm/pmutate! can recognize."
  [env err]
  (let [msg  (.getMessage err)
        data (or (ex-data err) {})]
    (log/error "Parser Error:" msg data)
    {::mutation-errors {:message msg
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

(defn log-response
  [env input]
  (let [{:current/keys [user-id firm-id]} (env-with-current-info env)]
    (binding [*print-level* 4 *print-length* 4]
      (log/info "user-id:" user-id "firm-id:" firm-id "response"
        (if (map? input)
          (dissoc input :com.wsscode.pathom/trace)
          input)))
    input))

(defn add-empty-vectors
  "For cardinality many attributes, replaces ::p/not-found with an empty vector."
  [input]
  (let [attr-types {}]                                      ; TODO: not found to-many remapping
    (with-meta
      (p/transduce-maps (map (fn [[k v]]
                               (if (and (= ::p/not-found v) (= :many (some-> k attr-types first)))
                                 [k []]
                                 [k v])))
        input)
      (meta input))))

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

(def parser-args
  {::p/mutate  pc/mutate
   ::p/env     {::p/reader               [p/map-reader pc/reader2 pc/index-reader
                                          pc/open-ident-reader p/env-placeholder-reader]
                ::p/placeholder-prefixes #{">"}}
   ::p/plugins [(pc/connect-plugin {::pc/register [account/login]})
                (p/env-plugin {::p/process-error process-error})
                (p/env-wrap-plugin (fn [env]
                                     (assoc env
                                       ;; :connection s.database/connection
                                       :config s.config/config)))
                (preprocess-parser-plugin log-requests)
                (preprocess-parser-plugin add-current-info)
                (p/post-process-parser-plugin add-empty-vectors)
                (p/post-process-parser-plugin p/elide-not-found)
                (p/post-process-parser-plugin elide-reader-errors)
                (post-process-parser-plugin-with-env log-response)
                query-params-to-env-plugin
                p/error-handler-plugin
                p/trace-plugin]})

(defstate parser
  :start
  (let [real-parser (p/parser parser-args)
        trace?      (not (nil? (System/getProperty "trace")))]
    (fn wrapped-parser [env tx]
      (real-parser env (if trace?
                         (conj tx :com.wsscode.pathom/trace)
                         tx)))))
