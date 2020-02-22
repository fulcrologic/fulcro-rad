(ns com.fulcrologic.rad.rendering.semantic-ui.blob-field
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?@(:cljs [[com.fulcrologic.fulcro.dom :as dom :refer [div input]]
               [goog.object :as gobj]
               [com.fulcrologic.fulcro.networking.file-upload :as file-upload]]
        :clj  [[com.fulcrologic.fulcro.dom-server :as dom :refer [div input]]])
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.attributes :as attr]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.ui-validation :as validation]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.options-util :refer [?! narrow-keyword]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]))

(defn evt->js-files [evt]
  #?(:cljs
     (let [js-file-list (.. evt -target -files)]
       (map (fn [file-idx]
              (let [js-file (.item js-file-list file-idx)
                    name    (.-name js-file)]
                js-file))
         (range (.-length js-file-list))))))

(defsc ImageUploadField [this
                         {::form/keys [form-instance] :as env}
                         {::blob/keys [accept-file-types]
                          ::attr/keys [qualified-key] :as attribute}]
  {:initLocalState (fn [this]
                     {:save-ref  (fn [r] (gobj/set this "fileinput" r))
                      :on-click  (fn [evt] (when-let [i (gobj/get this "fileinput")]
                                             (.click i)))
                      :on-change (fn [evt]
                                   (let [env       (comp/props this)
                                         attribute (comp/get-computed this)
                                         file      (-> evt evt->js-files first)]
                                     (blob/upload-file! env attribute file)))})}
  (let [props              (comp/props form-instance)
        url-key            (narrow-keyword qualified-key "url")
        current-sha        (get props qualified-key)
        url                (get props url-key)
        has-current-value? (seq current-sha)
        {:keys [save-ref on-change on-click]} (comp/get-state this)
        upload-complete?   false
        label              (form/field-label env attribute)
        valid?             (and upload-complete? has-current-value?)]
    (div :.field {:key (str qualified-key)}
      (dom/label label)
      (div :.ui.tiny.image
        (dom/img {:src     url :width "100"
                  :onClick on-click})
        (dom/input (cond-> {:id       (str qualified-key)
                            :ref      save-ref
                            :style    {:position "absolute"
                                       :opacity  0
                                       :top      0
                                       :right    0}
                            :onChange on-change
                            :type     "file"}
                     accept-file-types (assoc :allow (?! accept-file-types))))))))

(def ui-image-upload-field (comp/computed-factory ImageUploadField
                             {:keyfn (fn [props] (some-> props comp/get-computed ::attr/qualified-key))}))

(defn render-image-upload [env attribute]
  (ui-image-upload-field env attribute))
