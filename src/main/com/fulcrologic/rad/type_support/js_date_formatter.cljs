(ns com.fulcrologic.rad.type-support.js-date-formatter
  "An implementation of date formatting that uses ISO format specifiers, but uses browser built-in Intl support
   to get the locale-dependent values"
  (:require
    [clojure.string :as str]
    [cljc.java-time.zoned-date-time :as zdt]
    [taoensso.timbre :as log]
    [cljc.java-time.zone-id :as zone-id]
    [cljc.java-time.zone-offset :as zo]
    [cljc.java-time.instant :as instant]))

(def tokenize
  "[format-str]

   Turns a string that has repeating characters into groups of those repeating letters. This function is memoized, so
   it is best to ONLY use it on date/time format patterns, of which there will likely be few."
  (fn [format-str]
    (let [add-token (fn [{:keys [literal? letters] :as acc}]
                      (if (seq letters)
                        (update acc :tokens conj (cond->> (str/join letters)
                                                   literal? (array-map :literal)))
                        acc))
          acc       (let [letters (seq format-str)]
                      (reduce
                        (fn [{:keys [literal? prior-letter] :as acc} letter]
                          (cond
                            (and (= \' letter) (= \' prior-letter)) (-> acc
                                                                      (assoc :literal? false
                                                                             :letters []
                                                                             :prior-letter "")
                                                                      (update :tokens conj {:literal \'}))
                            (and literal? (= \' letter)) (-> acc
                                                           (add-token)
                                                           (assoc :literal? false :letters [] :prior-letter ""))
                            (= \' letter) (-> acc
                                            (add-token)
                                            (assoc :literal? true :letters [] :prior-letter \'))

                            literal? (-> acc
                                       (assoc :prior-letter letter)
                                       (update :letters conj letter))

                            (= prior-letter letter) (update acc :letters conj letter)
                            :else (-> acc
                                    (add-token)
                                    (assoc :prior-letter letter)
                                    (assoc :letters [letter]))))
                        {:tokens       []
                         :literal?     false
                         :letters      []
                         :prior-letter nil}
                        letters))]
      (-> acc
        (add-token)
        :tokens))))

(defn- std-formatter [{:keys [zero-pad?] :as options}]
  (let [zero-pad (fn [s] (if (= 1 (count s))
                           (str "0" s)
                           s))]
    (fn [locale-name zone-name]
      (let [formatter (js/Intl.DateTimeFormat. locale-name (clj->js (merge {:timeZone zone-name}
                                                                      (dissoc options :zero-pad?))))]
        (fn [inst]
          (cond-> (.format formatter inst)
            zero-pad? zero-pad))))))

(defn- zone-name-formatter [format]
  (fn [locale-name zone-name]
    (let [formatter (js/Intl.DateTimeFormat. locale-name #js {:timeZone     zone-name
                                                              :timeZoneName format
                                                              :minute       "numeric"})]
      (fn [inst]
        (last (str/split (.format formatter inst) #"\s+"))))))

(defn- seconds->zone-offset [^long totalSeconds size]
  (let [absTotalSeconds (int (Math/abs totalSeconds))
        absSeconds      (int (mod absTotalSeconds 60))      ;
        absHours        (int (/ absTotalSeconds 3600))
        absMinutes      (int (mod (/ absTotalSeconds 60) 60))
        sign            (if (neg? totalSeconds) "-" "+")
        hours           (str (if (< absHours 10) "0" "") absHours)
        mins            (str (if (< absMinutes 10) "0" "") absMinutes)
        secs            (str (if (< absSeconds 10) "0" "") absSeconds)]
    (if (zero? totalSeconds)
      "Z"
      (case size
        1 (str sign hours (when (pos? absMinutes) mins))
        2 (str sign hours mins)
        3 (str sign hours ":" mins)
        4 (str sign hours mins (when (pos? absSeconds) secs))
        5 (str sign hours ":" mins (when (pos? absSeconds) (str ":" secs)))
        nil))))

(defn- zone-offset-formatter [size]
  (fn [_ zone-name]
    (let []
      (fn [inst]
        (let [z      (zone-id/of zone-name)
              i      (instant/of-epoch-milli (inst-ms (or inst (js/Date.))))
              offset (zdt/get-offset (zdt/of-instant i z))]
          (seconds->zone-offset (zo/get-total-seconds offset) size))))))

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
   ;; If you don't include some element of time, then zone name includes the whole darn date :(
   "Z"     (zone-name-formatter "short")
   "ZZ"    (zone-name-formatter "short")
   "ZZZ"   (zone-name-formatter "short")
   "ZZZZ"  (zone-name-formatter "long")
   "X"     (zone-offset-formatter 1)
   "XX"    (zone-offset-formatter 2)
   "XXX"   (zone-offset-formatter 3)
   "XXXX"  (zone-offset-formatter 4)
   "XXXXX" (zone-offset-formatter 5)
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
                     (if (map? token)
                       (constantly (:literal token))
                       (let [f (get format-map token)]
                         (if f
                           (f locale-name zone-name)
                           (constantly token)))))
        generators (mapv generator tokens)]
    (fn [inst] (str/join (map (fn [gen] (gen inst)) generators)))))
