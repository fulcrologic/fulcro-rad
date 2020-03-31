(ns com.fulcrologic.rad.routing.html5-history
  "An implementation of RAD's RouteHistory protocol, wrapping a browser's location and History API. This implementation
   will put an string-valued route parameters onto the query parameter section of the URI when a route is pushed or replaced,
   and will merge the current URL's query parameters with returned route params."
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.routing :as routing]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.routing.history :as history :refer [RouteHistory]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.net URLDecoder URLEncoder)
                   (java.nio.charset StandardCharsets))))

(defn decode-uri-component
  "Decode the given string as a transit and URI encoded CLJ(s) value."
  [v]
  (let [v #?(:clj (URLDecoder/decode ^String v (.toString StandardCharsets/UTF_8))
             :cljs (js/decodeURIComponent v))]
    (if (str/starts-with? v "_")
      (try
        (transit-str->clj (str/replace v #"^_" ""))
        (catch #?(:clj Exception :cljs :default) _
          (log/error "Unable to decode transit. Resorting to plain decoding" v)
          v))
      v)))

(>defn encode-uri-component
  "Encode a key/value pair of CLJ(s) data such that it can be safely placed in browser query params. If `v` is
   a plain string, then it will not be transit-encoded."
  [v]
  [any? => string?]
  (let [v (if (string? v) v (str "_" (transit-clj->str v)))]
    #?(:clj  (URLEncoder/encode ^String v (.toString StandardCharsets/UTF_8))
       :cljs (js/encodeURIComponent v))))

(>defn query-params
  [raw-search-string]
  [string? => map?]
  (let [param-string (str/replace raw-search-string #"^[?]" "")]
    (into {}
      (keep (fn [assignment]
              (let [[k v] (str/split assignment #"=")]
                (when (and k v)
                  [(keyword (decode-uri-component k)) (decode-uri-component v)]))))
      (str/split param-string #"&"))))

(>defn query-string
  "Convert a map to an encoded string that is acceptable on a URL."
  [param-map]
  [map? => string?]
  (str "?"
    (str/join "&"
      (map (fn [[k v]]
             (str
               (encode-uri-component (str (symbol k)))
               "=" (encode-uri-component v))) param-map))))

(defrecord HTML5History [listeners generator current-uid prior-route]
  RouteHistory
  (-push-route! [this route params]
    #?(:cljs
       (let [url (str "/"
                   (str/join "/" (map str route))
                   (query-string params))]
         (log/spy :debug ["Pushing route" route params])
         (reset! current-uid (swap! generator inc))
         (reset! prior-route {:route route :params params})
         (.pushState js/history #js {"uid" @current-uid} "" url))))
  (-replace-route! [this route params]
    #?(:cljs
       (let [url (str "/"
                   (str/join "/" (map str route))
                   (query-string params))]
         (log/spy :debug ["Replacing route" route params])
         (reset! prior-route {:route route :params params})
         (.replaceState js/history #js {"uid" @current-uid} "" url))))
  (-undo! [this _ {::history/keys [direction]}]
    (log/debug "Attempting to UNDO a routing request from the browser")
    (when-let [{:keys [route params]} (log/spy :debug @prior-route)]
      (reset! prior-route nil)
      (if (= :forward direction)
        (history/-replace-route! this route params)
        (history/-push-route! this route params))))
  (-back! [_]
    #?(:cljs
       (.back js/history)))
  (-add-route-listener! [_ listener-key f] (swap! listeners assoc listener-key f))
  (-remove-route-listener! [_ listener-key] (swap! listeners dissoc listener-key))
  (-current-route [_]
    #?(:cljs
       (let [path   (.. js/document -location -pathname)
             params (or (some-> (.. js/document -location -search) (query-params)) {})
             route  (vec (drop 1 (str/split path #"/")))]
         {:route  route
          :params params}))))

(defn url->route
  "Convert the current URI into a route path and parameter map. Returns:

   ```
   {:route [\"path\" \"segment\"]
    :params {:param value}}
   ```
  "
  []
  #?(:cljs
     (let [path   (.. js/document -location -pathname)
           route  (vec (drop 1 (str/split path #"/")))
           params (or (some-> (.. js/document -location -search) (query-params)) {})]
       {:route  route
        :params params})))

(defn html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API."
  []
  #?(:cljs
     (try
       (let [history            (HTML5History. (atom {}) (atom 1) (atom 1) (atom nil))
             pop-state-listener (fn [evt]
                                  (let [current-uid (-> history (:current-uid) deref)
                                        event-uid   (log/spy :info (gobj/getValueByKeys evt "state" "uid"))
                                        forward?    (< event-uid current-uid)
                                        {:keys [route params]} (url->route)
                                        listeners   (some-> history :listeners deref vals)]
                                    (log/debug "Got pop state event." evt)
                                    (doseq [f listeners]
                                      (f route (assoc params ::history/direction (if forward? :forward :back))))
                                    (reset! (:prior-route history) (history/-current-route history))))]
         (.addEventListener js/window "popstate" pop-state-listener)
         history)
       (catch :default e
         (log/error e "Unable to create HTML5 history.")))))

(defn restore-route!
  "Attempt to restore the route given in the URL. If that fails, simply route to the default given (a class and map).

   WARNING: This should not be called until the HTML5 history is installed in your app."
  [app default-page default-params]
  (let [this (history/active-history app)
        {:keys [route params]} (url->route)]
    (if (and this (seq route))
      (dr/change-route! app route params)
      (routing/route-to! app default-page default-params))))
