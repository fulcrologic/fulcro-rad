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
    [com.fulcrologic.fulcro.react.error-boundaries :refer [error-boundary]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.rad.form-options :as fo]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.form :as form]
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

(defsc FormInfo [this form-props {:keys [validator
                                         form-instance
                                         relation
                                         visited] :as cprops}]
  {:use-hooks? true}
  (let [{::fs/keys [id fields pristine-state subforms complete?]} (::fs/config form-props)
        state-map      (app/current-state this)
        key->attribute (comp/component-options form-instance ::form/key->attribute)]
    (div :.ui.segment {}
      (dom/h4 :.ui.header (str (if relation relation "Form Ident") " - " id "  " (comp/component-name form-instance)))
      (div :.ui.segment
        (dom/h4 :.ui.header "Fields")
        (table :.ui.small.compact.table
          (thead nil
            (tr nil
              (th nil "Field")
              (th nil "Original")
              (th nil "Current")
              (th nil "Complete?")
              (th nil "V?")
              (th nil "Notes")))
          (tbody nil
            (map-indexed
              (fn [idx k]
                (let [subform?          (contains? subforms k)
                      valid-ident?      #(and (vector? %) (keyword? (first %)) (not (nil? (second %))))
                      current-value     (get-in state-map (conj id k)) ; idents, not denormalized from form-props
                      pristine-refs     (when subform? (get pristine-state k))
                      attr              (get key->attribute k)
                      expected-to-many? (some-> attr (attr/to-many?))
                      to-many?          (boolean
                                          (and
                                            subform?
                                            (or
                                              (every? valid-ident? current-value)
                                              (every? valid-ident? pristine-refs))))
                      bad-cardinality?  (and (boolean? expected-to-many?)
                                          (not= expected-to-many? to-many?))
                      bad-idents?       (fn [refs]
                                          (if to-many?
                                            (not (every? valid-ident? refs))
                                            (and refs (not (valid-ident? refs)))))
                      current-error?    (and subform? (bad-idents? current-value))
                      pristine-error?   (and subform? (bad-idents? pristine-refs))
                      validation-code   (if validator
                                          (validator form-props k)
                                          "--")
                      invalid?          (= :invalid validation-code)]
                  (tr {:key idx}
                    (td nil (str k))
                    (td {:classes [(when (or pristine-error? bad-cardinality?) "error")]} (str (get pristine-state k)))
                    (td {:classes [(when (or invalid? current-error? bad-cardinality?) "error")]} (str current-value))
                    (td nil (if (contains? complete? k) "Y" "N"))
                    (td nil (if validator (str validation-code) "--"))
                    (td nil
                      (cond-> []
                        (nil? attr) (conj (dom/p {:key "noattr"} "Could not find the RAD attribute definition on the form."))
                        bad-cardinality? (conj (dom/p {:key "badcard"} "The cardinality of the attribute does not match data."))
                        (or current-error? pristine-error?) (conj (dom/p {:key "badidents"} "One or more of the idents are invalid"))
                        )))))
              (concat fields (keys subforms)))))
        (when (seq subforms)
          (comp/fragment {}
            (dom/h4 "Subforms")
            (for [subform       (keys subforms)
                  :let [value (get form-props subform)
                        forms (cond
                                (map? value) [value]
                                (vector? value) value
                                :else nil)]
                  subform-props forms
                  :let [ident         (-> subform-props ::fs/config ::fs/id)
                        form-instance (comp/ident->any this ident)]
                  :when (not (contains? visited ident))]
              (div {:key (str ident)}
                (ui-form-info subform-props (-> cprops
                                              (assoc :relation subform :form-instance form-instance)
                                              (update :visited conj subform-props)))))))))))

(def ui-form-info (comp/computed-factory FormInfo))

(defsc FormDebugContainer [this {:keys [width height
                                        form-instance
                                        validator] :as options}]
  {}
  (let [form-props (comp/props form-instance)]
    (ui-embedded-iframe {:style {:border "none"
                                 :width  (or width "100%")
                                 :height (or height "100%")}}
      (dom/link {:href "https://cdn.jsdelivr.net/npm/fomantic-ui@2.7.8/dist/semantic.min.css" :rel "stylesheet"})
      (div :.ui.segment {:style {:backgroundColor "rgb(235,245,250)"}}
        (ui-form-info form-props options)))))

(def ui-form-debug-container (comp/factory FormDebugContainer))

(defn debugger
  "Render a debug UI for a form (RAD or otherwise)."
  [form-instance]
  (let [validator (comp/component-options form-instance fo/validator)]
    (ui-form-debug-container {:form-instance form-instance
                              :validator     validator})))

(defn top-bottom-debugger
  "Use as the only item in the body of a RAD form. Will render the form first, and the debugger under it. Use `debugger`
   if you want to deal with your own layout."
  [form-instance props]
  (div nil
    (div {:width "100%"}
      (form/render-layout form-instance props))
    (div {:style {:width  "100%"
                  :height "2000px"}}
      (error-boundary
        (debugger form-instance)))))

(defn side-by-side-debugger
  "Use as the only item in the body of a RAD form. Will render the form first, and the debugger under it. Use `debugger`
   if you want to deal with your own layout. REQUIRES SEMANTIC UI, and does not work well within a SUI container class."
  [form-instance props]
  (div :.ui.grid {}
    (div :.eight.wide.column {}
      (form/render-layout form-instance props))
    (div :.eight.wide.column {}
      (error-boundary
        (let [validator (comp/component-options form-instance fo/validator)]
          (ui-form-info props {:form-instance form-instance
                               :validator     validator}))))))