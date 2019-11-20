(ns com.fulcrologic.rad
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>def]]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.components :as comp]))

;; RAD IDs are always strings because the must be URL compatible. You must convert them to the correct field type.
;; TODO: Possible to auto-convert based on the ID field itself.
(>def ::id string?)
(>def ::tempid (s/or :fulcro-tempid tempid/tempid? :uuid uuid?))
(>def ::target-route (s/coll-of string? :kind vector?))
(>def ::BodyItem comp/component-class?)
(>def ::source-attribute qualified-keyword?)
(>def ::load! ifn?)
(>def ::start-edit! ifn?)
(>def ::start-create! ifn?)
