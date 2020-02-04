(ns com.fulcrologic.rad.type-support.date-time-spec
  (:require
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [cljc.java-time.instant]
    [cljc.java-time.local-date :as ld]
    [fulcro-spec.core :refer [assertions specification]]
    #?@(:clj  []
        :cljs [[java.time :refer [Duration ZoneId LocalTime LocalDateTime LocalDate DayOfWeek Month ZoneOffset Instant]]
               [goog.date.duration :as g-duration]])
    [cljc.java-time.instant :as instant]
    [com.fulcrologic.rad.type-support.date-time :as datetime])
  #?(:clj (:import java.io.Writer
                   [java.util Date]
                   [java.time DayOfWeek Duration Instant LocalDate LocalDateTime LocalTime Month MonthDay
                              OffsetDateTime OffsetTime Period Year YearMonth ZonedDateTime ZoneId ZoneOffset]
                   [java.time.zone ZoneRules]
                   [java.time.temporal TemporalAdjusters ChronoField ChronoUnit]
                   [com.cognitect.transit TransitFactory WriteHandler ReadHandler])))

(declare =>)

(specification "now"
  (assertions
    "Returns the an inst?"

    (inst? (dt/now)) => true))

(specification "local-datetime->inst"
  (let [expected (-> (cljc.java-time.instant/parse "2019-04-01T16:00:00Z")
                   cljc.java-time.instant/to-epoch-milli
                   dt/new-date)
        NY-local (cljc.java-time.local-date-time/of 2019 4 1 12 0 0)]
    (assertions
      "Can convert from integer inputs into UTC instant"
      (dt/local-datetime->inst "America/New_York" 4 1 2019 12 0 0) => expected
      "Can convert from a local date time to proper UTC instant"
      (dt/local-datetime->inst "America/New_York" NY-local) => expected)))

(specification "local-date string conversion"
  (assertions
    "Converts an ISO string into a LocalDate"
    (dt/html-date-string->local-date "2019-03-01") => (ld/of 2019 3 1)
    "Can convert LocalDate to an HTML input string"
    (dt/local-date->html-date-string (ld/of 2019 3 1)) => "2019-03-01"))

(specification "local-datetime string conversion"
  (let [la-1130 (dt/local-datetime->inst "America/Los_Angeles" 4 1 2019 11 30)]
    (assertions
      "Can convert an instant into a localized time string"
      (dt/inst->html-datetime-string "America/Los_Angeles" la-1130) => "2019-04-01T11:30:00"
      "Converts an ISO string into a LocalDate"
      (dt/html-datetime-string->inst "America/Los_Angeles" "2019-04-01T11:30") => la-1130)))

(specification "inst->local-datetime"
  (let [tm          (datetime/new-date (instant/to-epoch-milli (cljc.java-time.instant/parse "2019-03-05T12:00:00Z")))
        expected-LA (cljc.java-time.local-date-time/of 2019 3 5 4 0 0)
        expected-NY (cljc.java-time.local-date-time/of 2019 3 5 7 0 0)]
    (assertions
      "Converts UTC inst into properly time-zoned local date times"
      (dt/inst->local-datetime "America/Los_Angeles" tm) => expected-LA
      (dt/inst->local-datetime "America/New_York" tm) => expected-NY)))



