(ns com.fulcrologic.rad.container
  "A RAD container is a component for grouping together reports.
   They allow you pull up controls to the container level to coordinate reports so that one set of controls is shared among them.

   Reports may keep controls local to themselves by adding `:local?` to a control; otherwise, all of the controls
   from all nested reports will be pulled up to the container level and will be unified when their names match. The
   container itself will then be responsible for asking the children to refresh (though technically you can add a local
   control to any child to make such a control available for a particular child)."
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.routing :as rad-routing]
    [com.fulcrologic.rad.control :as control]
    [com.fulcrologic.rad.options-util :as opts]
    #?@(:clj
        [[cljs.analyzer :as ana]])
    [com.fulcrologic.fulcro.data-fetch :as df]
    [taoensso.timbre :as log]
    [taoensso.encore :as enc]))

(defn reload!
  "Trigger a reload on the container, which will cause all nested children to refresh."
  [container-instance])

(defn set-parameter!
  "Set the given parameter on the container, which will feed it forward into each report that uses it. The container
   should be started before you do this or the forward-feed will likely be overwritten when the children are started."
  [report-instance parameter-name new-value]
  ;(comp/transact! report-instance [(merge-params {parameter-name new-value})])
  (rad-routing/update-route-params! report-instance assoc parameter-name new-value))

(defn render-layout [container-instance]
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
  ;; 1. Send parameters down to all children
  )

(defn container-will-enter [app route-params container-class]
  (let [container-ident (comp/get-ident container-class {})]
    (dr/route-deferred container-ident
      (fn []
        (start-container! app container-class {:route-params route-params})
        (comp/transact! app [(dr/target-ready {:target container-ident})])))))

(defn shared-controls
  "Gathers all of the non-local controls from all children into a common control map."
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
            ::keys         [children] :as options} options]
       (when-not (seq children)
         (throw (ana/error &env (str "defsc-container " sym " has no declared children."))))
       (let [query   (into [:ui/parameters [df/marker-table '(quote _)]]
                       (map (fn [child-sym] {(comp/class->registry-key ~child-sym) `(comp/get-query ~child-sym)}) children))
             nspc    (if (enc/compiling-cljs?) (-> &env :ns :name str) (name (ns-name *ns*)))
             fqkw    (keyword (str nspc) (name sym))
             options (assoc options
                       :will-enter `(fn [app# route-params#] (container-will-enter app# route-params# ~sym))
                       :query query
                       ::control/controls `(merge (shared-controls ~sym) ~controls)
                       :initial-state {:ui/parameters {}}
                       :ident (list 'fn [] [::id fqkw]))
             body    (if (seq (rest args))
                       (rest args)
                       [`(render-layout ~this-sym)])]
         `(comp/defsc ~sym ~arglist ~options ~@body)))))
