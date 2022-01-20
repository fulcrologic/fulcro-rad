(ns com.fulcrologic.rad.type-support.js-joda-base
  "Things like js-joda/timezone require that a GLOBAL JSJoda exist before they are required. This
   file simply makes that happen."
  (:require
    ["@js-joda/core" :as js-joda]))

(set! js/JSJoda js-joda)
