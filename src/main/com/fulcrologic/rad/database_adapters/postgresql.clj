(ns com.fulcrologic.rad.database-adapters.postgresql
  (:require
    [camel-snake-kebab.core :as csk]
    [com.fulcrologic.rad.attributes :as attr]
    [com.fulcrologic.guardrails.core :refer [>defn =>]]
    [clojure.string :as str]
    [taoensso.timbre :as log]))

(def type-map
  {:string   "TEXT"
   :password "TEXT"
   :int      "INTEGER"
   :long     "BIGINT"
   :money    "decimal(20,2)"
   :inst     "BIGINT"
   :keyword  "TEXT"
   :symbol   "TEXT"
   :uuid     "UUID"})

(defn attr->table-name [{::attr/keys [qualified-key]}]
  (some-> qualified-key
    namespace
    (str/split #"\.")
    last
    csk/->snake_case))

(defn attr->column-name [{::attr/keys [qualified-key]}]
  (some-> qualified-key
    name
    csk/->snake_case))

;(>defn new-entities->migration
;    [schema new-entities]
;    ;[::schema/schema (s/coll-of ::entity/entity) => (s/tuple string? string?)]
;    (let [creates (StringBuilder.)
;          updates (StringBuilder.)]
;      (doseq [{::entity/keys [qualified-key attributes] :as entity} new-entities
;              :let [attributes (map attr/key->attribute attributes)
;                    table-name (csk/->snake_case (name qualified-key))]]
;        (.append creates (str "CREATE TABLE " table-name "(\n"))
;        (.append creates (str/join ",\n"
;                           (keep
;                             (fn [{::attr/keys [qualified-key type unique?]}]
;                               (let [column-name (csk/->snake_case (name qualified-key))]
;                                 (cond
;                                   (and
;                                     (= type :generated)
;                                     (= unique? true))
;                                   (str "   " column-name " BIGSERIAL PRIMARY KEY")
;
;                                   (= unique? true)
;                                   (str "   " column-name " " (type-map type) " NOT NULL PRIMARY KEY")
;
;                                   (= :ref type) nil
;
;                                   :else (str "   " column-name " " (type-map type)))))
;                             attributes)))
;        (.append creates ");\n"))
;
;      (doseq [{::entity/keys [qualified-key attributes] :as entity} new-entities
;              :let [attributes (map attr/key->attribute attributes)
;                    table-name (csk/->snake_case (name qualified-key))]]
;        (.append updates
;          (str/join ""
;            (keep
;              (fn [{::attr/keys [qualified-key type target unique? cardinality]}]
;                (if (or (nil? cardinality) (= cardinality :one))
;                  (let [column-name      (csk/->snake_case (name qualified-key))
;                        target-attribute (when target
;                                           (attr/key->attribute target))
;                        target-column    (attr->column-name target-attribute)
;                        target-table     (attr->table-name target-attribute)
;                        target-type      (some-> target-attribute ::attr/type type-map)]
;                    (when (and target target-type target-table (= :ref type))
;                      (str "ALTER TABLE " table-name " ADD COLUMN " column-name " " target-type " REFERENCES " target-table "(" target-column ");\n")))))
;              attributes))))
;      [(.toString creates) (.toString updates)]))

