(ns com.fulcrologic.rad.authorization.simple-authorization
  "An implementation of an authorization system for applications that provides very simple logic:

  * The `determine` will only do routing async. All other requests immediately go through `can?`.
  * `determine` for routing looks on the route target to see if an authority is required. If not, it simply answers
    `cached-true` immediately. If an authority is required but not present it will start an auth sequence with a request to
    signal when that is complete.
  * `can?` looks for narrowed props for Read and Write permissions in the `context`. If these are nil, then
    it returns `uncached-true`. E.g. `:invoice/date` will look for `:invoice.date/permissions`. Forms and
    reports must be configured to ask for these additional properties, and resolvers must be configured to
    provide them.


    ")
