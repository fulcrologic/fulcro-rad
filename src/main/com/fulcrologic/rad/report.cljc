(ns com.fulcrologic.rad.report
  (:require
    [com.fulcrologic.rad.controller :as controller :refer [io-complete!]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp]
    [taoensso.timbre :as log]))

(defn load-report! [app TargetReportClass {::controller/keys [id]
                                           ::rad/keys        [target-route] :as options}]
  (let [{::rad/keys [BodyItem source-attribute]} (comp/component-options TargetReportClass)
        path (conj (comp/get-ident TargetReportClass {}) source-attribute)]
    (log/info "Loading report" source-attribute
      (comp/component-name TargetReportClass)
      (comp/component-name BodyItem))
    (df/load! app source-attribute BodyItem
      (merge
        options
        {:post-action (fn [{:keys [app]}] (io-complete! app {::controller/id    id
                                                             ::rad/target-route target-route}))
         :target      path}))))
