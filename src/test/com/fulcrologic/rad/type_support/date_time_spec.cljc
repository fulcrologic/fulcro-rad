(ns com.fulcrologic.rad.type-support.date-time-spec
  (:require
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [cljc.java-time.instant]
    [cljc.java-time.local-date :as ld]
    [cljc.java-time.local-time :as lt]
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.format.date-time-formatter :as dtf]
    [com.fulcrologic.rad.locale :as r.locale]
    [fulcro-spec.core :refer [assertions specification behavior when-mocking]]
    #?@(:clj  []
        :cljs [[java.time :refer [Duration ZoneId LocalTime LocalDateTime LocalDate DayOfWeek Month ZoneOffset Instant]]
               [goog.date.duration :as g-duration]])
    [cljc.java-time.instant :as instant]
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
    (dt/local-date->html-date-string (ld/of 2019 3 1)) => "2019-03-01"
    "Empty string and nil HTML input string return nil LocalDate"
    (dt/html-date-string->local-date "") => nil
    (dt/html-date-string->local-date nil) => nil))

(specification "local-datetime string conversion"
  (let [la-1130 (dt/local-datetime->inst "America/Los_Angeles" 4 1 2019 11 30)]
    (dt/set-timezone! "America/Los_Angeles")
    (assertions
      "Uses the globally set tz by default"
      (dt/inst->html-datetime-string la-1130) => "2019-04-01T11:30:00"
      (dt/html-datetime-string->inst "2019-04-01T11:30") => la-1130
      "Can convert an instant into a localized time string"
      (dt/inst->html-datetime-string "America/Los_Angeles" la-1130) => "2019-04-01T11:30:00"
      "Converts an ISO string into an instant"
      (dt/html-datetime-string->inst "America/Los_Angeles" "2019-04-01T11:30") => la-1130
      "Empty string and nil HTML input string return nil instant"
      (dt/html-datetime-string->inst "") => nil
      (dt/html-datetime-string->inst nil) => nil)))

