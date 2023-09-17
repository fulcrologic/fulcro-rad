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
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.attributes-options :as ao]))

(defonce ^:private form-info-plugins (atom {}))

(defn add-form-info-plugin!
  "Add a plugin to the form info section of the form debugger. `k` is a keyword, and plugin is a map:

  ```
  {:title (fn [ form-props extra] string?)
   :render (fn [ form-props extra] react-element)}
  ```

  The `extra` parameter is a map that has:

  * `:form-instance` - The `this` of the form
  * `:key->attribute` - If a RAD form. keyword to attribute map for the form fields
  * `:validator` - The form-state validator for the form, if known

  Plugins are rendered in order of their sorted key.
  "
  [k plugin]
  (swap! form-info-plugins assoc k plugin))

(defn remove-form-info-plugin! [k]
  (swap! form-info-plugins dissoc k))

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

(defsc FormDiffViewer [this props]
  {:use-hooks? true}
  (let [diff (fs/dirty-fields props true)
        v    (fn [val] (if (nil? val) (dom/b "nil") (str val)))]
    (if (empty? diff)
      "No changes"
      (table :.ui.small.compact.table {}
        (thead nil
          (tr nil
            (th nil "ident")
            (th nil "field")
            (th nil "before")
            (th nil "after")))
        (tbody nil
          (for [ident (keys diff)
                :let [fields      (sort (keys (get diff ident)))
                      rowspan     (count fields)
                      first-field (first fields)]
                field fields
                :let [{:keys [before after]} (get-in diff [ident field])]]
            (tr {:key field}
              (when (= field first-field) (td {:rowSpan rowspan} (str ident)))
              (td nil (str field))
              (td nil (v before))
              (td nil (v after)))))))))

(def ui-form-diff-viewer (comp/computed-factory FormDiffViewer))

(add-form-info-plugin! ::save-delta {:title  "Form Save Delta"
                                     :render (fn [props cprops] (ui-form-diff-viewer props cprops))})

(defn- form-attributes
  "Gets all of the attributes in use by a form and its subforms."
  [form]
  (let [form-options    (comp/component-options form)
        base-attributes (fo/attributes form-options)
        subform-map     (form/subform-options form-options)
        subforms        (mapv fo/ui (vals subform-map))]
    (into base-attributes
      (mapcat
        #(comp/component-options % fo/attributes)
        subforms))))

(defsc RADAttributeInfo [this props {:keys [form-instance]}]
  {}
  (let [all-attributes (form-attributes form-instance)]
    (table :.ui.small.compact.table {}
      (thead nil
        (tr nil
          (th nil "Key")
          (th nil "Schema")
          (th :.center.aligned nil "Type")
          (th nil "Style")
          (th nil "Card.")
          (th nil "Req?")
          (th nil "Validator")))
      (tbody nil
        (for [{::attr/keys [qualified-key required? type valid? schema target style]
               :as         attr} (sort-by ao/qualified-key all-attributes)
              :let [required?        (if required? "Y" "N")
                    type-description (if (= type :ref)
                                       (str "ref => " target)
                                       (str (some-> type name)))
                    card             (if (attr/to-many? attr) "Many" "One")
                    valid            (if (fn? valid?) "Local")]]
          (tr {:key (str qualified-key)}
            (td nil (str qualified-key))
            (td nil (str (if schema (name schema) "--")))
            (td :.center.aligned nil type-description)
            (td nil (str (or style "default")))
            (td nil card)
            (td nil required?)
            (td nil (str valid))))))))

(def ui-rad-attribute-info (comp/computed-factory RADAttributeInfo))

(add-form-info-plugin! ::rad-info {:title  "RAD Attribute Field Info"
                                   :render (fn [props cprops] (ui-rad-attribute-info props cprops))})

