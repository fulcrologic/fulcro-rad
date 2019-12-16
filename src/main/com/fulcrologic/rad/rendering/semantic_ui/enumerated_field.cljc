(ns com.fulcrologic.rad.rendering.semantic-ui.enumerated-field
  (:require
    #?@(:cljs
        [[com.fulcrologic.fulcro.dom :as dom :refer [div label input]]
         [com.fulcrologic.semantic-ui.modules.dropdown.ui-dropdown :refer [ui-dropdown]]]
        :clj
        [[com.fulcrologic.fulcro.dom-server :as dom :refer [div label input]]])
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.transit :as ftransit]
    [com.fulcrologic.rad.attributes :as attr]
    [clojure.string :as str]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.mutations :as m]))

(defn sui-format->user-format
  "Converts transit encoded value(s), used by Semantic UI, into CLJS datastructure."
  [{:keys [multiple]} value]
  (log/info value)
  (if multiple
    (into [] (map ftransit/transit-str->clj value))
    (ftransit/transit-str->clj value)))

(defn user-format->sui-format [{:keys [multiple]} value]
  "Converts CLJS datastructure into transit encoded string(s), usable by Semantic UI."
  (log/info value)
  (if multiple
    (if value
      (to-array (map ftransit/transit-clj->str value))
      #js [])
    (if value (ftransit/transit-clj->str value))))

(defn wrapped-onChange
  "Wraps userOnChange fn with try/catch and sui-form->user-format conversion."
  [props userOnChange]
  (fn [_ v]
    (try
      (if (and (.-value v) (seq (.-value v)))
        (let [value (sui-format->user-format props (.-value v))]
          (when (and value userOnChange) (userOnChange value)))
        (userOnChange nil))
      (catch :default e
        (log/error e "Unable to read dropdown value " (when v (.-value v)))))))

(defsc WrappedDropdown [this {:keys [onChange value multiple] :as props}]
  (let [userOnChange onChange
        options      (mapv (fn [{:keys [text value]}] {:text text :value (ftransit/transit-clj->str value)}) (:options props))
        value        (when (or value (boolean? value))
                       (if multiple
                         (mapv #(ftransit/transit-clj->str %) value)
                         (ftransit/transit-clj->str value)))
        props        (merge
                       {:search             true
                        :selection          true
                        :closeOnBlur        true
                        :openOnFocus        true
                        :selectOnBlur       true
                        :selectOnNavigation true}
                       props
                       {:value    value
                        :options  options
                        :onChange (fn [e v]
                                    (try
                                      (let [value (if multiple
                                                    (mapv #(ftransit/transit-str->clj %) (.-value v))
                                                    (ftransit/transit-str->clj (.-value v)))]
                                        (when (and (or value (boolean? value)) userOnChange)
                                          (userOnChange value)))
                                      (catch :default e
                                        (log/error "Unable to read dropdown value " e (when v (.-value v))))))})]
    (ui-dropdown props)))

(def ui-wrapped-dropdown
  "Draw a SUI dropdown with the given props.  The arguments are identical to sui/ui-dropdown, but options and onChange
  are auto-wrapped so that clojure data (e.g. keywords) can be used for the option :value fields. It also defaults
  a number of things (:search, :closeOnBlue, openOnFocus, selectOnBlue, and :selectOnNavigation) to true, but you can"
  (comp/factory WrappedDropdown))

(defn render-field [this attribute props]
  (let [k          (::attr/qualified-key attribute)
        {::form/keys [field-label]} attribute
        read-only? (form/read-only? this attribute)
        options    (mapv (fn [k]
                           {:text (name k) :value k}) (::attr/enumerated-values attribute))
        value      (get props k)]
    #?(:cljs
       (div :.ui.field {:key (str k)}
         (label (or field-label (some-> k name str/capitalize)))
         (ui-wrapped-dropdown {:disabled read-only?
                               :options  options
                               :value    value
                               :onChange (fn [v]
                                           (log/spy :info (comp/get-computed this))
                                           (form/input-changed! this k v))}))
       :clj
       (dom/div :.ui.selection.dropdown
         (dom/input {:type "hidden"})
         (dom/i :.dropdown.icon)
         (dom/div :.default.text "")
         (dom/div :.menu)))))