(specification "inst->local-datetime"
  (let [tm          (dt/new-date (instant/to-epoch-milli (cljc.java-time.instant/parse "2019-03-05T12:00:00Z")))
        expected-LA (cljc.java-time.local-date-time/of 2019 3 5 4 0 0)
        expected-NY (cljc.java-time.local-date-time/of 2019 3 5 7 0 0)]
    (dt/set-timezone! "America/Los_Angeles")
    (let [pretend-now #inst "2020-01-01T12:00Z"]
      (when-mocking
        (dt/now) => pretend-now

        (assertions
          "Defaults to current datetime if value is nil and no default is provided"
          (dt/inst->local-datetime "America/Los_Angeles" nil) => (ldt/of 2020 1 1 4 0))))
    (assertions
      "Uses the globally-set time zone by default"
      (dt/inst->local-datetime tm) => expected-LA
      "Converts UTC inst into properly time-zoned local date times"
      (dt/inst->local-datetime "America/Los_Angeles" tm) => expected-LA
      (dt/inst->local-datetime "America/New_York" tm) => expected-NY
      "Uses default value if one is provided and inst param is nil"
      (dt/inst->local-datetime "America/Los_Angeles" nil tm) => expected-LA
      (dt/inst->local-datetime "America/Los_Angeles" nil nil) => nil)))

(specification "set-timezone!"
  (dt/set-timezone! "America/New_York")
  (assertions
    "allows the root binding to be modified to a new time zone"
    (str dt/*current-timezone*) => "America/New_York")
  (binding [dt/*current-timezone* "America/Los_Angeles"]
    (assertions
      "and can be overridden with binding"
      (str dt/*current-timezone*) => "America/Los_Angeles")))

(specification "inst->human-readable-date"
  (behavior "formats dates based on the currently-set time zone"
    (let [tm #inst "2020-03-04T06:00:00Z"]
      (dt/set-timezone! "UTC")
      (assertions
        "UTC"
        (dt/inst->human-readable-date tm) => "Wed, Mar 4, 2020")
      (dt/set-timezone! "America/New_York")
      (assertions
        "NY"
        (dt/inst->human-readable-date tm) => "Wed, Mar 4, 2020")
      (dt/set-timezone! "America/Los_Angeles")
      (assertions
        "LA"
        (dt/inst->human-readable-date tm) => "Tue, Mar 3, 2020"))))

(specification "inst->html-date"
  (behavior "Outputs the correct HTML date for the given instant based on the current time zone"
    (let [tm #inst "2020-03-04T06:00:00Z"]
      (dt/set-timezone! "UTC")
      (assertions
        "UTC"
        (dt/inst->html-date tm) => "2020-03-04")
      (dt/set-timezone! "America/New_York")
      (assertions
        "NY"
        (dt/inst->html-date tm) => "2020-03-04")
      (dt/set-timezone! "America/Los_Angeles")
      (assertions
        "LA"
        (dt/inst->html-date tm) => "2020-03-03")))
  (behavior "Uses default value if given inst is nil"
    (let [pretend-now #inst "2021-03-03T12:00Z"]
      (when-mocking
        (dt/now) => pretend-now

        (assertions
          "If no default value is provided, return current date"
          (dt/inst->html-date nil) => "2021-03-03"))
      (dt/set-timezone! "America/Los_Angeles")
      (assertions
        "Return default value"
        (dt/inst->html-date nil #inst "2020-03-04T06:00:00Z") => "2020-03-03"
        "Returns empty string on nil"
        (dt/inst->html-date nil nil) => ""))))

(specification "html-date->inst"
  (behavior "Outputs the correct instant for an HTML date, properly adjusted to the local time given in the *current-timezone*"
    (let [dt "2020-03-01"
          tm (lt/of 6 0)]
      (dt/set-timezone! "UTC")
      (assertions
        "UTC"
        (dt/html-date->inst dt tm) => #inst "2020-03-01T06:00:00Z")

      (dt/set-timezone! "America/New_York")
      (assertions
        "NY"
        (dt/html-date->inst dt tm) => #inst "2020-03-01T11:00:00Z")

      (dt/set-timezone! "America/Los_Angeles")
      (assertions
        "LA"
        (dt/html-date->inst dt tm) => #inst "2020-03-01T14:00:00Z")))
  "Empty string and nil HTML input string return nil instant"
  (dt/html-date->inst "" (lt/now)) => nil
  (dt/html-date->inst nil (lt/now)) => nil)

(specification "inst->zoned-date-time"
  (let [expected    (zdt/of (ldt/of (ld/of 2020 3 1) (lt/of 6 0)) (dt/get-zone-id "America/Los_Angeles"))
        pretend-now #inst "2020-04-05T12:00Z"
        zdt-now (zdt/of (ldt/of (ld/of 2020 4 5) (lt/of 5 0)) (dt/get-zone-id "America/Los_Angeles"))]
    (dt/set-timezone! "America/Los_Angeles")
    (when-mocking
      (dt/now) => pretend-now

      (assertions
        "Defaults to current date if value is nil and no default is provided"
        (dt/inst->zoned-date-time "America/Los_Angeles" nil) => zdt-now))
    (assertions
      "Converts an instant to the correct zoned date time"
      (= expected (dt/inst->zoned-date-time #inst "2020-03-01T14:00:00Z")) => true
      "Uses default value if one is provided and inst param is nil"
      (dt/inst->zoned-date-time "America/Los_Angeles" nil #inst "2020-03-01T14:00:00Z") => expected
      (dt/inst->zoned-date-time "America/Los_Angeles" nil nil) => nil)))

(specification "inst->local-date"
  (let [pretend-now #inst "2020-01-01T12:00Z"]
    (when-mocking
      (dt/now) => pretend-now

      (assertions
        "Defaults to current date if value is nil and no default is provided"
        (dt/inst->local-date "America/Los_Angeles" nil) => (ld/of 2020 1 1))))
  (let [expected (ld/of 2020 2 29)]
    (dt/set-timezone! "America/Los_Angeles")

    (assertions
      "Converts an instant to the correct (zoned) local date"
      (= expected (dt/inst->local-date #inst "2020-03-01T04:00:00Z")) => true
      "Uses default value if one is provided and inst param is nil"
      (dt/inst->local-date "America/Los_Angeles" nil #inst "2020-03-01T04:00:00Z") => expected
      (dt/inst->local-date "America/Los_Angeles" nil nil) => nil)))

(specification "beginning-of-month"
  (let [expected #inst "2020-02-01T08:00:00Z"]
    (dt/set-timezone! "America/Los_Angeles")
    (assertions
      "Converts an instant to the correct (zoned) instant at the beginning of the month."
      (dt/beginning-of-month #inst "2020-03-01T04:00:00Z") => expected)))

(specification "formatting Locale support"
  (dt/with-timezone "Asia/Tehran"
    (r.locale/with-locale "en-US"
      (assertions
        "Formats the date/time in the correct zone and locale"
        (dt/tformat "hha E MMM d, yyyy" #inst "2020-03-15T12:45Z") => "04PM Sun Mar 15, 2020"
        (dt/tformat "yyyy-MM-dd'T'HH:mm" #inst "2020-03-15T12:45Z") => "2020-03-15T16:15"
        (dt/tformat "hh:mmaX" #inst "2020-03-15T12:45Z") => "04:15PM+0330"
        (dt/tformat "hh:mmaXX" #inst "2020-03-15T12:45Z") => "04:15PM+0330"
        (dt/tformat "hh:mmaXXX" #inst "2020-03-15T12:45Z") => "04:15PM+03:30"
        (dt/tformat "hh:mmaXXXX" #inst "2020-03-15T12:45Z") => "04:15PM+0330"
        (dt/tformat "hh:mmaXXXXX" #inst "2020-03-15T12:45Z") => "04:15PM+03:30")))
  #_(dt/with-timezone "America/Bogota"
      (r.locale/with-locale "es-CO"
        (assertions
          "Formats the date/time in the correct zone and locale"
          (dt/tformat "hha E MMM d, yyyy" #inst "2020-03-15T12:45Z") => #?(:cljs "07a.\u00a0m. dom. mar. 15, 2020"
                                                                           :clj  "07a. m. dom. mar. 15, 2020")))))
