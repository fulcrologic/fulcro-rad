# Type Support

## Overview

RAD provides extended type support for dates/times and arbitrary precision math (BigDecimal). The date-time support uses
`cljc.java-time` for cross-platform API compatibility and requires timezone configuration. The decimal support provides
full-stack BigDecimal operations in both CLJ and CLJS using `big.js` for JavaScript runtime.

## Date and Time Support

From DevelopersGuide.adoc:1711-1726:

### The :instant Type

RAD's `instant` type represents time as milliseconds from epoch (standard for JVM, JS, transit, and storage systems).

From DevelopersGuide.adoc:1713-1716:
> "The standard way to represent time is as an offset from the epoch in milliseconds. This is the de-facto
> representation in the JVM, JS VM, transit, and many storage systems. As such, it is the standard for the `instant` type
> in RAD."

**Storage**: Java/JS Date objects (UTC offsets)
**UI**: Localized to user/context timezone

From DevelopersGuide.adoc:1723-1726:
> "At the time of this writing RAD supports only the storage of instants (Java/js Date objects), and requires that you
> select a time-zone for the context of your processing. The concept of `LocalDate` and `LocalTime` can easily be added,
> but for now the style of the UI control determines what the user interaction looks like."

### cljc.java-time Library

From DevelopersGuide.adoc:1718-1722:
> "There are standard implementations of localization for js and the JVM, but since we're using CLJC already it makes
> the most sense to us to just use `cljc.java-time`, which is a library that unifies the API of the standard JVM Time API.
> This makes it much simpler to write localized support for dates and times in CLJC files."

**Why Not tick?**:
> "To date we are avoiding the `tick` library because it is not yet as mature, and is overkill for RAD itself (though
> you can certainly use it in your applications)."

### Date-Only Fields

From DevelopersGuide.adoc:1727-1736:

When users input a "date" (no time), RAD stores it as an instant at a specific time (e.g., noon) in the user's timezone:

```clojure
(defattr date :invoice/date :instant
  {ao/identities  #{:invoice/id}
   fo/field-style :date-at-noon})
```

**Alternative**: Store dates as strings or use database-specific LocalDate types, then add Transit support for
full-stack typing.

## Setting the Timezone

From DevelopersGuide.adoc:1741-1776:

### Client-Side (CLJS)

From DevelopersGuide.adoc:1746-1759:
> "In order to use date/time support in RAD you *must* set the time zone so that RAD knows how to adjust local date and
> times into proper UTC offsets."

From DevelopersGuide.adoc:1756-1759:
> "The time zone on the client side can usually be set to some reasonable default on client startup (perhaps based on
> the browser's known locale) and further refined when a user logs in (via a preference that you allow them to set)."

```clojure
(ns com.example.client
  (:require
    [com.fulcrologic.rad.type-support.date-time :as datetime]))

(defn init []
  (log/info "Starting App")
  ;; Set default timezone until user logs in
  (datetime/set-timezone! "America/Los_Angeles")
  (rad-app/install-ui-controls! app sui/all-controls)
  (app/mount! app Root "app"))
```

From DevelopersGuide.adoc:1759-1761:
> "The string argument is one of the standard time zone names. The are available from
`(cljc.java-time.zone-id/get-available-zone-ids)`."

**Common Timezones**:

- `"America/Los_Angeles"`
- `"America/New_York"`
- `"Europe/London"`
- `"UTC"`
- `"Asia/Tokyo"`

From DevelopersGuide.adoc:1774-1776:
> "NOTE: The above action is all that is needed to get most of RAD working. The remainder of the date/time support is
> used internally, and can also be convenient for your own logic as your requirements grow."

### Server-Side (CLJ)

From DevelopersGuide.adoc:1749-1752:
> "It is important to note that the *server* (CLJ) side will typically only deal with already-adjusted UTC offsets.
> Thus, the code on the server mostly just read/saves the values without having to do anything else. A UTC offset is
> unambiguous, just not human friendly."

For calculations requiring specific timezones, use thread-local bindings:

From DevelopersGuide.adoc:1780-1802:

```clojure
(ns com.example.reports
  (:require
    [com.fulcrologic.rad.type-support.date-time :as datetime]
    [cljc.java-time.zone-id :as zone-id]))

;; Standard Clojure binding
(binding [datetime/*current-timezone* (zone-id/of "America/New_York")]
  ; ... timezone-specific logic ...
  )

;; Or use the macro
(datetime/with-timezone "America/New_York"
  ; ... timezone-specific logic ...
  )
```

**Use Cases**:

- Report date range calculations from user's perspective
- Timezone-aware aggregations
- Converting stored UTC to specific timezone for display

## Arbitrary Precision Math

From DevelopersGuide.adoc:1804-1865:

### The Problem

