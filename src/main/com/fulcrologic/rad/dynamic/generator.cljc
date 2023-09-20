(ns com.fulcrologic.rad.dynamic.generator
  (:require
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.rad.report :as report]
    [com.fulcrologic.rad.form :as form]
    [com.fulcrologic.rad.options-util :refer [?!]]
    [com.fulcrologic.rad.dynamic.generator-options :as geno]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [taoensso.timbre :as log]))

(defn add-routes! [app component-map Router]
  (doseq [[sym c] component-map]
    (if (dr/route-segment c)
      (do
        (log/debug "Adding route for " sym)
        (dr/add-route-target!! app {:router Router
                                    :target c}))
      (log/debug "Skipping route for" sym "because it has no route segment."))))

(defn generate-reports!
  "Takes a map `reports` of `{sym (fn [env] report-options)}` and returns a map from those same symbols to their generated
   report components. These new components will be auto-added to the Fulcro registry under those syms (converted to
   keywords) and any of the reports that define a route segment will be auto-added to the given dynamic `Router`.

   `env` requires you add ::attr/all-attributes (from which it will auto-add `::attr/key->attribute`), but it is an
   open map that your option generator functions will receive, and can contain anything needed by those functions.
   You can leverage `env` or multi-methods to overcome circular references within code needed by options."
  [app {::attr/keys [all-attributes] :as env} reports Router]
  (let [k->a          (attr/attribute-map all-attributes)
        env           (assoc env ::attr/key->attribute k->a)
        component-map (reduce-kv
                        (fn [acc sym optf]
                          (assoc acc sym (report/report (keyword sym) (?! optf env))))
                        {}
                        reports)]
    (add-routes! app component-map Router)
    component-map))

(defn generate-forms!
  "Takes a map `forms` of `{sym (fn [env] form-options)}` and returns a map from those same symbols to their generated
   form components. These new components will be auto-added to the Fulcro registry under those syms (converted to
   keywords) and any of the forms that define a route segment will be auto-added to the given dynamic `Router`.

   `env` requires you add ::attr/all-attributes (from which it will auto-add `::attr/key->attribute`), but it is an
   open map that your option generator functions will receive, and can contain anything needed by those functions.
   You can leverage `env` or multi-methods to overcome circular references within code needed by options.
"
  [app {::attr/keys [all-attributes] :as env} forms Router]
  (let [k->a          (attr/attribute-map all-attributes)
        env           (assoc env ::attr/key->attribute k->a)
        component-map (reduce-kv
                        (fn [acc sym optf]
                          (assoc acc sym (form/form (keyword sym) (?! optf env))))
                        {}
                        forms)]
    (add-routes! app component-map Router)
    component-map))

(defn generate-from-model!
  "Scans the entire RAD model looking for identity attributes that specify geno/reports or geno/forms, and generates
   the corresponding component for each entry using the symbol keys of that map as Fulcro registry keys for the new components.

   `env` requires you add ::attr/all-attributes (from which it will auto-add `::attr/key->attribute`), but it is an
   open map that your option generator functions will receive, and can contain anything needed by those functions.
   You can leverage `env` or multi-methods to overcome circular references within code needed by options.

   The new routes will be added to the specified router.

   Returns a map from sym to the new component."
  [app {::attr/keys [all-attributes] :as env} Router]
  (let [k->a (attr/attribute-map all-attributes)
        env  (assoc env ::attr/key->attribute k->a)
        genc (fn [gen m]
               (reduce-kv
                 (fn [acc sym optf]
                   (assoc acc sym (gen (?! optf env))))
                 {}
                 m))
        {:keys [reports forms]} (reduce
                                  (fn [acc a]
                                    (if (ao/identity? a)
                                      (-> acc
                                        (update :forms merge (?! (geno/forms a) env))
                                        (update :reports merge (?! (geno/reports a) env)))
                                      acc))
                                  {:forms   {}
                                   :reports {}}
                                  all-attributes)]
    (merge
      (generate-reports! app env reports Router)
      (generate-forms! app env forms Router))))
