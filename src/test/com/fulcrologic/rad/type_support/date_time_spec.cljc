(ns com.fulcrologic.rad.type-support.date-time-spec
  (:require
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [cljc.java-time.instant]
    [cljc.java-time.local-date :as ld]
    [cljc.java-time.local-time :as lt]
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.format.date-time-formatter :as dtf]
    [fulcro-spec.core :refer [assertions specification behavior]]
    #?@(:clj  []
        :cljs [[java.time :refer [Duration ZoneId LocalTime LocalDateTime LocalDate DayOfWeek Month ZoneOffset Instant]]
               [goog.date.duration :as g-duration]])
    [cljc.java-time.instant :as instant]
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [cljc.java-time.zoned-date-time :as zdt])
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
  (let [expected          (-> (cljc.java-time.instant/parse "2019-04-01T16:00:00Z")
                            cljc.java-time.instant/to-epoch-milli
                            dt/new-date)
        a-local-date-time (cljc.java-time.local-date-time/of 2019 4 1 12 0 0)]
    (dt/set-timezone! "America/Los_Angeles")
    (assertions
      "Uses the user's pre-set zone name for conversion"
      (dt/local-datetime->inst a-local-date-time) => (-> (cljc.java-time.instant/parse "2019-04-01T19:00:00Z")
                                                       cljc.java-time.instant/to-epoch-milli
                                                       dt/new-date)
      "Can convert from integer inputs into UTC instant"
      (dt/local-datetime->inst "America/New_York" 4 1 2019 12 0 0) => expected
      "Can convert from a local date time to proper UTC instant"
      (dt/local-datetime->inst "America/New_York" a-local-date-time) => expected)))

(specification "local-date string conversion"
  (assertions
    "Converts an ISO string into a LocalDate"
    (dt/html-date-string->local-date "2019-03-01") => (ld/of 2019 3 1)
    "Can convert LocalDate to an HTML input string"
    (dt/local-date->html-date-string (ld/of 2019 3 1)) => "2019-03-01"))

(specification "local-datetime string conversion"
  (let [la-1130 (dt/local-datetime->inst "America/Los_Angeles" 4 1 2019 11 30)]
    (dt/set-timezone! "America/Los_Angeles")
    (assertions
      "Uses the globally set tz by default"
      (dt/inst->html-datetime-string la-1130) => "2019-04-01T11:30:00"
      (dt/html-datetime-string->inst "2019-04-01T11:30") => la-1130
      "Can convert an instant into a localized time string"
      (dt/inst->html-datetime-string "America/Los_Angeles" la-1130) => "2019-04-01T11:30:00"
      "Converts an ISO string into a LocalDate"
      (dt/html-datetime-string->inst "America/Los_Angeles" "2019-04-01T11:30") => la-1130)))

(specification "inst->local-datetime"
  (let [tm          (datetime/new-date (instant/to-epoch-milli (cljc.java-time.instant/parse "2019-03-05T12:00:00Z")))
        expected-LA (cljc.java-time.local-date-time/of 2019 3 5 4 0 0)
        expected-NY (cljc.java-time.local-date-time/of 2019 3 5 7 0 0)]
    (dt/set-timezone! "America/Los_Angeles")
    (assertions
      "Uses the globally-set time zone by default"
      (dt/inst->local-datetime tm) => expected-LA
      "Converts UTC inst into properly time-zoned local date times"
      (dt/inst->local-datetime "America/Los_Angeles" tm) => expected-LA
      (dt/inst->local-datetime "America/New_York" tm) => expected-NY)))

(specification "set-timezone!"
  (datetime/set-timezone! "America/New_York")
  (assertions
    "allows the root binding to be modified to a new time zone"
    (str datetime/*current-timezone*) => "America/New_York")
  (binding [datetime/*current-timezone* "America/Los_Angeles"]
    (assertions
      "and can be overridden with binding"
      (str datetime/*current-timezone*) => "America/Los_Angeles")))

(specification "inst->human-readable-date"
  (behavior "formats dates based on the currently-set time zone"
    (let [tm #inst "2020-03-04T06:00:00Z"]
      (datetime/set-timezone! "UTC")
      (assertions
        "UTC"
        (datetime/inst->human-readable-date tm) => "Wed, Mar 4, 2020")
      (datetime/set-timezone! "America/New_York")
      (assertions
        "NY"
        (datetime/inst->human-readable-date tm) => "Wed, Mar 4, 2020")
      (datetime/set-timezone! "America/Los_Angeles")
      (assertions
        "LA"
        (datetime/inst->human-readable-date tm) => "Tue, Mar 3, 2020"))))

(specification "inst->html-date"
  (behavior "Outputs the correct HTML date for the given instant based on the current time zone"
    (let [tm #inst "2020-03-04T06:00:00Z"]
      (datetime/set-timezone! "UTC")
      (assertions
        "UTC"
        (datetime/inst->html-date tm) => "2020-03-04")
      (datetime/set-timezone! "America/New_York")
      (assertions
        "NY"
        (datetime/inst->html-date tm) => "2020-03-04")
      (datetime/set-timezone! "America/Los_Angeles")
      (assertions
        "LA"
        (datetime/inst->html-date tm) => "2020-03-03"))))

(specification "html-date->inst"
  (behavior "Outputs the correct instant for an HTML date, properly adjusted to the local time given in the *current-timezone*"
    (let [dt "2020-03-01"
          tm (lt/of 6 0)]
      (datetime/set-timezone! "UTC")
      (assertions
        "UTC"
        (datetime/html-date->inst dt tm) => #inst "2020-03-01T06:00:00Z")

      (datetime/set-timezone! "America/New_York")
      (assertions
        "NY"
        (datetime/html-date->inst dt tm) => #inst "2020-03-01T11:00:00Z")

      (datetime/set-timezone! "America/Los_Angeles")
      (assertions
        "LA"
        (datetime/html-date->inst dt tm) => #inst "2020-03-01T14:00:00Z"))))

(specification "inst->zoned-date-time"
  (let [expected (zdt/of (ldt/of (ld/of 2020 3 1) (lt/of 6 0)) (datetime/get-zone-id "America/Los_Angeles"))]
    (datetime/set-timezone! "America/Los_Angeles")
    (assertions
      (= expected (datetime/inst->zoned-date-time #inst "2020-03-01T14:00:00Z")) => true)))

(specification "inst->local-date"
  (let [expected (ld/of 2020 2 29)]
    (datetime/set-timezone! "America/Los_Angeles")
    (assertions
      "Converts an instant to the correct (zoned) local date"
      (= expected (datetime/inst->local-date #inst "2020-03-01T04:00:00Z")) => true)))

(specification "beginning-of-month"
  (let [expected #inst "2020-02-01T08:00:00Z"]
    (datetime/set-timezone! "America/Los_Angeles")
    (assertions
      "Converts an instant to the correct (zoned) instant at the beginning of the month."
      (datetime/beginning-of-month #inst "2020-03-01T04:00:00Z") => expected)))

