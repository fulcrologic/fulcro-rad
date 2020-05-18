(ns com.fulcrologic.rad.control-options
  "Options definitions and documentation for control options. Used by reports and containers.")

(def control-layout
  "Controls are normally laid out by simply throwing all of the buttons
   in a sequence, and throwing all of the non-buttons into a form. No layout in particular is guaranteed by RAD
   (though you rendering plugin may provide one).

   This option is a HINT to the rendering plugin as to how you'd *like* the controls to be placed on the screen. The
   content of this option is a map should generally look like:

   ```
   {:action-buttons [::a ::b ::c]
    :inputs [[::d]
             [::e ::f]]
   ```

   Where the keywords are the control keys that you wish to place in those respective positions in the UI. See
   `controls`."
  :com.fulcrologic.rad.control/control-layout)

(def controls
  "A map of control definitions, which can be action buttons or inputs.

   Input control values are stored in the report's parameters, and can be used in the filtering/sorting of the
   rows.

   Each control is given a unique key, and a map that describes the control. A typical value of this option will look like:

   ```
   :com.fulcrologic.rad.control/controls {::new-invoice {:label  \"New Invoice\"
                                                         :type   :button
                                                         :action (fn [this] (form/create! this InvoiceForm))}
                                          :show-inactive? {:type          :boolean
                                                           :style         :toggle
                                                           :default-value false
                                                           :onChange      (fn [this _] (report/reload! this))
                                                           :label         \"Show Inactive Accounts?\"}}
                                          ::new-account {:label  \"New Account\"
                                                         :type   :button
                                                         :action (fn [this] (form/create! this AccountForm))}}
   ```

   The types of controls supported will depend on your UI plugin and the component you're defining.

   See also `control-layout`.
   "
  :com.fulcrologic.rad.control/controls)
