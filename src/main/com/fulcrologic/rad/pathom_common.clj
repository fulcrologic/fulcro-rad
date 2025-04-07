(ns com.fulcrologic.rad.pathom-common
  "Helper functions that are used by both Pathom 2 and 3 support."
  (:require
   [clojure.walk :as walk]
   [taoensso.timbre :as log]))

(defn remove-omissions
  "Replaces black-listed keys from tx with :com.fulcrologic.rad.pathom/omitted, meant for logging tx's
  without logging sensitive details like passwords."
  [config tx]
  (let [sensitive-keys (conj (:sensitive-keys config #{})
                             :com.wsscode.pathom/trace)]
    (walk/postwalk
      (fn [x]
        (if (and (vector? x) (= 2 (count x)) (contains? sensitive-keys (first x)))
          [(first x) :com.fulcrologic.rad.pathom/omitted]
          x))
      tx)))

(defn- log!
  "Log a message and an EDN value, but limit the depth and length when printing the value."
  [env msg value]
  (binding [*print-level* 4 *print-length* 4]
    (let [{:keys [config]} env]
      (log/info msg
        (try
          (pr-str (remove-omissions config value))
          (catch Throwable e
            (log/error (.getMessage e))
            "<failed to serialize>"))))))

(defn log-request! [{:keys [env tx] :as req}]
  (log! env "Request: " tx)
  req)

(defn log-response!
  [env response]
  (log! env "Response: " response)
  response)

(defn process-error
  "If there were any exceptions in the parser that cause complete failure we
  respond with a well-known message that the client can handle."
  [env err]
  (let [msg  (.getMessage err)
        data (or (ex-data err) {})]
    (log/error err "Parser Error:" msg data)
    {:com.fulcrologic.rad.pathom/errors {:message msg
                                         :data    data}}))

(defn elide-reader-errors
  [input]
  (with-meta
    (walk/prewalk (fn [e] (when-not (= :com.wsscode.pathom.core/reader-error e) e)) input)
    (meta input)))

(defn combined-query-params [ast]
  (let [children     (:children ast)
        query-params (reduce
                       (fn [qps {:keys [type params] :as x}]
                         (cond-> qps
                           (and (not= :call type) (seq params)) (merge params)))
                       {}
                       children)]
    query-params))



