(ns com.fulcrologic.rad.state-machines.server-paginated-report-options)

(def point-in-time-view?
  "Report option. Boolean (default true). When true the report will send a point-in-time timestamp to the resolver to attempt to
   cause all pages of the report to come from the same point-in-time of the server's data store."
  :com.fulcrologic.rad.state-machines.server-paginated-report/point-in-time-view?)

(def direct-page-access?
  "Report option. Boolean (default true). When true the report will ask the resolver for the total result count on the first
   page it loads so it can show a complete list of the available pages, and a total result count. This can
   be expensive, so when this is false it will not ask for the total, but will only track the current offset and
   whether or not there is a next page."
  :com.fulcrologic.rad.state-machines.server-paginated-report/direct-page-access?)
