(ns com.fulcrologic.rad.application
  (:require
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mroot]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    #?@(:cljs
        [[com.fulcrologic.fulcro.networking.http-remote :as net]
         [com.fulcrologic.fulcro.networking.file-upload :as file-upload]])
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]
    [clojure.walk :as walk]))

#?(:cljs
   (defn secured-request-middleware [{:keys [csrf-token]}]
     (->
       (net/wrap-fulcro-request)
       (file-upload/wrap-file-upload)
       (cond->
         csrf-token (net/wrap-csrf-token csrf-token)))))

(def default-network-blacklist
  "A set of the keywords that should not appear on network requests."
  #{::uism/asm-id
    ::app/active-remotes
    :com.fulcrologic.rad.blob/blobs
    :com.fulcrologic.rad.picker-options/options-cache
    df/marker-table
    ::fs/config})

(defn elision-predicate
  "Returns an elision predicate that will return true if the keyword k is in the blacklist or has the namespace
  `ui`."
  [blacklist]
  (fn [k]
    (let [kw-namespace (fn [k] (and (keyword? k) (namespace k)))
          k            (if (vector? k) (first k) k)
          ns           (some-> k kw-namespace)]
      (or
        (contains? blacklist k)
        (and (string? ns) (= "ui" ns))))))

(defn elide-params
  "Given a params map, elides any k-v pairs where `(pred k)` is false."
  [params pred]
  (walk/postwalk (fn [x]
                   (if (and (vector? x) (= 2 (count x)) (pred (first x)))
                     nil
                     x))
    params))

(defn elide-ast-nodes
  "Like df/elide-ast-nodes but also applies elision-predicate logic to mutation params."
  [{:keys [key union-key children] :as ast} elision-predicate]
  (let [union-elision? (and union-key (elision-predicate union-key))]
    (when-not (or union-elision? (elision-predicate key))
      (when (and union-elision? (<= (count children) 2))
        (log/warn "Unions are not designed to be used with fewer than two children. Check your calls to Fulcro
        load functions where the :without set contains " (pr-str union-key)))
      (let [new-ast (-> ast
                      (update :children (fn [c] (vec (keep #(elide-ast-nodes % elision-predicate) c))))
                      (update :params elide-params elision-predicate))]
        (if (seq (:children new-ast))
          new-ast
          (dissoc new-ast :children))))))

(defn global-eql-transform
  "Returns an EQL transform that removes `(pred k)` keywords from network requests."
  [pred]
  (fn [ast]
    (let [mutation? (symbol? (:dispatch-key ast))]
      (cond-> (elide-ast-nodes ast pred)
        mutation? (update :children conj (eql/expr->ast :tempids))))))

(defn fulcro-rad-app
  "Create a new fulcro RAD application with reasonable defaults.

  `options` is the same as for `app/fulcro-app`. You should use caution when overridding the :optimized-render!
   or `:global-eql-transform` options."
  [options]
  (app/fulcro-app
    (merge
      #?(:clj {}
         :cljs
              (let [token (when-not (undefined? js/fulcro_network_csrf_token)
                            js/fulcro_network_csrf_token)]
                {:remotes {:remote (net/fulcro-http-remote {:url                "/api"
                                                            :request-middleware (secured-request-middleware {:csrf-token token})})}}))
      {:global-eql-transform (global-eql-transform (elision-predicate default-network-blacklist))
       :optimized-render!    mroot/render!}
      options)))

(defn install-ui-controls!
  "Install the given control set as the RAD UI controls used for rendering forms. This should be called before mounting
  your app. The `controls` is just a map from data type to a sub-map that contains a :default key, with optional
  alternate renderings for that data type that can be selected with `::form/field-style {attr-key style-key}`."
  [app controls]
  (let [{::app/keys [runtime-atom]} app]
    (swap! runtime-atom assoc :com.fulcrologic.rad/controls controls)))
