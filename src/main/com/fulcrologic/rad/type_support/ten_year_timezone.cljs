(ns ^:no-doc com.fulcrologic.rad.type-support.ten-year-timezone
  "NOTE: This is a super-hacky hack to get cljdocs to work. I don't want all of js-joda-timezone because it is
   HUGE, but if I don't use cljsjs I get no cljdoc. So, I recommend using a js-resolve section in shadow-cljs
   to re-map it.

   ```
  {:builds   {:main       {:target            :browser
                           :output-dir        \"resources/public/js/main\"
                           :asset-path        \"/js/main\"
                           :js-options        {:resolve
                                               {\"@js-joda/timezone\"
                                                {:target  :npm
                                                 :require \"@js-joda/timezone/dist/js-joda-timezone-10-year-range.min.js\"}}}}}}
   ```
"
  (:require
    ["@js-joda/timezone"]))
