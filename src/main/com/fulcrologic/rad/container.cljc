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
    [taoensso.encore :as enc]
    [clojure.spec.alpha :as s]))

(defn id-child-pairs
  "Returns a sequence of [id cls] pairs for each child (i.e. the seq of the children setting)"
  [container]
  (seq (comp/component-options container ::children)))

(defn child-classes
  "Returns a de-duped set of classes of the children of the given instance/class (using it's query)"
  [container]
  (set (vals (comp/component-options container ::children))))

(defn- merge-children [env]
  (let [container-class (uism/actor-class env :actor/container)
        container-ident (uism/actor->ident env :actor/container)
        merge-children* (fn [s]
                          (reduce
                            (fn [state [id cls]]
                              (let [k    (comp/class->registry-key cls)
                                    path (conj container-ident k)]
                                (merge/merge-component state cls (or (comp/get-initial-state cls {::report/id id}) {}) :replace path)))
                            s
                            (id-child-pairs container-class)))]
    (uism/apply-action env merge-children*)))

(defn- start-children! [{::uism/keys [app event-data] :as env}]
  (let [container-class (uism/actor-class env :actor/container)
        id-children     (id-child-pairs container-class)]
    (doseq [[id c] id-children]
      (report/start-report! app c (assoc event-data
                                    ::report/id id
                                    ::report/externally-controlled? true)))
    env))

(defn container-options
  "Returns the report options from the current report actor."
  [uism-env & k-or-ks]
  (apply comp/component-options (uism/actor-class uism-env :actor/container) k-or-ks))

(defn- initialize-parameters [{::uism/keys [app event-data] :as env}]
  (let [{history-params :params} (history/current-route app)
        {:keys [route-params]} event-data
        controls (control/component-controls (uism/actor-class env :actor/container))]
    (reduce-kv
      (fn [new-env control-key {:keys [default-value]}]
        (let [v (cond
                  (contains? route-params control-key) (get route-params control-key)
                  (contains? history-params control-key) (get history-params control-key)
                  (not (nil? default-value)) (?! default-value app))]
          (if-not (nil? v)
            (uism/apply-action new-env assoc-in [::control/id control-key ::control/value] v)
            new-env)))
      env
      controls)))

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
           (merge-children)
           (initialize-parameters)
           (start-children!)))}

      :event/run
      {::uism/handler (fn [env]
                        (reduce
                          (fn [env [id c]]
                            (uism/trigger env (comp/get-ident c {::report/id id}) :event/run))
                          env
                          (id-child-pairs (uism/actor-class env :actor/container))))}}}}})

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
       (when-not (map? children)
         (throw (ana/error &env (str "defsc-container " sym " has no declared children."))))
       (when (and route (not (string? route)))
         (throw (ana/error &env (str "defsc-container " sym " ::route, when defined, must be a string."))))
       (let [query-expr (into [:ui/parameters
                               {:ui/controls `(comp/get-query Control)}
                               [df/marker-table '(quote _)]]
                          (map (fn [[id child-sym]] `{~id (comp/get-query ~child-sym)}) children))
             query      (list 'fn '[] query-expr)
             nspc       (if (enc/compiling-cljs?) (-> &env :ns :name str) (name (ns-name *ns*)))
             fqkw       (keyword (str nspc) (name sym))
             options    (cond-> (assoc options
                                  :query query
                                  :initial-state (list 'fn '[_]
                                                   `(into {:ui/parameters {}
                                                           :ui/controls   (mapv #(select-keys % #{::control/id}) (control/control-map->controls ~controls))}
                                                      (map (fn [[id# c#]] [id# (comp/get-initial-state c# {::report/id id#})]) ~children)))
                                  :ident (list 'fn [] [::id fqkw]))
                          (string? route) (assoc
                                            :route-segment [route]
                                            :will-enter `(fn [app# route-params#] (container-will-enter app# route-params# ~sym))))
             body       (if (seq (rest args))
                          (rest args)
                          [`(render-layout ~this-sym)])]
         `(comp/defsc ~sym ~arglist ~options ~@body)))))
