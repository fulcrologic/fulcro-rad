(ns com.fulcrologic.rad.report)

(defn load-report! [app TargetReportClass {::keys     [id]
                                           ::rad/keys [target-route] :as options}]
  (let [{::rad/keys [BodyItem source-attribute]} (comp/component-options TargetReportClass)
        path (conj (comp/get-ident TargetReportClass {}) source-attribute)]
    (log/info "Loading report" source-attribute
      (comp/component-name TargetReportClass)
      (comp/component-name BodyItem))
    (df/load! app source-attribute BodyItem
      (merge
        options
        {:post-action (fn [{:keys [app]}] (io-complete! app id target-route))
         :target      path}))))