From DevelopersGuide.adoc:1806-1810:
> "EDN and Transit already support the concept of representing and transmitting arbitrary precision numbers. CLJ uses
> the built-in `BigDecimal` and `BigInteger` JVM support for runtime implementation and seamless math operation.
> Unfortunately, CLJS accepts the *notation* for these, but uses only JS numbers as the actual runtime representation.
> This means that logic written in CLJC cannot be trusted to do math."

### The Solution

From DevelopersGuide.adoc:1811-1817:
> "Therefore RAD has full-stack support for BigDecimal (BigInteger may be added, as needed). Not just in type, but in
*operation*. The `com.fulcrologic.rad.type-support.decimal` namespace includes constructors that work the same in CLJ
> and CLJS (you would avoid using suffixes like `M`, since the CLJS code would map that to Number), and many of the common
> mathematical operations you'd need to implement your calculations in CLJS."

### Using BigDecimal

From DevelopersGuide.adoc:1819-1831:

```clojure
(ns example
  (:require
    [com.fulcrologic.rad.type-support.decimal :as math]))

;; Works the same in CLJ and CLJS
(-> (math/numeric 41)
  (math/div 3)    ; Division defaults to 20 digits precision
  (math/+ 35))

;; All standard operations
(math/+ (math/numeric 10) 5)
(math/- (math/numeric 100) 25)
(math/* (math/numeric 7) 8)
(math/div (math/numeric 22) 7)
```

From DevelopersGuide.adoc:1835-1837:
> "Of course you can use clojure exclusions and refer to get rid of the `math` prefix, but since it is common to need
> normal math for other UI operations we do not recommend it."

### Runtime Representation

From DevelopersGuide.adoc:1837-1841:
> "Fields that are declared to be arbitrary precision numerics will automatically live in your Fulcro database as this
`math/numeric` type (which is CLJ is BigDecimal, and in CLJS is a transit-tagged BigDecimal (a wrapped string)).
>
> The JS implementation is currently provided by `big.js` (which you must add to your package.json)."

**Add to package.json**:

```json
{
  "dependencies": {
    "big.js": "^6.0.0"
  }
}
```

### Performance: Primitive Math Mode

From DevelopersGuide.adoc:1842-1860:

For UI calculations where precision is less critical, use primitive ops for speed:

```clojure
;; Precise but slower (251ms for 10k operations)
(time (reduce math/+ 0 (range 0 10000)))
;; => 49995000M

;; Fast but imprecise (2ms for 10k operations)
(time (math/with-primitive-ops
        (reduce math/+ 0 (range 0 10000))))
;; => 49995000
```

From DevelopersGuide.adoc:1857-1860:
> "NOTE: `with-primitive-ops` coerces the value down to a `js/Number` (or JVM `double`), and then calls Clojure's
> pre-defined `+`, etc. This primarily exists for cases where you're doing something in a UI that must render quickly, but
> that uses data in this numeric format. For example a dynamically-adjusting report where you know the standard math to be
> accurate enough for transient purposes."

