(ns com.fulcrologic.rad.type-support.ten-year-timezone
  "Loads a 10-year range of time zone definitions"
  (:require
    ;; MUST do this one in order for JSJoda to exist
    [com.fulcrologic.rad.type-support.js-joda-base]
    ;; This one ASSUMES there is a GLOBAL JSJoda
    ["@js-joda/timezone/dist/js-joda-timezone-10-year-range.min.js"]))
