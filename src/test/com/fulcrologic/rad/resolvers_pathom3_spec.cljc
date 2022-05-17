(ns com.fulcrologic.rad.resolvers-pathom3-spec
  (:require
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.authorization :as auth]
    [com.fulcrologic.rad.resolvers-pathom3 :as resolvers-pathom3]
    ;; Pathom3 available on test cp, only
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [fulcro-spec.core :refer [assertions specification behavior component =>]]))

(specification "pathom3 resolver generation"
  (let [resolve-mock-answer #?(:clj (java.util.Date.)
                               :cljs (js/Date.))
        three-attributes [(attr/new-attribute ::time :inst
                                              {::pco/resolve (fn [_env _input]
                                                               {::time resolve-mock-answer})})
                          (attr/new-attribute ::secret :inst
                                              {::auth/permissions (fn [_] #{})
                                               ::pco/resolve      (fn [_env _input]
                                                                    {::secret #inst "2013-12-13"})})
                          (attr/new-attribute ::ignore-this-attr :string {ao/required? true})]]

    (behavior "can do it"
              (assertions
                (count (resolvers-pathom3/generate-resolvers [])) => 0
                (count (resolvers-pathom3/generate-resolvers three-attributes)) => (dec (count three-attributes))))

    (behavior "does it correctly"
      (let [all-generated-resolvers (resolvers-pathom3/generate-resolvers three-attributes)
            base-env (pci/register all-generated-resolvers)
            env ((attr/wrap-env three-attributes) base-env)]
        (assertions
          (every? pco/resolver? all-generated-resolvers) => true
          (p.eql/process env [::time]) => {::time resolve-mock-answer}
          (p.eql/process env [::secret]) => {::secret ::auth/REDACTED})))))

#_ (clojure.test/run-tests)