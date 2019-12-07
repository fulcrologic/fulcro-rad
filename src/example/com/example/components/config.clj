(ns com.example.components.config
  (:require
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.server.config :as fserver]
    [mount.core :refer [defstate args]]
    [taoensso.timbre :as log]
    [clojure.string :as str]))

(defmacro p
  "Convert a data structure to a spy-like output string."
  [v]
  `(str
     ~(str "\n" v "\n================================================================================\n")
     (with-out-str (pprint ~v))
     "================================================================================"))

(defn pretty
  "Marks a data item for pretty formatting when logging it."
  [v]
  (with-meta v {:pretty true}))

(defn custom-output-fn
  "Derived from Timbre's default output function."
  ([data] (custom-output-fn nil data))
  ([opts data]
   (let [{:keys [no-stacktrace?]} opts
         {:keys [level ?err msg_ ?ns-str ?file timestamp_ ?line]} data]
     (str
       ;(force timestamp_) " "
       ;(str/upper-case (name level)) " "
       (str/replace (or ?ns-str ?file "?")
         #"^com\.[^.]*\." "_") ":" (or ?line "?") " - "
       (force msg_)
       (when-not no-stacktrace?
         (when-let [err ?err]
           (str "\n" (log/stacktrace err opts))))))))


(defn start-logging! [config]
  (let [{:keys [taoensso.timbre/logging-config]} config]
    (log/merge-config!
      (assoc logging-config
        :middleware [(fn [data]
                       (update data :vargs (fn [args]
                                             (mapv
                                               (fn [v]
                                                 (if (-> v meta :pretty)
                                                   (with-out-str (pprint v))
                                                   v))
                                               args))))]
        :output-fn custom-output-fn))
    (log/info "Configured Timbre" (pretty logging-config))))

(defstate config
  "The overrides option in args is for overriding
   configuration in tests."
  :start (let [{:keys [config overrides]
                :or   {config "config/dev.edn"}} (args)
               loaded-config (merge (fserver/load-config {:config-path config}) overrides)]
           (log/warn "Loading config" config)
           (start-logging! loaded-config)
           loaded-config))