(defsc FormInfo [this form-props {:keys [validator
                                         form-instance
                                         relation
                                         visited] :as cprops}]
  {:use-hooks? true}
  (let [{::fs/keys [id fields pristine-state subforms complete?]} (::fs/config form-props)
        state-map      (app/current-state this)
        key->attribute (comp/component-options form-instance ::form/key->attribute)
        [open-segments set-open-segments!] (hooks/use-state #(sorted-set ::current-state))
        expanded?      (fn [k] (contains? open-segments k))
        toggle!        (fn [k] (set-open-segments! (if (expanded? k) (disj open-segments k) (conj open-segments k))))]
    (if (seq fields)
      (div :.ui.segment {}
        (dom/h4 :.ui.header (str (comp/component-name form-instance) relation " " id))
        (dom/div :.ui.styled.fluid.accordion nil
          (let [expanded? (expanded? ::form-state)]
            (comp/fragment {}
              (div :.title {:onClick #(toggle! ::form-state)
                            :classes [(when expanded? "active")]}
                (dom/i :.dropdown.icon)
                (str "Current State"))
              (div :.content {:classes [(when expanded? "active")]}
                (when expanded?
                  (comp/fragment {}
                    (if (seq fields)
                      (comp/fragment {}
                        (dom/h4 :.ui.header "Fields")
                        (table :.ui.small.compact.striped.table
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
                              (concat fields (keys subforms))))))
                      (comp/fragment {}
                        (dom/h4 "Picker (or hidden)")))
                    (when (seq subforms)
                      (comp/fragment {}
                        (dom/h4 "Subforms")
                        (for [subform       (sort (keys subforms))
                              :let [value (get form-props subform)
                                    forms (cond
                                            (map? value) [value]
                                            (vector? value) value
                                            :else nil)]
                              subform-props forms
                              :let [ident         (-> subform-props ::fs/config ::fs/id)
                                    form-instance (comp/ident->any this ident)]
                              :when (not (contains? visited ident))]
                          (div {:key (str (or ident (hash subform-props)))}
                            (ui-form-info subform-props (-> cprops
                                                          (assoc :relation subform :form-instance form-instance)
                                                          (update :visited conj subform-props))))))))))))
          (when-not relation
            (for [plugin-key (sort (keys @form-info-plugins))
                  :let [{:keys [title render]} (get @form-info-plugins plugin-key)
                        expanded? (expanded? plugin-key)]]
              (comp/fragment {:key (str plugin-key)}
                (div :.title {:onClick #(toggle! plugin-key)
                              :classes [(when expanded? "active")]}
                  (dom/i :.dropdown.icon)
                  (str title))
                (div :.content {:classes [(when expanded? "active")]}
                  (when (and expanded? render)
                    (render form-props cprops))))))))
      (div (str (or id relation) " is a picker or is not visible as a subform")))))

(def ui-form-info
  "
  [props {:keys [form-instance validator]}]

  The UI for the form debugging information. Can be used directly.

  * `props` should be the props of the form in question

  The additional options map:
  * `form-instance` (REQURIED) - the `this` of the form.
  * `validator` (OPTIONAL) - the validator for the fields. If not supplied then the validation
  information will be inaccurate."
  (comp/computed-factory FormInfo))

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
  "Render a debug UI for a form (RAD or otherwise). This embeds an iframe with semantic UI CSS so
   that the debugger looks good independent of your page's CSS; however, if you are using SUI, then
   you may find it easier to place/format the info by just using `ui-form-info` directly."
  [form-instance]
  (let [validator (comp/component-options form-instance fo/validator)]
    (ui-form-debug-container {:form-instance form-instance
                              :validator     validator})))

(defn top-bottom-debugger
  "Use as the only item in the body of a RAD form. Will render the form first, and the debugger under it. Use `debugger`
   if you want to deal with your own layout. REQUIRES SEMANTIC UI, and does not work well within a SUI container class.

   If you supply `render`, a `(fn [] element)`, then it will be used instead of form/render-layout to render the form
   itself."
  ([form-instance props]
   (comp/fragment
     (div :.ui.basic.segment nil
       (error-boundary
         (let [validator (comp/component-options form-instance fo/validator)]
           (ui-form-info props {:form-instance form-instance
                                :validator     validator}))))
     (div {:width "100%"}
       (form/render-layout form-instance props))))
  ([form-instance props render]
   (comp/fragment
     (div :.ui.basic.segment nil
       (error-boundary
         (let [validator (comp/component-options form-instance fo/validator)]
           (ui-form-info props {:form-instance form-instance
                                :validator     validator}))))
     (div {:width "100%"}
       (render)))))

(defn side-by-side-debugger
  "Use as the only item in the body of a RAD form. Will render the form first, and the debugger under it. Use `debugger`
   if you want to deal with your own layout. REQUIRES SEMANTIC UI, and does not work well within a SUI container class.

   If you supply `render`, a `(fn [] element)`, then it will be used instead of form/render-layout to render the form
   itself."
  ([form-instance props]
   (div :.ui.grid {}
     (div :.eight.wide.column {}
       (form/render-layout form-instance props))
     (div :.eight.wide.column {}
       (error-boundary
         (let [validator (comp/component-options form-instance fo/validator)]
           (ui-form-info props {:form-instance form-instance
                                :validator     validator}))))))
  ([form-instance props render]
   (div :.ui.grid {}
     (div :.eight.wide.column {}
       (render))
     (div :.eight.wide.column {}
       (error-boundary
         (let [validator (comp/component-options form-instance fo/validator)]
           (ui-form-info props {:form-instance form-instance
                                :validator     validator})))))))
