(ns com.fulcrologic.rad.routing.html5-history
  "An implementation of RAD's RouteHistory protocol, wrapping a browser's location and History API. This implementation
   will put an string-valued route parameters onto the query parameter section of the URI when a route is pushed or replaced,
   and will merge the current URL's query parameters with returned route params."
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.guardrails.core :refer [>defn >defn- => ?]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.rad.routing :as routing]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]
    [com.fulcrologic.rad.routing.history :as history :refer [RouteHistory]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log]
    [clojure.core :as core])
  #?(:clj (:import (java.net URLDecoder URLEncoder)
                   (java.nio.charset StandardCharsets))))

(>defn decode-uri-component
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
  "Decode a query string into a map of key/value pairs. The keys and values are decoded using decode-uri-component.
   The query string should not include the leading '?' character. optpnal fn-kv is a function that can be used to transform the key and value."
  ([qstr]
   [string? => map?]
   (query-params qstr (fn [k v] [(keyword (decode-uri-component k)) (decode-uri-component v)])))
  ([qstr fn-kv]
  [string?, fn? => map?]
   (try
     (reduce
      (fn [result assignment]
        (let [[k v] (str/split assignment #"=")]
          (cond
            (and k v (= k "_rp_")) (merge result (transit-str->clj (base64-decode (decode-uri-component v))))
            (and k v) (apply assoc result (fn-kv k v))
            :else result)))
      {}
      (str/split qstr #"&"))
     (catch #?(:clj Exception :cljs :default) e
       (log/error e "Cannot decode query param string")
       {}))))

(defn query-string*
  "Convert a map to an encoded string that is acceptable on a URL.
  The param-map allows any data type acceptable to transit. 
  The additional key-values must all be strings, key must be a string and value must be a string. Value should uri-encoded"
  [param-map string-key-values]
  (let [pm? (seq param-map)
        skv? (seq string-key-values)]
    (if (or pm? skv?)
      (str "?"
           (str/join "&" (map (fn [[k v]] (str k "=" v)) string-key-values))
           (when (and pm? skv?) "&")
           (when pm?
             (str "_rp_="
                  (encode-uri-component (base64-encode (transit-clj->str param-map))))))
      "")))

(>defn query-string
  "Convert a map to an encoded string that is acceptable on a URL.
  The param-map allows any data type acceptable to transit. The additional key-values must all be strings
  (and will be coerced to string if not). "
  ([param-map]
   [map? => string?]
   (query-string param-map nil))
  ([param-map string-key-values]
   [map? (? string?) => string?] ; !!! this is not correct, how to specify optional map of strings 
   (query-string* param-map
                  (into {} (map (fn [[k v]]
                                  [(encode-uri-component (name k)) (encode-uri-component (str v))]) string-key-values)))))

(>defn route->url
  "Construct URL from route and query-string"
  ([route qstr]
   [coll? string? => string?]
   (route->url route qstr false))
  ([route qstr hash-based?]
   [coll? string? boolean? => string?]
   (let [path (str/join "/" (map str route))]
     (if hash-based?
       (str qstr "#/" path)
       (str "/" path qstr)))))

(defn browser-url-data
  "Return tuple [path qstr] from browser. Prefix is removed from path.
  !!!! Where is auotdetecting hash-based? ???? 
   Parameter hash-based? specifies whether to expect hash based routing. If no
   parameter is provided the mode is autodetected from presence of hash segment in URL.
   "
  [hash-based? prefix]
  #?(:cljs
     (let [path      (if hash-based?
                       (str/replace (.. js/document -location -hash) #"^[#]" "")
                       (.. js/document -location -pathname)) 
           pcnt      (count prefix)
           prefixed? (> pcnt 0)
           path      (if (and prefixed? (str/starts-with? path prefix))
                       (subs path pcnt)
                       path)
           search      (.. js/document -location -search)] 
       ;; handle "bu%C4%8Da" & drop leading &
       [(decode-uri-component path) (str/replace search #"^[?]" "")])))            

(defn url->route
  "Convert URL (path and query-string) into a route path and parameter map.
   See browser-url-data for more info about path and qstr.
   Returns:

   ```
   {:route [\"path\" \"segment\"]
    :params {:param value}}
   ```

   You can save this value and later use it with `apply-route!`. "
  ([path qstr]
   (url->route path qstr query-params))
  ([path qstr decode-query-string]
   {:route (vec (drop 1 (str/split path #"/")))
    :params (or (some-> qstr (decode-query-string)) {})}))

(comment

  (route->url ["a" "b"] {} false)
  (route->url ["a" "b"] {} true)
  (route->url ["a" "b"] {:a 1} false)
  (route->url ["a" "b"] {:a 1} true)

  (route->url ["a" "b"] {} true)
  (route->url ["a" "b"] {:a 1} true)
  (route->url ["a" "b"] {:a 1} true)

  (query-string {} {})
  (query-string {} {"a" 1})
  (query-string {:c 3} {"a" 1 :b "test"})

  (query-string* {:a 1 :b 2} {"c" "3"})
  (query-string* {:a 1 :b 2} {"c" "3"} (fn [k v] [(clojure.string/upper-case  ( str k)) (str v)]))
  (query-string* nil {:c "3"})
  (query-string nil {:c "3"})
  (query-string {} )
  (query-string {} {})
  (query-string {} {:c "3"})
  (url->route "/bu%C4%8Da/11/events-and-speed"
              "from=2024-01-12T21:39:47.85&client-id=11&until=2024-01-21T00:00:00")

  (query-params "from=2024-01-12T21:39:47.85&client-id=11&until=2024-01-21T00:00:00")
  (route->url ["buča" "11" "events-and-speed"]
              {:from "2024-01-12T21:39:47.85"
               :client-id "11"
               :until "2024-01-21T00:00:00"}
              false)
  (url->route "/buča/11/events-and-speed"
              "a=1&_rp_=WyJeICIsIn46ZnJvbSIsIjIwMjQtMDEtMTJUMjE6Mzk6NDcuODUiLCJ%2BOmNsaWVudC1pZCIsIjExIiwifjp1bnRpbCIsIjIwMjQtMDEtMjFUMDA6MDA6MDAiXQ%3D%3D")
  (query-params "a=1&_rp_=WyJeICIsIn46ZnJvbSIsIjIwMjQtMDEtMTJUMjE6Mzk6NDcuODUiLCJ%2BOmNsaWVudC1pZCIsIjExIiwifjp1bnRpbCIsIjIwMjQtMDEtMjFUMDA6MDA6MDAiXQ%3D%3D")
  (browser-url-data false "")
  
  )


(defn- notify-listeners! [history route params direction]
  (let [listeners (some-> history :listeners deref vals)]
    (doseq [f listeners]
      (f route (assoc params ::history/direction direction)))))

(defrecord HTML5History [hash-based? listeners generator current-uid prior-route all-events? prefix recent-history default-route
                         fulcro-app encode-query-params decode-query-string]
  RouteHistory
  (-push-route! [this route params]
    #?(:cljs
       (let [url (str prefix (route->url route (encode-query-params params) hash-based?))]
         (log/spy :debug ["Pushing route" route params "->" url])
         (when all-events?
           (notify-listeners! this route params :push))
         (reset! current-uid (swap! generator inc))
         (reset! prior-route {:route route :params params})
         (swap! recent-history #(cons {:route route :params params} %))
         (.pushState js/history #js {"uid" @current-uid} "" url))))
  (-replace-route! [this route params]
    #?(:cljs
       (let [url (str prefix (route->url route (encode-query-params params) hash-based?))]
         (when all-events?
           (notify-listeners! this route params :replace))
         (log/spy :debug ["Replacing route" route params])
         (reset! prior-route {:route route :params params})
         (swap! recent-history (fn [h] (->> h (rest) (cons {:route route :params params}))))
         (.replaceState js/history #js {"uid" @current-uid} "" url))))
  (-undo! [this _ {::history/keys [direction]}]
    (log/debug "Attempting to UNDO a routing request from the browser")
    (when-let [{:keys [route params]} @prior-route]
      (reset! prior-route nil)
      (if (= :forward direction)
        (history/-replace-route! this route params)
        (history/-push-route! this route params))))
  (-back! [this]
    #?(:cljs
       (do
         (cond
           (> (count @recent-history) 1) (do
                                           (log/debug "Back to prior route" (some-> prior-route deref))
                                           (.back js/history))
           (:route default-route) (let [{:keys [route params]
                                         :or   {params {}}} default-route]
                                    (log/debug "No prior route. Using default route")
                                    (when (= :routing (dr/change-route! fulcro-app route params))
                                      (history/-push-route! this route params)))
           :else (log/error "No prior route. Ignoring BACK request.")))))
  (-add-route-listener! [_ listener-key f] (swap! listeners assoc listener-key f))
  (-remove-route-listener! [_ listener-key] (swap! listeners dissoc listener-key))
  (-current-route [_] (let [[path qstr] (browser-url-data hash-based? prefix)]
                        (log/spy (url->route path qstr decode-query-string)))))

(defn new-html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API.

   `hash-based?` - Use hash-based URIs instead of paths
   `all-events?` - Call the route listeners on all routing operations (not just pop state events).
   `default-route` - A map of `{:route r :params p}` to use when there is no prior route, but the user tries to navigate to the prior screen.
   IF YOU PROVIDE default-route, THEN YOU MUST ALSO PROVIDE `app` for it to work.
   `app` - The Fulco application that is being served.
   `prefix` - Prepend prefix to all routes, in cases we are not running on root url (context-root)
   `encode-query-params` - Specify a function that can convert a params into query-streng. Defaults to the function query-params in this ns.
   `decode-query-string` - Specify a function that can convert a query-string params mao in Defaults to the function query-string in this ns."
  [{:keys [hash-based? all-events? prefix default-route app
           encode-query-params decode-query-string] :or {all-events? false
                                       hash-based? false
                                       prefix      nil
                                       encode-query-params query-string
                                       decode-query-string query-params}}]
  (assert (or (not prefix)
            (and (str/starts-with? prefix "/")
              (not (str/ends-with? prefix "/"))))
    "Prefix must start with a slash, and not end with one.")
  #?(:cljs
     (try
       (let [history            (HTML5History. hash-based? (atom {}) (atom 1) (atom 1) (atom nil) all-events? prefix (atom []) default-route app
                                               encode-query-params decode-query-string)
             pop-state-listener (fn [evt]
                                  (let [current-uid (-> history (:current-uid) deref)
                                        event-uid   (gobj/getValueByKeys evt "state" "uid")
                                        forward?    (< event-uid current-uid)
                                        {:keys [route params]} (history/-current-route history)
                                        listeners   (some-> history :listeners deref vals)]
                                    (log/debug "Got pop state event." evt)
                                    (doseq [f listeners]
                                      (f route (assoc params ::history/direction (if forward? :forward :back))))
                                    (swap! (:recent-history history) rest)
                                    (reset! (:prior-route history) (history/-current-route history))))]
         (.addEventListener js/window "popstate" pop-state-listener)
         history)
       (catch :default e
         (log/error e "Unable to create HTML5 history.")))))

(defn html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API.

   `hash-based?` - Use hash-based URIs instead of paths
   `all-events?` - Call the route listeners on all routing operations (not just pop state events).

  You should prefer using the new-html5-history, since it supports more options"
  ([] (new-html5-history {}))
  ([hash-based?] (new-html5-history {:hash-based? hash-based?}))
  ([hash-based? all-events?] (new-html5-history {:hash-based? hash-based? :all-events? all-events?})))

(defn apply-route!
  "Apply the given route and params to the URL and routing system. `saved-route` is in the format of
   the return value of `url->route`. Returns true if it is able to route there."
  [app {:keys [route params] :as saved-route}]
  (if-let [target (dr/resolve-target app route)]
    (let [app-root        (app/root-class app)
          raw-path        (binding [rc/*query-state* (app/current-state app)]
                            (dr/resolve-path app-root target params))
          embedded-params (reduce
                            (fn [m [raw resolved]]
                              (if (keyword? raw)
                                (assoc m raw resolved)
                                m))
                            {}
                            (mapv vector raw-path route))
          params          (merge embedded-params params)]
      (routing/route-to! app target params)
      true)
    (do
      (log/error "Saved route did not resolve to a UI target" saved-route)
      false)))

(defn restore-route!
  "Attempt to restore the route given in the URL. If that fails, simply route to the default given (a class and map).

   WARNING: This should not be called until the HTML5 history is installed in your app, and any module that might be
   needed is loaded."
  [app default-page default-params]
  (let [this      (history/active-history app)
        url-route (history/-current-route this)]
    (if (and this (seq (:route url-route)))
      (when-not (apply-route! app url-route)
        (routing/route-to! app default-page default-params))
      (routing/route-to! app default-page default-params))))
