(ns com.fulcrologic.rad.rendering.semantic-ui.blob-field
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?@(:cljs [[com.fulcrologic.fulcro.dom :as dom :refer [div input]]
               [goog.object :as gobj]
               [com.fulcrologic.semantic-ui.modules.progress.ui-progress :refer [ui-progress]]
               [com.fulcrologic.fulcro.networking.file-upload :as file-upload]]
        :clj  [[com.fulcrologic.fulcro.dom-server :as dom :refer [div input]]])
    [com.fulcrologic.rad.form :as form]
    [clojure.core.async :as async]
    [com.fulcrologic.fulcro-i18n.i18n :refer [tr]]
    [com.fulcrologic.rad.attributes :as attr]
    [taoensso.timbre :as log]
    [com.fulcrologic.rad.blob :as blob]
    [com.fulcrologic.rad.options-util :refer [?! narrow-keyword]]
    [com.fulcrologic.rad.rendering.semantic-ui.components :refer [ui-wrapped-dropdown]]
    [com.fulcrologic.rad.rendering.semantic-ui.field :refer [render-field-factory]]
    [com.fulcrologic.rad.rendering.semantic-ui.form-options :as sufo]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]))

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
                     #?(:cljs
                        {:save-ref  (fn [r] (gobj/set this "fileinput" r))
                         :on-click  (fn [evt] (when-let [i (gobj/get this "fileinput")]
                                                (.click i)))
                         :on-change (fn [evt]
                                      (let [{::form/keys [form-instance]} (comp/props this)
                                            attribute (comp/get-computed this)
                                            file      (-> evt evt->js-files first)]
                                        (blob/upload-file! this attribute file {:file-ident (comp/get-ident form-instance)})))}))}
  #?(:cljs
     (let [props    (comp/props form-instance)
           url-key  (narrow-keyword qualified-key "url")
           status   (get props (blob/status-key qualified-key))
           progress (get props (blob/progress-key qualified-key))
           url      (get props url-key)
           {:keys [save-ref on-change on-click]} (comp/get-state this)
           label    (form/field-label env attribute)]
       (div :.field {:key     (str qualified-key)
                     :onClick (fn []
                                (when (not= status :uploading)
                                  (on-click)))}
         (dom/label label)
         (div :.ui.tiny.image
           (case status
             :uploading (dom/div :.ui.segment {:style {:minHeight "100px"}}
                          (dom/div :.ui.active.loader)
                          (ui-progress {:active true :percent (or progress 0) :attached "bottom"}))
             :failed (dom/div :.ui.segment {:style {:minHeight "100px"}}
                       "Upload failed.")
             (if (seq url)
               (dom/img :.ui.tiny.image {:src   url
                                         :style {:border "1px solid lightgray"}})
               (dom/div :.ui.segment {:style {:minHeight "100px"}})))
           (dom/input (cond-> {:id       (str qualified-key)
                               :ref      save-ref
                               :style    {:position "absolute"
                                          :opacity  0
                                          :top      0
                                          :right    0}
                               :key      (rand-int 1000000)
                               :onChange on-change
                               :type     "file"}
                        accept-file-types (assoc :allow (?! accept-file-types)))))))))

(def ui-image-upload-field (comp/computed-factory ImageUploadField
                             {:keyfn (fn [props] (some-> props comp/get-computed ::attr/qualified-key))}))

(defn render-image-upload [env attribute]
  (ui-image-upload-field env attribute))

(defsc FileUploadField [this
                        {::form/keys [form-instance master-form] :as env}
                        {::blob/keys [accept-file-types can-change?]
                         ::attr/keys [qualified-key] :as attribute}]
  {:componentDidMount (fn [this]
                        (comment "TRIGGER UPLOAD IF CONFIG SAYS TO?"))
   :initLocalState    (fn [this]
                        #?(:cljs
                           {:save-ref  (fn [r] (gobj/set this "fileinput" r))
                            :on-click  (fn [evt] (when-let [i (gobj/get this "fileinput")]
                                                   (.click i)))
                            :on-change (fn [evt]
                                         (let [env       (comp/props this)
                                               attribute (comp/get-computed this)
                                               file      (-> evt evt->js-files first)]
                                           (blob/upload-file! this attribute file {:file-ident []})))}))}
  (let [props     (comp/props form-instance)
        url-key   (blob/url-key qualified-key)
        name-key  (blob/filename-key qualified-key)
        url       (get props url-key)
        filename  (get props name-key)
        pct       (blob/upload-percentage props qualified-key)
        label     (form/field-label env attribute)
        top-class (sufo/top-class form-instance attribute)]
    (div {:className (or top-class "field")
          :key       (str qualified-key)}
      (dom/label label)
      (cond
        (blob/blob-downloadable? props qualified-key)
        (dom/a {:href (str url "?filename=" filename)} (tr "Download"))

        (blob/uploading? props qualified-key)
        (dom/div :.ui.small.blue.progress
          (div :.bar {:style {:transitionDuration "300ms"
                              :display            "block"
                              :width              pct}}
            (div :.progress pct)))))))

(def ui-file-upload-field (comp/computed-factory FileUploadField
                            {:keyfn (fn [props] (some-> props comp/get-computed ::attr/qualified-key))}))

(defn render-file-upload [env attribute]
  (ui-file-upload-field env attribute))
