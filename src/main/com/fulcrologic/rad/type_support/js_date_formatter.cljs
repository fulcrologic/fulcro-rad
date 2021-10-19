(ns com.fulcrologic.rad.type-support.js-date-formatter
  "An implementation of date formatting that uses ISO format specifiers, but uses browser built-in Intl support
   to get the locale-dependent values"
  (:require
    [clojure.string :as str]
    [taoensso.timbre :as log]))

(def tokenize
  "[format-str]

   Turns a string that has repeating characters into groups of those repeating letters. This function is memoized, so
   it is best to ONLY use it on date/time format patterns, of which there will likely be few."
  (memoize
    (fn [format-str]
      (let [{:keys [tokens letters]} (let [letters (seq format-str)]
                                       (reduce
                                         (fn [{:keys [letters prior-letter] :as acc} letter]
                                           (if (= prior-letter letter)
                                             (update acc :letters conj letter)
                                             (-> acc
                                               (update :tokens conj (str/join letters))
                                               (assoc :prior-letter letter)
                                               (assoc :letters [letter]))))
                                         {:tokens       []
                                          :letters      [(first letters)]
                                          :prior-letter (first letters)}
                                         (rest letters)))]
        (conj tokens (str/join letters))))))

(defn- std-formatter [{:keys [zero-pad?] :as options}]
  (let [zero-pad (fn [s] (if (= 1 (count s))
                           (str "0" s)
                           s))]
    (fn [locale-name zone-name]
      (let [formatter (js/Intl.DateTimeFormat. locale-name (clj->js (merge {:timeZone zone-name}
                                                                      (dissoc options :zero-pad?))))]
        (fn [inst]
          (cond-> (.format formatter inst)
            zero-pad? zero-pad
            ))))))

(def format-map
  {"a"     (fn [locale-name zone-name]
             (let [formatter (js/Intl.DateTimeFormat.
                               locale-name
                               #js {:timeZone zone-name
                                    :hour12   true
                                    :hour     "numeric"})]
               (fn [inst]
                 (or
                   (some-> (re-matches #"^\d+(.*)$" (.format formatter inst)) (second) (str/trim))
                   ""))))
   "M"     (std-formatter {:month "numeric"})
   "MM"    (std-formatter {:month "2-digit"})
   "MMM"   (std-formatter {:month "short"})
   "MMMM"  (std-formatter {:month "long"})
   "MMMMM" (std-formatter {:month "narrow"})
   "m"     (std-formatter {:minute "numeric"})
   "mm"    (std-formatter {:minute "numeric" :zero-pad? true})
   "d"     (std-formatter {:day "numeric"})
   "dd"    (std-formatter {:day "numeric" :zero-pad? true})
   "h"     (fn [locale-name zone-name]
             (let [formatter (js/Intl.DateTimeFormat.
                               locale-name
                               #js {:timeZone zone-name
                                    :hour12   true
                                    :hour     "numeric"})]
               (fn [inst]
                 (second (re-matches #"^(\d+).*$" (.format formatter inst))))))
   "hh"    (fn [locale-name zone-name]
             (let [formatter (js/Intl.DateTimeFormat.
                               locale-name
                               #js {:timeZone zone-name
                                    :hour12   true
                                    :hour     "2-digit"})]
               (fn [inst]
                 (second (re-matches #"^(\d+).*$" (.format formatter inst))))))
   "H"     (std-formatter {:hour12 false :hour "numeric"})
   "HH"    (std-formatter {:hour12 false :hour "2-digit"})
   "y"     (std-formatter {:year "numeric"})
   "yy"    (std-formatter {:year "2-digit"})
   "yyy"   (std-formatter {:year "numeric"})
   "yyyy"  (std-formatter {:year "numeric"})
   "E"     (std-formatter {:weekday "short"})
   "EE"    (std-formatter {:weekday "long"})
   "EEE"   (std-formatter {:weekday "narrow"})
   "s"     (std-formatter {:second "numeric"})
   "ss"    (std-formatter {:second "numeric" :zero-pad? true})})

(defn new-formatter
  "Build a formatter. Returns a `(fn [inst] string?)`."
  [format-str locale-name zone-name]
  (let [tokens     (tokenize format-str)
        generator  (fn [token]
                     (let [f (get format-map token)]
                       (if f
                         (f locale-name zone-name)
                         (constantly token))))
        generators (mapv generator tokens)]
    (fn [inst] (str/join (map (fn [gen] (gen inst)) generators)))))