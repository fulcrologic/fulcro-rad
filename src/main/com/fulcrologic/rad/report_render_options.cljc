(ns com.fulcrologic.rad.report-render-options
  (:require
    [com.fulcrologic.rad.options-util :refer [defoption]]))

(defoption style
  "Report or Attribute option. The general style of report to render. This can affect things like rendering,
   state machine behavior, etc. Can be a keyword or a `(fn [report-instance] keyword?)`.")

(defoption header-style
  "Report or Attribute option. The style of header to generate for the report (typically column headings). keyword or (fn [report-instance] keyword?).
   Defaults to whatever rro/style is.")

(defoption footer-style
  "Report or Attribute option. The style of footer to generate below the report.
   Keyword or (fn [report-instance] keyword?). Defaults to whatever rro/style is.")

(defoption control-style
  "Report or Attribute option. The style of report controls (above the body) to render.
   Keyword or `(fn [report-instance] kw). Defaults to whatever rro/style is.")

(defoption body-style
  "Report or Attribute option. The style of the body of the report (the container around rows).
   Defaults to whatever rro/style is.")

(defoption row-style
  "Report or Attribute option. The style to use for the rows.
   Defaults to whatever rro/style is.")