**Important Warning** (from DevelopersGuide.adoc:1861-1864):
> "WARNING: `with-primitive-ops` returns the value of the last statement in the body. If that is a numeric value then it
> will be a *primitive* numeric value (since you're using primitives). You must coerce it back using `math/numeric` if you
> need the arbitrary precision data type for storage."

```clojure
;; If result needs to be stored
(math/numeric
  (math/with-primitive-ops
    (reduce math/+ 0 calculated-values)))
```

## Common Patterns

### Pattern 1: Invoice Line Item Subtotal

```clojure
(ns com.example.model.line-item
  (:require
    [com.fulcrologic.rad.attributes :refer [defattr]]
    [com.fulcrologic.rad.attributes-options :as ao]
    [com.fulcrologic.rad.type-support.decimal :as math]))

(defattr quantity :line-item/quantity :decimal
  {ao/identities #{:line-item/id}})

(defattr price :line-item/price :decimal
  {ao/identities #{:line-item/id}})

(defattr subtotal :line-item/subtotal :decimal
  {ao/identities   #{:line-item/id}
   ao/computed-value (fn [{::fo/keys [props]} attr]
                       (let [{:line-item/keys [quantity price]} props]
                         (math/* quantity price)))})
```

### Pattern 2: User Timezone Selection

```clojure
(ns com.example.ui.login
  (:require
    [com.fulcrologic.rad.type-support.date-time :as datetime]))

(defmutation login-complete
  [{:keys [user]}]
  (action [{:keys [state]}]
    (swap! state assoc :session/current-user user)
    ;; Set timezone from user preference
    (datetime/set-timezone! (:user/timezone user))))
```

### Pattern 3: Date Range Report

```clojure
(ns com.example.reports.sales
  (:require
    [com.fulcrologic.rad.type-support.date-time :as datetime]))

(defresolver sales-in-range
  [{:keys [query-params] :as env} _]
  {::pc/output [{:report/sales [:sale/id]}]}
  (datetime/with-timezone (:user-timezone query-params "UTC")
    (let [start (datetime/html-date->inst (:from-date query-params))
          end   (datetime/html-date->inst (:to-date query-params))]
      {:report/sales (get-sales-between start end)})))
```

### Pattern 4: Currency Formatting

```clojure
(ns com.example.ui.formatters
  (:require
    [com.fulcrologic.rad.type-support.decimal :as math]))

(defn format-currency [amount]
  (str "$" (math/round amount 2)))

;; In report column formatter
{ro/column-formatters
 {:product/price (fn [report-instance row-props]
                   (format-currency (:product/price row-props)))}}
```

### Pattern 5: Percentage Calculations

```clojure
(defn calculate-tax
  [subtotal tax-rate]
  (-> subtotal
    (math/* tax-rate)
    (math/round 2)))

(defn calculate-total
  [subtotal tax discount]
  (-> subtotal
    (math/+ tax)
    (math/- discount)))
```

## Important Notes

### 1. Timezone Must Be Set

From DevelopersGuide.adoc:1746:
> "In order to use date/time support in RAD you *must* set the time zone..."

Without calling `datetime/set-timezone!`, date/time fields won't work correctly.

### 2. Server Uses UTC

From DevelopersGuide.adoc:1749-1752:

Server-side code typically works with UTC offsets. The client handles timezone conversion for display.

### 3. big.js Dependency Required

From DevelopersGuide.adoc:1839-1841:

For CLJS BigDecimal support, you must add `big.js` to `package.json`.

### 4. Avoid M Suffix in CLJC

From DevelopersGuide.adoc:1817:

Don't use `42M` notation in CLJC files. Use `(math/numeric 42)` instead for cross-platform compatibility.

### 5. LocalDate Not Yet Supported

From DevelopersGuide.adoc:1723-1724:

RAD currently only supports `:instant` type. LocalDate/LocalTime can be added but aren't built-in yet.

### 6. Precision Defaults

From DevelopersGuide.adoc:1829:

Division (`math/div`) defaults to 20 digits of precision. TODO: Add `math/with-precision` macro for customization.

### 7. Auto-Coercion

From DevelopersGuide.adoc:1840-1841:

Most `math/*` functions auto-coerce regular numbers to BigDecimal, so you can mix types:

```clojure
(math/+ (math/numeric 10) 5)  ; 5 is coerced
```

### 8. Joda Timezone Requirement

From DevelopersGuide.adoc:1743-1745:
> "NOTE: At the time of this writing the date-time namespace requires the 10-year time zone range from Joda Timezone.
> This will most likely be removed from RAD and changed to a requirement for your application, since you can then select
> the time zone file that best meets your application's size and functionality requirements."

Check current RAD documentation for timezone data requirements.

## Date-Time Namespace Functions

From DevelopersGuide.adoc:1801-1803:
> "See the doc strings on the functions in `com.fulcrologic.rad.type-support.date-time` namespace for more details on
> what support currently exists. This namespace will grow as needs arise, but many of the things you might need are easily
> doable using `cljc.java-time` (already included) and tick (an easy add-on dependency) as long as you center your logic
> around the `*current-timezone` when appropriate."

**Common Functions**:

- `set-timezone!` - Set global timezone (CLJS)
- `with-timezone` - Macro for thread-local timezone (CLJ)
- `html-date->inst` - Convert HTML5 date string to instant
- `inst->html-date` - Convert instant to HTML5 date string
- `now` - Get current instant
- `*current-timezone*` - Dynamic var for current timezone (ZoneID instance)

## Decimal Namespace Functions

**Constructors**:

- `(math/numeric value)` - Create BigDecimal from number or string

**Operations**:

- `(math/+ a b ...)` - Addition
- `(math/- a b ...)` - Subtraction
- `(math/* a b ...)` - Multiplication
- `(math/div a b)` - Division (20 digit precision default)
- `(math/round value digits)` - Round to N decimal places

**Comparison**:

- `(math/zero)` - BigDecimal zero
- Standard Clojure comparisons work: `=`, `<`, `>`, `<=`, `>=`

**Utilities**:

- `(math/with-primitive-ops body)` - Execute with JS Number math (fast, imprecise)

## Related Topics

- **Client Setup** (12-client-setup.md): Where to call `datetime/set-timezone!`
- **Dynamic Forms** (07-dynamic-forms.md): Using BigDecimal in computed and derived fields
- **Attributes and Data Model** (01-attributes-data-model.md): Defining `:instant` and `:decimal` attributes
- **Forms Basics** (04-forms-basics.md): Date/time and decimal input fields

## Source References

- DevelopersGuide.adoc:1701-1865 (Extended Data Type Support section)
- DevelopersGuide.adoc:1711-1803 (Dates and Time subsection)
- DevelopersGuide.adoc:1741-1802 (Setting the Time Zone)
- DevelopersGuide.adoc:1804-1865 (Arbitrary Precision Math and Storage)
- cljc.java-time: https://github.com/henryw374/cljc.java-time
- big.js: https://github.com/MikeMcl/big.js
