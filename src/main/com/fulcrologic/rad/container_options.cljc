(ns com.fulcrologic.rad.container-options
  "Options specific to RAD containers.")

(def children
  "A map of RAD components to combine under common control in a container. Each entry is a key (which will be the ID
   of the element) and a RAD component (typically a report).

   ```
   {:a SalesReport
    :b OtherReport
    :c Statistic}
   ```

   Note that you can use the same component class more than once, which can allow for simultaneous (but perhaps differently
   shaped) views of the same data."
  :com.fulcrologic.rad.container/children)

(def layout
  "A vector of vectors of IDs (or description maps) of components in the container.

   Each inner vector will allocate a grid row.

   ```
   co/children {:a ReportA
                :b WidgetA
                :c WidgetB}
   co/layout [[:a]
              [:b :c]]
   ```

   The `layout` values can be maps instead of keywords, in which case the `:id` key will designate which child the
   layout parameters go with. Your UI render plugin can then define any number of additional things you can decorate
   the entry with. For example, the grid system might allow for component sizing:

   ```
   co/layout [[{:id :a :width 3}]
              [{:id :b :width 2} {:id :c :width 1}]]
   ```

   This is optional. The default is to simply place the reports one after another, top to bottom on the screen.

   Additional layout configuration may be available in your container renderer."
  :com.fulcrologic.rad.container/layout)

(def layout-style
  "A keyword (hint) of what layout style to use. Support relies on the underlying rendering plugin."
  :com.fulcrologic.rad.container/layout-style)

(def route
  "A string that will be used as this container's path element in the routing tree. Must be unique among siblings. If you
   do not define this option, then the container will not behave properly as a route target in dynamic routing."
  :com.fulcrologic.rad.container/route)

(def title
  "A string of `(fn [this] element-or-string)` that will be used as this container's title."
  :com.fulcrologic.rad.container/title)
