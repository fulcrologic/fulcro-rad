(ns com.fulcrologic.rad.container
  "A RAD container is a component for grouping together reports.
   They allow you pull up controls to the container level to coordinate reports so that one set of controls is shared among them.

   Reports may keep controls local to themselves by adding `:local?` to a control; otherwise, all of the controls
   from all nested reports will be pulled up to the container level and will be unified when their names match. The
   container itself will then be responsible for asking the children to refresh (though technically you can add a local
   control to any child to make such a control available for a particular child)."
  #?(:cljs
     (:require-macros com.fulcrologic.rad.container))
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.ui-state-machines :as uism :refer [defstatemachine]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.rad.routing.history :as history]
    [com.fulcrologic.rad.control :as control :refer [Control]]
    [com.fulcrologic.rad.options-util :as opts :refer [?! debounce]]
    #?@(:clj
        [[cljs.analyzer :as ana]])
    [com.fulcrologic.fulcro.data-fetch :as df]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]))

(defn- merge-children [env]
  (let [container-class (uism/actor-class env :actor/container)
        container-ident (uism/actor->ident env :actor/container)
        merge-children* (fn [s]
                          (reduce
                            (fn [state cls]
                              (let [k    (comp/class->registry-key cls)
                                    path (conj container-ident k)]
                                (merge/merge-component state cls (or (comp/get-initial-state cls) {}) :replace path)))
                            s
                            (comp/component-options container-class ::children)))]
    (uism/apply-action env merge-children*)))

(defn shared-controls
  "Gathers all of the non-local controls from all children into a common control map. Controls with a common name
   will end up with the last child's definition, or you can make an explicit override in the container itself."
  [container-class-or-instance]
  (let [{::keys [children]} (comp/component-options container-class-or-instance)
        without-local (fn *without-local [controls]
                        (reduce-kv (fn [c k v]
                                     (if (:local? v)
                                       c
                                       (assoc c k v))) {} controls))]
    (reduce
      (fn [controls child]
        (let [child-controls (comp/component-options child ::control/controls)]
          (merge controls (without-local child-controls))))
      {}
      children)))

(defn- start-children! [{::uism/keys [app event-data] :as env}]
  (let [container-class (uism/actor-class env :actor/container)
        children        (comp/component-options container-class ::children)]
    (doseq [c children]
      (report/start-report! app c (assoc event-data ::report/externally-controlled? true)))
    env))

(defn container-options
  "Returns the report options from the current report actor."
  [uism-env & k-or-ks]
  (apply comp/component-options (uism/actor-class uism-env :actor/container) k-or-ks))

(defn- initialize-parameters [{::uism/keys [app] :as env}]
  (let [{history-params :params} (history/current-route app)
        controls           (merge (shared-controls (uism/actor-class env :actor/container)) (container-options env :com.fulcrologic.rad.control/controls))
        initial-parameters (reduce-kv
                             (fn [result control-key {:keys [default-value]}]
                               (if default-value
                                 (assoc result control-key (?! default-value app))
                                 result))
                             {}
                             controls)]
    ;; TASK: Push parameters down to children, so everyone is in agreement. Would be really nice to normalize these
    (uism/update-aliased env :parameters merge initial-parameters history-params)))

(defstatemachine container-machine
  {::uism/actors
   #{:actor/container}

   ::uism/aliases
   {:parameters [:actor/container :ui/parameters]}

   ::uism/states
   {:initial
    {::uism/events
     {::uism/started
      {::uism/handler
       (fn [env]
         (-> env
           (initialize-parameters)
           (merge-children)
           (start-children!)))}

      :event/set-parameter
      {::uism/handler
       (fn [{::uism/keys [event-data app] :as env}]
         (let [container (uism/actor-class env :actor/container)
               children  (comp/component-options container ::children)]
           (rad-routing/update-route-params! app merge event-data)
           (as-> env $
             (uism/update-aliased $ :parameters merge event-data)
             (reduce
               (fn [env c] (uism/trigger env (comp/get-ident c {}) :event/set-parameter event-data))
               $
               children))))}

      :event/run
      {::uism/handler (fn [env]
                        (reduce
                          (fn [env c]
                            (uism/trigger env (comp/get-ident c {}) :event/run))
                          env
                          (comp/component-options (uism/actor-class env :actor/container) ::children)))}}}}})

(defn render-layout
  "Auto-render the content of a container. This is the automatic body of a container. If you supply no render body
   to a container, this is what it will hold. Configurable through component options via `::container/layout-style`.  You can also do custom rendering
   in the container, and call this to embed the generated UI."
  [container-instance]
  (let [{::app/keys [runtime-atom]} (comp/any->app container-instance)
        layout-style (or (some-> container-instance comp/component-options ::layout-style) :default)
        layout       (some-> runtime-atom deref :com.fulcrologic.rad/controls ::style->layout layout-style)]
    (if layout
      (layout container-instance)
      (do
        (log/error "No layout function found for form layout style" layout-style)
        nil))))

(defn start-container! [app container-class options]
  (log/info "Starting container!")
  (let [container-ident (comp/get-ident container-class {})]
    (uism/begin! app container-machine container-ident {:actor/container container-class} options)))

(defn container-will-enter [app route-params container-class]
  (let [container-ident (comp/get-ident container-class {})]
    (dr/route-deferred container-ident
      (fn []
        (start-container! app container-class {:route-params route-params})
        (comp/transact! app [(dr/target-ready {:target container-ident})])))))

#?(:clj
   (defmacro defsc-container
     "Define a container, which is a specialized component that holds and coordinates more than one report under
      a common set of controls.

      If you want this to be a route target, then you must add `:route-segment`.

      You should at least specify a ::children option.

      If you elide the body, one will be generated for you."
     [sym arglist & args]
     (let [this-sym (first arglist)
           options  (first args)
           options  (opts/macro-optimize-options &env options #{::field-formatters ::column-headings ::form-links} {})
           {::control/keys [controls]
            ::keys         [children route] :as options} options]
       (when-not (seq children)
         (throw (ana/error &env (str "defsc-container " sym " has no declared children."))))
       (when (and route (not (string? route)))
         (throw (ana/error &env (str "defsc-container " sym " ::route, when defined, must be a string."))))
       (let [query-expr (into [:ui/parameters
                               {:ui/controls `(comp/get-query Control)}
                               [df/marker-table '(quote _)]]
                          (map (fn [child-sym] `{(comp/class->registry-key ~child-sym) (comp/get-query ~child-sym)}) children))
             query      (list 'fn '[] query-expr)
             nspc       (if (enc/compiling-cljs?) (-> &env :ns :name str) (name (ns-name *ns*)))
             fqkw       (keyword (str nspc) (name sym))
             options    (cond-> (assoc options
                                  :query query
                                  :initial-state (list 'fn '[_]
                                                   {:ui/parameters {}
                                                    :ui/controls   `(mapv #(select-keys % #{::control/id}) (control/control-map->controls ~controls))})
                                  :ident (list 'fn [] [::id fqkw]))
                          (string? route) (assoc
                                            :route-segment [route]
                                            :will-enter `(fn [app# route-params#] (container-will-enter app# route-params# ~sym))))
             body       (if (seq (rest args))
                          (rest args)
                          [`(render-layout ~this-sym)])]
         `(comp/defsc ~sym ~arglist ~options ~@body)))))
