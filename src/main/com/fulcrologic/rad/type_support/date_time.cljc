(ns com.fulcrologic.rad.type-support.date-time
  "A common set of date/time functions for CLJC.  Libraries like `tick` are promising, and CLJC time is useful
  (and used by this ns), but cljc-time does not have an interface that is the same between the two languages,
  and tick is alpha (and often annoying).
  "
  #?(:cljs (:require-macros [com.fulcrologic.rad.type-support.date-time]))
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [cljc.java-time.instant :as instant]
    [cljc.java-time.day-of-week :as java-time.day-of-week]
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.local-date :as ld]
    [cljc.java-time.zoned-date-time :as zdt]
    cljc.java-time.format.date-time-formatter
    [cljc.java-time.zone-id :as zone-id]
    [cljc.java-time.month :refer [january february march april may june july august september october
                                  november december]]
    #?@(:clj  []
        :cljs [[java.time :refer [Duration ZoneId LocalTime LocalDateTime LocalDate DayOfWeek Month ZoneOffset Instant]]
               [com.fulcrologic.rad.type-support.ten-year-timezone]
               [goog.date.duration :as g-duration]]))
  #?(:clj (:import java.io.Writer
                   [java.util Date]
                   [java.time DayOfWeek Duration Instant LocalDate LocalDateTime LocalTime Month MonthDay
                              OffsetDateTime OffsetTime Period Year YearMonth ZonedDateTime ZoneId ZoneOffset]
                   [java.time.zone ZoneRules]
                   [java.time.temporal TemporalAdjusters ChronoField ChronoUnit]
                   [com.cognitect.transit TransitFactory WriteHandler ReadHandler])))

(>def ::month (s/or :month #{january february march april may june july august september october
                             november december}))
