See git commit history for further change log entries. 
This file is often not updated.

1.6.0
-----
- Support for Fulcro's new Dynamic Routing improvements (routing/route-to!) 
- New multimethod basis for rendering (bw compatible with plugin rendering via maps). See Developer's Guide.
- Improved support for dynamically generating artifacts (reports/forms) at runtime.
- Expanded options available in fn version of many options
- Allow picker options on attribute model
- Added exported clj-kondo config
- Added support for raw EQL query on pickers
- Added rendering hint to omit labels on form fields (for rendering things like tables). See `form/render-input`
- Added rendering hierarchy so that rendering of some element type can leverage an existing definition
- Can now place `fo/subform` on ref attributes instead of having to colocate them on the form
- Added similar kinds of rendering support for reports
