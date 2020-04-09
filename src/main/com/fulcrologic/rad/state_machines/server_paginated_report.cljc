(ns com.fulcrologic.rad.state-machines.server-paginated-report
  "A Report state machine that is tuned to work with large data sets on the server, where a small subset of the data
   is loaded at any given time, and the server is responsible for sorting and filtering. Requires that the data's
   resolver supports parameters that indicate the current sort key, sort direction, and filter(s).")

;; TODO: write server paginated report support
