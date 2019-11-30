(ns user
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.spec.alpha :as s]
    [clojure.tools.namespace.repl
     :as tools-ns
     :refer [disable-reload! refresh clear set-refresh-dirs]]
    [com.example.components.middleware]
    [expound.alpha :as expound]
    [taoensso.timbre :as log]))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/example")

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(log/merge-config!
  {:level      :info
   :middleware
   (if (System/getProperty "dev")
     [(fn [{:keys [level vargs] :as data}]
        (if (and (= :debug level)
              (= "=>" (second vargs)))
          (update-in data [:vargs 2]
            #(with-out-str (clojure.pprint/pprint %)))
          data))]
     [])})

(defn in-ns-dev []
  (in-ns 'development)
  :loaded)

(defn dev
  "Loads all source files and switches to the 'dev' namespace. Throws
  any exceptions that may be thrown during compilation."
  []
  (let [ret (refresh :after `in-ns-dev)]
    (if (instance? Throwable ret)
      (throw ret)
      ret)))
