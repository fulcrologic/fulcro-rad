(ns com.fulcrologic.rad.type-support.date-time
  "A common set of date/time functions for CLJC.  Libraries like `tick` are promising, and CLJC time is useful
  (and used by this ns), but cljc-time does not have an interface that is the same between the two languages,
  and tick is alpha (and often annoying).
  "
  #?(:cljs (:require-macros [com.fulcrologic.rad.type-support.date-time]))
  (:require
    ;; FIXME: Straighten out our story on locale support. Right now defaulting to en-US, which is not right.
    #?@(:cljs
        [["js-joda"]
         ["js-joda-timezone"]
         ["@js-joda/locale_en-us" :as js-joda-locale]])
    [clojure.spec.alpha :as s]
    [com.fulcrologic.rad.locale :as locale]
    [com.fulcrologic.guardrails.core :refer [>defn >def => ?]]
    [cljc.java-time.instant :as instant]
    [cljc.java-time.day-of-week :as java-time.day-of-week]
    [cljc.java-time.local-date-time :as ldt]
    [cljc.java-time.local-date :as ld]
    [cljc.java-time.zoned-date-time :as zdt]
    [cljc.java-time.format.date-time-formatter :as dtf]
    [cljc.java-time.period :as period]
    [cljc.java-time.duration :as duration]
    [cljc.java-time.zone-id :as zone-id]
    [taoensso.timbre :as log]
    [cljc.java-time.month :refer [january february march april may june july august september october
                                  november december]]
    #?@(:clj  []
        :cljs [[goog.object :as gobj]
               [java.time :refer [Duration LocalTime LocalDateTime LocalDate ZonedDateTime Period Instant]]
               [java.time.format :refer [DateTimeFormatter]]
               [com.fulcrologic.rad.type-support.ten-year-timezone]
               [goog.date.duration :as g-duration]])
    [cljc.java-time.local-time :as lt])
  #?(:clj (:import java.io.Writer
                   [java.util Date Locale]
                   [java.time Duration Instant LocalDate LocalDateTime LocalTime Period ZonedDateTime]
                   [java.time.format DateTimeFormatter])))

#?(:cljs
   (do
     (js/goog.exportSymbol "JSJodaLocale" js-joda-locale)))

(>def ::month (s/or :month #{january february march april may june july august september october
                             november december}))
