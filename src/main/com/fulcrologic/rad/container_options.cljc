(ns com.fulcrologic.rad.container-options
  "Options specific to RAD containers.")

(def children
  "A vector of RAD components to combine under common control in a container."
  :com.fulcrologic.rad.container/children)

(def layout
  "A vector of vectors of RAD components in the container.

   Each inner vector will allocate a grid row.

   ```
   co/children [ReportA WidgetA WidgetB]
   co/layout [[ReportA]
              [WidgetA WidgetB]]
   ```

   This is optional. The default is to simply place the reports one after another, top to bottom on the screen.

   Additional layout configuration may be available in your container renderer."
  :com.fulcrologic.rad.container/layout)

(def layout-style
  "A keyword (hint) of what layout style to use. Support relies on the underlying rendering plugin."
  :com.fulcrologic.rad.container/layout-style)

