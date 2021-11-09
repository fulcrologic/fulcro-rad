(ns com.fulcrologic.rad.debugging
  "A ns containing helpers for debugging live components"
  (:require
    [clojure.string :as str]
    #?@(:clj  [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input h2 table thead tr tbody td th]]]
        :cljs [["react-dom" :refer [createPortal]]
               [com.fulcrologic.fulcro.dom :as dom :refer [div label input h2 table thead tr tbody td th]]])
    [com.fulcrologic.fulcro.algorithms.denormalize :refer [db->tree]]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [taoensso.timbre :as log]))

(defn- eiframe [js-props]
  #?(:cljs
     (let [[ref set-content-ref!] (hooks/use-state nil)
           head-children (.-head ^js js-props)
           body-children (.-body ^js js-props)
           props         (-> (.-iprops ^js js-props)
                           (assoc :ref set-content-ref!))
           head-node     (some-> ^js ref (.-contentWindow) (.-document) (.-head))
           body-node     (some-> ^js ref (.-contentWindow) (.-document) (.-body))]
       (dom/create-element "iframe" (clj->js props)
         #js [(when head-node (createPortal (clj->js head-children) head-node))
              (when body-node (createPortal (clj->js body-children) body-node))]))))

(defn ui-embedded-iframe
  "Renders the given `head-children` and `body-children` into an iframe's HEAD and BODY elements via portals. Allows
   `props` is passed directly as the props of the iframe itself."
  [props head-children body-children]
  (dom/create-element eiframe #js {:iprops props
                                   :head   head-children
                                   :body   body-children}))

(defn ident->on-screen-forms [app-ish ident]
  (let [on-screen-components (comp/ident->components app-ish ident)
        forms                (filter #(comp/component-options % :form-fields) on-screen-components)]
    forms))

(declare ui-form-info)

(defsc FormInfo [this props {:keys [forms-by-ident
                                    validator
                                    visited] :as cprops}]
  {:query                 ['*]
   :shouldComponentUpdate (fn [] true)}
  (let [{::fs/keys [id fields pristine-state subforms complete?]} props
        on-screen-components (ident->on-screen-forms this id)
        state-map            (app/current-state this)
        query                (some-> on-screen-components first (comp/get-query state-map))
        form-props           (db->tree query (get-in state-map id) state-map)]
    (div :.ui.segment {}
      (dom/h4 :.ui.header (str "Form Ident: " id)
        (dom/div {}
          (str "Mounted as " (str/join "," (map #(comp/component-name %) on-screen-components)))))
      (div :.ui.segment
        (div "Fields")
        (table :.ui.table
          (thead nil
            (tr nil
              (th nil "Field")
              (th nil "Original")
              (th nil "Current")
              (th nil "Complete?")
              (th nil "V?")))
          (tbody nil
            (map-indexed
              (fn [idx f]
                (let [subform?        (contains? subforms f)
                      valid-ident?    #(and (vector? %) (keyword? (first %)) (not (nil? (second %))))
                      current-refs    (when subform? (get-in state-map (conj id f)))
                      pristine-refs   (when subform? (get pristine-state f))
                      to-many?        (and
                                        subform?
                                        (or
                                          (every? valid-ident? current-refs)
                                          (every? valid-ident? pristine-refs)))
                      bad-idents?     (fn [refs]
                                        (if to-many?
                                          (not (every? valid-ident? refs))
                                          (and refs (not (valid-ident? refs)))))
                      current-error?  (and subform? (bad-idents? current-refs))
                      pristine-error? (and subform? (bad-idents? pristine-refs))]
                  (tr {:key idx}
                    (td nil (str f))
                    (td {:classes (when current-error? "error")} (str (if (subforms f)
                                                                        (get-in state-map (conj id f))
                                                                        (get pristine-state f))))
                    (td {:classes (when pristine-error? "error")} (str (get pristine-state f)))
                    (td nil (if (contains? complete? f) "Y" "N"))
                    (td nil (if validator
                              (str (validator form-props f))
                              "--")))))
              (concat fields (keys subforms)))))
        (when (seq subforms)
          (comp/fragment {}
            (h2 "Subforms")
            (for [subform (keys subforms)
                  :let [value  (get-in state-map (conj id subform))
                        idents (cond
                                 (every? vector? value) value
                                 (vector? value) [value]
                                 :else nil)]
                  id      idents
                  :when (not (contains? visited id))
                  :let [subform-config (get-in state-map [::fs/forms-by-ident {:table (first id)
                                                                               :row   (second id)}])]]
              (div
                (dom/h4 (str subform))
                (ui-form-info subform-config (update cprops :visited conj id))))))))))

(def ui-form-info (comp/computed-factory FormInfo))

(letfn [(form-item [select! idx {::fs/keys [id]}]
          (div :.item {:key idx}
            (dom/a {:onClick #(select! {:table (first id)
                                        :row   (second id)})} (str id))))]
  (defsc Forms [this {:ui/keys  [selected]
                      ::fs/keys [forms-by-ident] :as props} {:keys [validator]}]
    {:query                 [:ui/selected
                             [::fs/forms-by-ident '_]
                             [::uism/asm-id '_]]
     :shouldComponentUpdate (fn [] true)
     :initial-state         {}
     :ident                 (fn [] [::components ::Forms])}
    (let [select!   #(m/set-value!! this :ui/selected %)
          form-item (partial form-item select!)
          {on-screen true
           hidden    false} (group-by
                              (fn [form] (boolean (seq (ident->on-screen-forms this (::fs/id form)))))
                              (vals forms-by-ident))]
      (if selected
        (div {}
          (dom/a {:onClick #(select! nil)} "Back")
          (ui-form-info (get forms-by-ident selected {}) {:forms-by-ident forms-by-ident
                                                          :validator      validator
                                                          :visited        #{}}))
        (div {}
          (h2 "Mounted")
          (dom/div :.ui.list
            (map-indexed form-item on-screen))
          (h2 "Not Mounted")
          (dom/div :.ui.list
            (map-indexed form-item hidden)))))))

(def ui-forms (comp/computed-factory Forms))

(defsc FormDebugContainer [this {:keys [validator] :as props}]
  {:use-hooks? true}
  (let [[open? set-open!] (hooks/use-state true)
        app         (comp/any->app this)
        forms-props (hooks/use-component app Forms {:initialize?    true
                                                    :keep-existing? true})]
    (div {:style {:width "100%"}}
      (when-not open?
        (dom/a {:onClick #(set-open! true)
                :style   {:position "fixed"
                          :bottom   0
                          :zIndex   60000
                          :right    0}} "forms debugger"))
      (when open?
        (ui-embedded-iframe {:style {:width   "100%"
                                     :height  "300px"
                                     :display (if open? "block" "none")}}
          (dom/link {:href "https://cdn.jsdelivr.net/npm/fomantic-ui@2.7.8/dist/semantic.min.css" :rel "stylesheet"})
          (div :.ui.segment {:style {:backgroundColor "rgb(235,245,250)"}}
            (dom/button :.ui.right.floated.icon.button {:onClick #(set-open! false)}
              (dom/i :.times.icon))
            (ui-forms forms-props props)))))))

(def ui-form-debug-container (comp/factory FormDebugContainer))
