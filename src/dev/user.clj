(ns user
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [expound.alpha :as expound]
    [clojure.spec.alpha :as s]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.resolvers :as res]
    [com.example.model.employee :as employee]
    [com.example.model.account :as account]
    ))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/example")
(alter-var-root #'s/*explain-out* (constantly expound/printer))
(log/merge-config!
  {:level      :info
   :middleware (if (System/getProperty "dev")
                 [(fn [{:keys [level vargs] :as data}]
                    (if (and (= :debug level) (= "=>" (second vargs)))
                      (update-in data [:vargs 2] #(with-out-str (clojure.pprint/pprint %)))
                      data))]
                 [])})


(comment
  (res/entity->resolvers :production account/account))