(>def ::day (s/int-in 1 32))
(>def ::year (s/int-in 1970 3000))
(>def ::hour (s/int-in 0 24))
(>def ::minute (s/int-in 0 60))
(>def ::instant (s/with-gen #(instance? Instant %) #(s/gen #{(instant/now)})))
(>def ::local-time (s/with-gen #(instance? LocalTime %) #(s/gen #{(lt/of 11 23 0)})))
(>def ::zoned-date-time (s/with-gen #(instance? ZonedDateTime %) #(s/gen #{(zdt/of-instant (instant/now) (zone-id/of "America/Los_Angeles"))})))
(>def ::local-date-time (s/with-gen #(instance? LocalDateTime %) #(s/gen #{(ldt/of 2010 1 22 11 23 0)})))
(>def ::local-date (s/with-gen #(instance? LocalDate %) #(s/gen #{(ld/of 2019 3 21)})))
(>def ::zone-name (set (cljc.java-time.zone-id/get-available-zone-ids)))
(>def ::at inst?)
(>def ::period (s/with-gen #(instance? Period %) #(s/gen #{period/zero})))
(>def ::duration (s/with-gen #(instance? Duration %) #(s/gen #{duration/of-seconds 3})))
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
   (def ^:dynamic *current-zone-name*
     "The current time zone for all date time operations. Defaults to nil. Should be set using
     `set-timezone!` in cljs, and should be thread-bound to a processing request using `binding` in CLJ.
     The value of this var is a java.time.ZoneId, which can be obtained from a string with cljc.time.zone-id/of."
     nil)
   :cljs
   (defonce ^:dynamic *current-zone-name* nil))

#?(:clj
   (defmacro with-timezone
     "Set the (thread-local) \"current time zone\"to the given `zone-name` (a string zone id) for the duration of the rest of the
     `body`. Simply a short-hand for `(binding [*current-timezone* (zone-id/of zone-name)] ...)`."
     [zone-name & body]
     `(binding [*current-zone-name* ~zone-name
                *current-timezone*  (zone-id/of ~zone-name)]
        ~@body)))

(>defn set-timezone!
  "Set the root binding of timezone, a dynamic var. In CLJS there is a lot of async behavior, but the overall
  time zone is typically fixed for a user. In CLJ the timezone usually needs to be bound to the local processing
  thread for a request. Therefore, the typical CLJS code will call this function on start, and the typical
  CLJ code will do a `(binding [*current-timezone* (z/of user-zone)] ...)`. "
  [zone-name]
  [::zone-name => any?]
  #?(:cljs    (set! *current-zone-name* zone-name)
     :default (alter-var-root (var *current-zone-name*) (constantly zone-name)))
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

(>defn local-date->inst
  "Returns a UTC Clojure inst based on the date given as time in the named (ISO) zone (e.g. America/Los_Angeles).
  If no zone name (or nil) is given, then the `*current-timezone*` will be used."
  ([local-dt]
   [::local-date => inst?]
   (local-date->inst *current-zone-name* local-dt))
  ([zone-name local-dt]
   [(? ::zone-name) ::local-date => inst?]
   (let [z      (get-zone-id zone-name)
         zdt    (ldt/at-zone (ld/at-start-of-day local-dt) z)
         millis (instant/to-epoch-milli (zdt/to-instant zdt))]
     (new-date millis)))
  ([zone-name month day yyyy]
   [(? ::zone-name) int? int? int? => inst?]
   (let [local-dt (ld/of yyyy month day)]
     (local-date->inst zone-name local-dt))))

(>defn local-datetime->inst
  "Returns a UTC Clojure inst based on the date/time given as time in the named (ISO) zone (e.g. America/Los_Angeles).
  If no zone name (or nil) is given, then the `*current-timezone*` will be used."
  ([local-dt]
   [::local-date-time => inst?]
   (local-datetime->inst *current-zone-name* local-dt))
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

(>defn inst->local-date
  "Converts a UTC Instant into the correctly-offset (e.g. America/Los_Angeles) LocalDate."
  ([inst]
   [(? (s/or :inst inst?
         :instant ::instant)) => ::local-date]
   (inst->local-date *current-zone-name* inst))
  ([zone-name inst]
   [(? ::zone-name) (? (s/or :inst inst?
                         :instant ::instant)) => ::local-date]
   (let [z   (get-zone-id zone-name)
         i   (instant/of-epoch-milli (inst-ms (or inst (now))))
         ldt (ldt/of-instant i z)]
     (ldt/to-local-date ldt))))

(>defn inst->local-datetime
  "Converts a UTC Instant into the correctly-offset (e.g. America/Los_Angeles) LocalDateTime."
  ([inst]
   [(? (s/or :inst inst?
         :instant ::instant)) => ::local-date-time]
   (inst->local-datetime *current-zone-name* inst))
  ([zone-name inst]
   [(? ::zone-name) (? (s/or :inst inst?
                         :instant ::instant)) => ::local-date-time]
   (let [z   (get-zone-id zone-name)
         i   (instant/of-epoch-milli (inst-ms (or inst (now))))
         ldt (ldt/of-instant i z)]
     ldt)))

(>defn inst->zoned-date-time
  "Converts a UTC Instant into the correctly-offset (e.g. America/Los_Angeles) ZonedDateTime."
  ([inst]
   [(? (s/or :inst inst?
         :instant ::instant)) => ::zoned-date-time]
   (inst->zoned-date-time *current-zone-name* inst))
  ([zone-name inst]
   [(? ::zone-name) (? (s/or :inst inst?
                         :instant ::instant)) => ::zoned-date-time]
   (let [z (get-zone-id zone-name)
         i (instant/of-epoch-milli (inst-ms (or inst (now))))]
     (zdt/of-instant i z))))

(>defn html-datetime-string->inst
  ([date-time-string]
   [string? => inst?]
   (html-datetime-string->inst *current-zone-name* date-time-string))
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
   [(? inst?) => string?]
   (inst->html-datetime-string *current-zone-name* inst))
  ([zone-name inst]
   [(? ::zone-name) (? inst?) => string?]
   (try
     (let [z         (get-zone-id (or zone-name "UTC"))
           ldt       (ldt/of-instant (inst->instant (or inst (now))) z)
           formatter cljc.java-time.format.date-time-formatter/iso-local-date-time]
       (ldt/format ldt formatter))
     (catch #?(:cljs :default :clj Exception) e
       nil))))

(defn ^DateTimeFormatter formatter
  "Constructs a DateTimeFormatter out of either a
  * format string - \"YYYY/mm/DD\" \"YYY HH:MM\" etc.
  or
  * formatter name - :iso-instant :iso-local-date etc

  and a Locale, which is optional."
  ([fmt]
   (formatter
     fmt
     #?(:clj  (Locale/getDefault)
        :cljs (try
                (some->
                  (gobj/get js/JSJodaLocale "Locale")
                  (gobj/get "US"))
                (catch js/Error e)))))
  ([fmt locale]
   (let [^DateTimeFormatter fmt (cond (instance? DateTimeFormatter fmt) fmt
                                      (string? fmt) (if (nil? locale)
                                                      (throw
                                                        #?(:clj  (Exception. "Locale is nil")
                                                           :cljs (js/Error. (str "Locale is nil, try adding a require for [js-joda.locale_en-us]"))))
                                                      (.. DateTimeFormatter
                                                        (ofPattern fmt)
                                                        (withLocale locale))))]
     fmt)))

(let [get-format (memoize (fn [format locale]
                            (formatter format locale)))]
  (defn tformat [format inst]
    (try
      (let [ldt       (inst->local-datetime inst)
            formatter (get-format format (locale/current-locale))]
        (ldt/format ldt formatter))
      (catch #?(:clj Exception :cljs :default) e
        (log/error e)
        nil))))

(defn inst->human-readable-date
  "Converts a UTC Instant into the correctly-offset and human-readable (e.g. America/Los_Angeles) date string.

  Uses locale from `locale/current-locale`."
  [inst]
  (if (inst? inst)
    (tformat "E, MMM d, yyyy" inst)
    ""))

(>defn inst->html-date
  "Convert an inst to an HTML date input string. Assumes *current-timezone*. Always returns a string. Will return
  today's date if inst is nil or otherwise fails to convert."
  [inst]
  [(? inst?) => string?]
  (if inst
    (tformat "yyyy-MM-dd" inst)
    (tformat "yyyy-MM-dd" (now))))

(>defn html-date->inst
  "Convert an HTML date input string to an inst at the given local time, adjusted to the correct *current-timezone*. Returns
   `now` if the string isn't a proper ISO string."
  [html-date local-time]
  [(? string?) ::local-time => inst?]
  (let [date      (or (ld/parse html-date) (ld/now))
        date-time (ld/at-time date local-time)]
    (local-datetime->inst date-time)))

(>defn zoned-date-time->inst
  "Convert a zoned-date-time back to a low-level inst?"
  [ztm]
  [::zoned-date-time => inst?]
  (-> ztm
    (zdt/to-instant)
    (instant/to-epoch-milli)
    (new-date)))

(>defn beginning-of-day
  "Returns an inst? that is adjusted to midnight (local time of current time zone) on the day of the
   input instant (which defaults to `now`)."
  ([]
   [=> inst?]
   (beginning-of-day (now)))
  ([inst]
   [inst? => inst?]
   (-> inst
     (inst->local-date)
     (local-date->inst))))

(>defn end-of-day
  "Returns an inst? that is adjusted to midnight (local time of current time zone) on the next day of the
   input instant (which defaults to `now`). This creates an open interval for end."
  ([]
   [=> inst?]
   (end-of-day (now)))
  ([inst]
   [inst? => inst?]
   (-> inst
     (inst->local-date)
     (ld/plus-days 1)
     (local-date->inst))))

(>defn beginning-of-month
  "Returns an inst? that is adjusted to midnight (local time of current time zone) on the first day of the month of the
   input instant (which defaults to `now`)."
  ([]
   [=> inst?]
   (beginning-of-month (now)))
  ([inst]
   [inst? => inst?]
   (-> inst
     (inst->local-date)
     (ld/with-day-of-month 1)
     (local-date->inst))))

(>defn end-of-month
  "Returns an inst? that is adjusted to midnight (local time of current time zone) on the first day of the next month of the
   input instant (which defaults to `now`). This creates an open interval for end."
  ([]
   [=> inst?]
   (end-of-month (now)))
  ([inst]
   [inst? => inst?]
   (-> inst
     (inst->local-date)
     (ld/with-day-of-month 1)
     (ld/plus-months 1)
     (local-date->inst))))

(>defn beginning-of-year
  "Returns an inst? that is adjusted to midnight (local time of current time zone) on the first day of January in this
   year."
  ([]
   [=> inst?]
   (beginning-of-year (now)))
  ([inst]
   [inst? => inst?]
   (-> inst
     (inst->local-date)
     (ld/with-day-of-year 1)
     (local-date->inst))))

(>defn end-of-year
  "Returns an inst? that is adjusted to midnight (local time of current time zone) on the first day of January in the
   next year."
  ([]
   [=> inst?]
   (end-of-year (now)))
  ([inst]
   [inst? => inst?]
   (-> inst
     (inst->local-date)
     (ld/with-day-of-year 1)
     (ld/plus-years 1)
     (local-date->inst))))

(>defn max-inst
  "Returns the maximum inst from a list of insts. Returns nil if no insts are passed."
  [& insts]
  [(s/* inst?) => (? inst?)]
  (first (sort-by (comp - inst-ms) insts)))

(>defn min-inst
  "Returns the minimum inst from a list of insts. Returns nil if no insts are passed."
  [& insts]
  [(s/* inst?) => (? inst?)]
  (first (sort insts)))
