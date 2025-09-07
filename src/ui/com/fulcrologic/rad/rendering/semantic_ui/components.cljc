(ns com.fulcrologic.rad.rendering.semantic-ui.components
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.transit :as ftransit]
    [taoensso.timbre :as log]))

(defn sui-format->user-format
  "Converts transit encoded value(s), used by Semantic UI, into CLJS datastructure."
  [{:keys [multiple]} value]
  (if multiple
    (into [] (map (fn [v] (ftransit/transit-str->clj v {:metadata? false})) value))
    (ftransit/transit-str->clj value {:metadata? false})))

(defn user-format->sui-format [{:keys [multiple]} value]
  "Converts CLJS datastructure into transit encoded string(s), usable by Semantic UI."
  (if multiple
    (if value
      (to-array (map (fn [v] (ftransit/transit-clj->str v {:metadata? false})) value))
      #js [])
    (if (or value (boolean? value))
      (ftransit/transit-clj->str value {:metadata? false})
      "")))

(defn wrapped-onChange
  "Wraps userOnChange fn with try/catch and sui-form->user-format conversion."
  [props userOnChange]
  (fn [_ v]
    #?(:cljs
       (try
         (if (and (.-value v) (seq (.-value v)))
           (let [value (sui-format->user-format props (.-value v))]
             (when (and value userOnChange) (userOnChange value)))
           (userOnChange nil))
         (catch :default e
           (log/error e "Unable to read dropdown value " (when v (.-value v))))))))

(defsc WrappedDropdown [this {:keys [onChange value multiple] :as props}]
  {:initLocalState (fn [this]
                     #?(:cljs
                        (let [xform-options (memoize
                                              (fn [options]
                                                (clj->js (mapv (fn [option]
                                                                 (update option :value (fn [v] (ftransit/transit-clj->str v {:metadata? false}))))
                                                           options))))
                              xform-value   (fn [multiple? value]
                                              (user-format->sui-format {:multiple multiple?} value))]
                          {:get-options  (fn [props] (xform-options (:options props)))
                           :format-value (fn [props value] (xform-value (:multiple props) value))})))}
  #?(:cljs
     (let [{:keys [get-options format-value]} (comp/get-state this)
           userOnChange onChange
           options      (get-options props)
           value        (format-value props value)
           props        (merge
                          {:search             true
                           :selection          true
                           :closeOnBlur        true
                           :openOnFocus        true
                           :selectOnBlur       true
                           :selectOnNavigation true
                           :multiple           (boolean multiple)}
                          props
                          {:value       value
                           :searchInput #js {:children (fn [SearchInput ^js js-props]
                                                         ;; HACK for Chrome
                                                         (set! (.-autoComplete js-props) "no-autocomplete")
                                                         (dom/create-element SearchInput js-props))}
                           :options     options
                           :onChange    (fn [e v]
                                          (try
                                            (let [string-value (.-value v)
                                                  value        (if multiple
                                                                 (mapv #(when (seq %) (ftransit/transit-str->clj % {:metadata? false})) string-value)
                                                                 (when (seq string-value) (ftransit/transit-str->clj string-value {:metadata? false})))]
                                              (when userOnChange
                                                (userOnChange value)))
                                            (catch :default e
                                              ;; Note: With allowAdditions enabled the value will be the raw user-typed string, not transit-encoded
                                              ;; clj value. We can this safely ignore its error here and assume the user handles it in :onAddItem
                                              (when-not (and (.-allowAdditions v) (not (.find (.-options v) #(= (.-value %) (.-value v)))))
                                                (log/debug "Unable to read dropdown value " e (when v (.-value v)))))))})]
       (ui-dropdown props))
     :clj
     (dom/div :.ui.selection.dropdown
       (dom/input {:type "hidden"})
       (dom/i :.dropdown.icon)
       (dom/div :.default.text "")
       (dom/div :.menu))))

(def ui-wrapped-dropdown
  "Draw a SUI dropdown with the given props.  The arguments are identical to sui/ui-dropdown, but options and onChange
  are auto-wrapped so that clojure data (e.g. keywords) can be used for the option :value fields. It also defaults
  a number of things (:search, :closeOnBlue, openOnFocus, selectOnBlue, and :selectOnNavigation) to true, but you can"
  (comp/factory WrappedDropdown))

