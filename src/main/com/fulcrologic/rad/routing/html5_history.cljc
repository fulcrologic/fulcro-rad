(ns com.fulcrologic.rad.routing.html5-history
  "An implementation of RAD's RouteHistory protocol, wrapping a browser's location and History API. This implementation
   will put an string-valued route parameters onto the query parameter section of the URI when a route is pushed or replaced,
   and will merge the current URL's query parameters with returned route params."
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [com.fulcrologic.rad.routing :as routing]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]
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
  [(? string?) => (? string?)]
  (when (string? v)
    #?(:clj  (URLDecoder/decode ^String v (.toString StandardCharsets/UTF_8))
       :cljs (js/decodeURIComponent v))))

(>defn encode-uri-component
  "Encode a key/value pair of CLJ(s) data such that it can be safely placed in browser query params. If `v` is
   a plain string, then it will not be transit-encoded."
  [v]
  [string? => string?]
  #?(:clj  (URLEncoder/encode ^String v (.toString StandardCharsets/UTF_8))
     :cljs (js/encodeURIComponent v)))

(>defn query-params
  [raw-search-string]
  [string? => map?]
  (try
    (let [param-string (str/replace raw-search-string #"^[?]" "")]
      (reduce
        (fn [result assignment]
          (let [[k v] (str/split assignment #"=")]
            (cond
              (and k v (= k "_rp_")) (merge result (transit-str->clj (base64-decode (decode-uri-component v))))
              (and k v) (assoc result (keyword (decode-uri-component k)) (decode-uri-component v))
              :else result)))
        {}
        (str/split param-string #"&")))
    (catch #?(:clj Exception :cljs :default) e
      (log/error e "Cannot decode query param string")
      {})))

(>defn query-string
  "Convert a map to an encoded string that is acceptable on a URL.
  The param-map allows any data type acceptable to transit. The additional key-values must all be strings
  (and will be coerced to string if not). "
  [param-map & {:as string-key-values}]
  [map? (s/* string?) => string?]
  (str "?_rp_="
    (encode-uri-component (base64-encode (transit-clj->str param-map)))
    "&"
    (str/join "&"
      (map (fn [[k v]]
             (str (encode-uri-component (name k)) "=" (encode-uri-component (str v)))) string-key-values))))

(defn url->route
  "Convert the current browser URL into a route path and parameter map. Returns:

   ```
   {:route [\"path\" \"segment\"]
    :params {:param value}}
   ```

   You can save this value and later use it with `apply-route!`.
  "
  []
  #?(:cljs
     (let [path   (.. js/document -location -pathname)
           route  (vec (drop 1 (str/split path #"/")))
           params (or (some-> (.. js/document -location -search) (query-params)) {})]
       {:route  route
        :params params})))

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
       (let []
         (log/spy :debug ["Back to prior route" (some-> prior-route deref)])
         (.back js/history))))
  (-add-route-listener! [_ listener-key f] (swap! listeners assoc listener-key f))
  (-remove-route-listener! [_ listener-key] (swap! listeners dissoc listener-key))
  (-current-route [_]
    #?(:cljs
       (let [path   (.. js/document -location -pathname)
             params (or (some-> (.. js/document -location -search) (query-params)) {})
             route  (vec (drop 1 (str/split path #"/")))]
         {:route  route
          :params params}))))

(defn html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API."
  []
  #?(:cljs
     (try
       (let [history            (HTML5History. (atom {}) (atom 1) (atom 1) (atom nil))
             pop-state-listener (fn [evt]
                                  (let [current-uid (-> history (:current-uid) deref)
                                        event-uid   (gobj/getValueByKeys evt "state" "uid")
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

(defn apply-route!
  "Apply the given route and params to the URL and routing system. `saved-route` is in the format of
   the return value of `url->route`. Returns true if it is able to route there."
  [app {:keys [route params] :as saved-route}]
  (if-let [target (dr/resolve-target app route)]
    (do
      (routing/route-to! app target params)
      true)
    (do
      (log/error "Saved route did not resolve to a UI target" saved-route)
      false)))

(defn restore-route!
  "Attempt to restore the route given in the URL. If that fails, simply route to the default given (a class and map).

   WARNING: This should not be called until the HTML5 history is installed in your app."
  [app default-page default-params]
  (let [this      (history/active-history app)
        url-route (url->route)]
    (if (and this (seq (:route url-route)))
      (when-not (apply-route! app url-route)
        (routing/route-to! app default-page default-params))
      (routing/route-to! app default-page default-params))))
