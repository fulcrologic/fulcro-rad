(ns com.fulcrologic.rad.routing.html5-history
  "Use Fulcro's new Routing System instead.
   An implementation of RAD's RouteHistory protocol, wrapping a browser's location and History API. This implementation
   will put an string-valued route parameters onto the query parameter section of the URI when a route is pushed or replaced,
   and will merge the current URL's query parameters with returned route params.

   Cannot be used with statecharts-based RAD."
  (:require
    #?(:cljs [goog.object :as gobj])
    [com.fulcrologic.guardrails.core :refer [>defn >defn- => ?]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]
    [com.fulcrologic.fulcro.routing.system :as rsys]
    [com.fulcrologic.fulcro.routing.dynamic-routing-browser-system :refer [current-url->route]]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [clojure.spec.alpha :as s]
    [taoensso.timbre :as log])
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

(>defn route->url
  "Construct URL from route and params"
  [route params hash-based?]
  [coll? (? map?) boolean? => string?]
  (let [q (query-string (or params {}))]
    (if hash-based?
      (str q "#/" (str/join "/" (map str route)))
      (str "/" (str/join "/" (map str route)) q))))

(defn url->route
  "Convert the current browser URL into a route path and parameter map. Returns:

   ```
   {:route [\"path\" \"segment\"]
    :params {:param value}}
   ```

   You can save this value and later use it with `apply-route!`.

   Parameter hash-based? specifies whether to expect hash based routing. If no
   parameter is provided the mode is autodetected from presence of hash segment in URL.
  "
  ([] (url->route #?(:clj  false
                     :cljs (some? (seq (.. js/document -location -hash)))) nil))
  ([hash-based?] (url->route hash-based? nil))
  ([hash-based? prefix]
   #?(:cljs
      (let [path      (if hash-based?
                        (str/replace (.. js/document -location -hash) #"^[#]" "")
                        (.. js/document -location -pathname))
            pcnt      (count prefix)
            prefixed? (> pcnt 0)
            path      (if (and prefixed? (str/starts-with? path prefix))
                        (subs path pcnt)
                        path)
            route     (vec (drop 1 (str/split path #"/")))
            params    (or (some-> (.. js/document -location -search) (query-params)) {})]
        {:route  route
         :params params}))))

(defn new-html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API.

   `hash-based?` - Use hash-based URIs instead of paths
   `all-events?` - Call the route listeners on all routing operations (not just pop state events).
   `default-route` - A map of `{:route r :params p}` to use when there is no prior route, but the user tries to navigate to the prior screen.
   IF YOU PROVIDE default-route, THEN YOU MUST ALSO PROVIDE `app` for it to work.
   `app` - The Fulco application that is being served.
   `prefix`      - Prepend prefix to all routes, in cases we are not running on root url (context-root)
   `route->url` - Specify a function that can convert a given RAD route into a URL. Defaults to the function of this name in this ns.
   `url->route` - Specify a function that can convert a URL into a RAD route. Defaults to the function of this name in this ns."
  [{:keys [hash-based? all-events? prefix default-route app
           route->url url->route] :or {all-events? false
                                       hash-based? false
                                       prefix      nil
                                       route->url  route->url
                                       url->route  url->route}}]
  (log/error "HTML5 History in RAD has been removed. Use Fulcro's new routing systems."))

(defn html5-history
  "Create a new instance of a RouteHistory object that is properly configured against the browser's HTML5 History API.

   `hash-based?` - Use hash-based URIs instead of paths
   `all-events?` - Call the route listeners on all routing operations (not just pop state events).

  You should prefer using the new-html5-history, since it supports more options"
  ([] (new-html5-history {}))
  ([hash-based?] (new-html5-history {:hash-based? hash-based?}))
  ([hash-based? all-events?] (new-html5-history {:hash-based? hash-based? :all-events? all-events?})))

(defn ^:deprecated apply-route!
  "Use Fulcro's routing sytem instead.

   Apply the given route and params to the URL and routing system. `saved-route` is in the format of
   the return value of `url->route`. Returns true if it is able to route there."
  [app {:keys [route params] :as saved-route}]
  (rsys/route-to! app saved-route))

(defn ^:deprecated restore-route!
  "Use Fulcro's routing system instead. This will ONLY work if you're using dynamic routing.

   Attempt to restore the route given in the URL. If that fails, simply route to the default given (a class and map)."
  [app default-page default-params]
  (let [url-route (current-url->route)]
    (if (:route url-route)
      (rsys/replace-route! app url-route)
      (rsys/replace-route! app {:route  (dr/absolute-path app default-page default-params)
                                :params default-params}))))