(>def ::day (s/int-in 1 32))
(>def ::year (s/int-in 1970 3000))
(>def ::hour (s/int-in 0 24))
(>def ::minute (s/int-in 0 60))
(>def ::instant #(instance? Instant %))
(>def ::local-time #(instance? LocalTime %))
(>def ::local-date-time #(instance? LocalDateTime %))
(>def ::local-date #(instance? LocalDate %))
(>def ::zone-name (set (cljc.java-time.zone-id/get-available-zone-ids)))
(>def ::at inst?)
(>def ::day-of-week #{java-time.day-of-week/sunday
                      java-time.day-of-week/monday
                      java-time.day-of-week/tuesday
                      java-time.day-of-week/wednesday
                      java-time.day-of-week/thursday
                      java-time.day-of-week/friday
                      java-time.day-of-week/saturday})

#?(:clj
   (def ^:dynamic *current-timezone*
     "The current time zone for all date time operations. Defaults to nil. Should be set using
     `set-timezone!` in cljs, and should be thread-bound to a processing request using `binding` in CLJ.
     The value of this var is a java.time.ZoneId, which can be obtained from a string with cljc.time.zone-id/of."
     nil)
   :cljs
   (defonce ^:dynamic *current-timezone* nil))

#?(:clj
   (defmacro with-timezone
     "Set the (thread-local) \"current time zone\"to the given `zone-name` (a string zone id) for the duration of the rest of the
     `body`. Simply a short-hand for `(binding [*current-timezone* (zone-id/of zone-name)] ...)`."
     [zone-name & body]
     `(binding [*current-timezone* (zone-id/of ~zone-name)]
        ~@body)))

(>defn set-timezone!
  "Set the root binding of timezone, a dynamic var. In CLJS there is a lot of async behavior, but the overall
  time zone is typically fixed for a user. In CLJ the timezone usually needs to be bound to the local processing
  thread for a request. Therefore, the typical CLJS code will call this function on start, and the typical
  CLJ code will do a `(binding [*current-timezone* (z/of user-zone)] ...)`. "
  [zone-name]
  [::zone-name => any?]
  #?(:cljs    (set! *current-timezone* (zone-id/of zone-name))
     :default (alter-var-root (var *current-timezone*) (constantly (zone-id/of zone-name)))))

(>defn new-date
  "Create a Date object from milliseconds (defaults to now)."
  ([]
   [=> inst?]
   #?(:clj  (Date.)
      :cljs (js/Date.)))
  ([millis]
   [int? => inst?]
   #?(:clj  (new Date millis)
      :cljs (js/Date. millis))))

(>defn now
  "Returns the current time as an inst."
  []
  [=> inst?]
  (new-date))

(defn now-ms
  "Returns the current time in ms."
  []
  (inst-ms (now)))

(defn inst->instant [i] (instant/of-epoch-milli (inst-ms i)))
(defn instant->inst [i] (new-date (instant/to-epoch-milli i)))

(def zone-region? #(= java.time.ZoneRegion (type %)))
(def date-time? #(= java.time.LocalDateTime (type %)))
(def date? #(= java.time.LocalDate (type %)))

(def mon-to-sunday [java-time.day-of-week/monday
                    java-time.day-of-week/tuesday
                    java-time.day-of-week/wednesday
                    java-time.day-of-week/thursday
                    java-time.day-of-week/friday
                    java-time.day-of-week/saturday
                    java-time.day-of-week/sunday])

(defn get-zone-id
  "Returns the ZoneID of zone-name, or *current-timezone* if zone-name is nil."
  [zone-name]
  (if (string? zone-name)
    (zone-id/of zone-name)
    *current-timezone*))

(>defn html-date-string->local-date
  "Convert a standard HTML5 date input string to a local date"
  [s]
  [string? => date?]
  (ld/parse s))

(>defn local-date->html-date-string
  "Convert a standard HTML5 date input string to a local date"
  [d]
  [date? => string?]
  (str d))

(>defn local-datetime->inst
  "Returns a UTC Clojure inst based on the date/time given as time in the named (ISO) zone (e.g. America/Los_Angeles).
  If no zone name (or nil) is given, then the `*current-timezone*` will be used."
  ([local-dt]
   [::local-date-time => inst?]
   (local-datetime->inst nil local-dt))
  ([zone-name local-dt]
   [(? ::zone-name) ::local-date-time => inst?]
   (let [z      (get-zone-id zone-name)
         zdt    (ldt/at-zone local-dt z)
         millis (instant/to-epoch-milli (zdt/to-instant zdt))]
     (new-date millis)))
  ([zone-name month day yyyy hh mm ss]
   [(? ::zone-name) int? int? int? int? int? int? => inst?]
   (let [local-dt (ldt/of yyyy month day hh mm ss)]
     (local-datetime->inst zone-name local-dt)))
  ([zone-name month day yyyy hh mm]
   [(? ::zone-name) int? int? int? int? int? => inst?]
   (local-datetime->inst zone-name month day yyyy hh mm 0)))

(>defn inst->local-datetime
  "Converts a UTC Instant into the correctly-offset (e.g. America/Los_Angeles) LocalDateTime."
  ([inst]
   [(s/or :inst inst?
      :instant ::instant) => ::local-date-time]
   (inst->local-datetime nil inst))
  ([zone-name inst]
   [(? ::zone-name) (s/or :inst inst?
                      :instant ::instant) => ::local-date-time]
   (let [z   (get-zone-id zone-name)
         i   (instant/of-epoch-milli (inst-ms inst))
         ldt (ldt/of-instant i z)]
     ldt)))

(>defn html-datetime-string->inst
  ([date-time-string]
   [string? => inst?]
   (html-datetime-string->inst nil date-time-string))
  ([zone-name date-time-string]
   [(? ::zone-name) string? => inst?]
   (try
     (let [z   (get-zone-id zone-name)
           dt  (ldt/parse date-time-string)
           zdt (ldt/at-zone dt z)
           i   (zdt/to-instant zdt)]
       (new-date (instant/to-epoch-milli i)))
     (catch #?(:cljs :default :clj Exception) e
       nil))))

(>defn inst->html-datetime-string
  ([inst]
   [inst? => string?]
   (inst->html-datetime-string nil inst))
  ([zone-name inst]
   [(? ::zone-name) inst? => string?]
   (try
     (let [z         (get-zone-id zone-name)
           ldt       (ldt/of-instant (inst->instant inst) z)
           formatter cljc.java-time.format.date-time-formatter/iso-local-date-time]
       (ldt/format ldt formatter))
     (catch #?(:cljs :default :clj Exception) e
       nil))))
