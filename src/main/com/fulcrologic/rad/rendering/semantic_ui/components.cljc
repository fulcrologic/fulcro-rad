(ns com.fulcrologic.rad.rendering.semantic-ui.components
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.transit :as ftransit]
    [taoensso.timbre :as log]))

(defn sui-format->user-format
  "Converts transit encoded value(s), used by Semantic UI, into CLJS datastructure."
  [{:keys [multiple]} value]
  (if multiple
    (into [] (map ftransit/transit-str->clj value))
    (ftransit/transit-str->clj value)))

(defn user-format->sui-format [{:keys [multiple]} value]
  "Converts CLJS datastructure into transit encoded string(s), usable by Semantic UI."
  (if multiple
    (if value
      (to-array (map ftransit/transit-clj->str value))
      #js [])
    (if (or value (boolean? value))
      (ftransit/transit-clj->str value)
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
                        (let [xform-options (memoize (fn [options]
                                                       (clj->js (mapv (fn [{:keys [text value]}]
                                                                        #js {:text text :value (some-> value (ftransit/transit-clj->str))})
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
                          {:value    value
                           :options  options
                           :onChange (fn [e v]
                                       (try
                                         (let [string-value (.-value v)
                                               value        (if multiple
                                                              (mapv #(when (seq %) (ftransit/transit-str->clj %)) string-value)
                                                              (when (seq string-value) (ftransit/transit-str->clj string-value)))]
                                           (when userOnChange
                                             (userOnChange value)))
                                         (catch :default e
                                           (log/error "Unable to read dropdown value " e (when v (.-value v))))))})]
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

