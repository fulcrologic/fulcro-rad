# DevelopersGuide.adoc Structure Reference

Quick reference for locating topics when creating extraction prompts.

## Main Sections

### Introduction (Lines 64-329)

- **64-116**: Introduction, Overview
- **117-155**: Core Elements (Forms, Reports, Containers, Routing, BLOBs)
- **156-167**: Required Dependencies
- **168-207**: Attribute-Centric (Key concept intro)
- **208-234**: Attribute Options - Documentation
- **235-240**: Extensibility
- **241-272**: Data Modelling, Storage, and API (Schema, Resolvers, Security)
- **273-284**: Forms (High-level intro)
- **285-313**: Reports (High-level intro)
- **314-329**: Platform Targets

### = Attributes (Lines 331-603)

**Primary topic: Attribute system fundamentals**

- **331-365**: Chapter intro
- **366-393**: Model Namespaces
- **394-413**: Identity Attributes
- **414-420**: Data Types
- **421-436**: Scalar Attributes
- **437-472**: Attribute Clusters (Entities/Tables/Documents)
- **473-494**: Referential Attributes
- **495-545**: Attribute Types and Details
- **546-588**: All Attributes (comprehensive options list)
- **589-603**: Attribute Hot Code Reload

### = Server Setup (Lines 604-823)

**Primary topic: Server configuration**

- **604-608**: Chapter intro
- **609-647**: Configuration Files
- **648-693**: Form Middleware (intro)
    - **656-678**: Save Middleware
    - **679-693**: Delete Middleware
- **694-753**: Pathom Parser
- **754-798**: The Server (Ring) Middleware
- **799-823**: The Server

### = Client Setup (Lines 824-870)

**Primary topic: Client initialization**

- **824-870**: Complete client setup

### = Database Adapters (Lines 871-987)

**Primary topic: Database integration**

- **871-889**: Chapter intro
- **890-906**: Database Adapters
- **907-918**: The Server-side Resolvers
- **919-987**: Form Middleware (database-specific)
    - **928-939**: The Parser `env`
    - **940-983**: Save Middleware (detailed)
    - **984-987**: Delete Middleware

### = Leveraging Rendering Plugins (Lines 988-1093)

**Primary topic: UI customization**

- **988-1004**: Chapter intro
- **1005-1025**: Attribute and Context-Specific Style
- **1026-1093**: Installing Controls

### = Forms (Lines 1094-2120)

**Primary topic: Form system**

#### Basic Forms (Lines 1094-1307)

- **1094-1129**: Forms basics
- **1130-1181**: A Complete Client
- **1182-1258**: UI Validation
- **1259-1307**: Composing Forms

#### Form Relationships (Lines 1308-1551)

- **1308-1330**: Default Values During Creation
- **1331-1345**: Relationship Lifecycle
- **1346-1356**: To-One Relation, Owned by Reference
- **1357-1492**: To-One Relation to Pre-existing
- **1493-1547**: To-Many Relationships, Owned by Parent
- **1548-1551**: To-Many, Selected From Pre-existing

#### Dynamic Forms (Lines 1552-1700)

- **1552-1559**: Dynamic Forms intro
- **1560-1581**: Purely Computed UI Fields
- **1582-1643**: Derived, Stored Fields
- **1644-1700**: Form Change and I/O

#### Extended Types (Lines 1701-1865)

- **1701-1710**: Extended Data Type Support
- **1711-1803**: Dates and Time
    - **1741-1803**: Setting the Time Zone
- **1804-1865**: Arbitrary Precision Math and Storage

#### File Upload (Lines 1866-2120)

- **1866-1880**: Chapter intro
- **1881-1941**: General Operation
- **1942-1978**: Defining BLOB attributes
- **1979-1994**: Setting up the Client
- **1995-2084**: Setting up the Server
- **2085-2090**: File Arity
- **2091-2109**: Rendering File Upload Controls
- **2110-2120**: Downloading Files

### = Advancing Your Forms (Lines 2121-3011)

**Primary topic: Advanced form techniques**

- **2121-2127**: Chapter intro
- **2128-2168**: Form System Architecture

#### Server (Lines 2169-2279)

- **2169-2175**: Server intro
- **2176-2224**: Save Operation
- **2225-2266**: Save Middleware (advanced)
    - **2267-2274**: Autojoin Middleware
- **2275-2279**: Delete Middleware

#### UI (Lines 2280-2994)

- **2280-2289**: UI intro
- **2290-2304**: Rendering Environment
- **2305-2745**: Form Rendering via Multimethods
    - **2346-2401**: Dispatch Hierarchy
    - **2402-2429**: Predefined Methods
    - **2430-2493**: Render Multimethod Dispatch
    - **2494-2745**: Example Setup (extensive examples)
- **2746-2777**: Custom Field Rendering
- **2778-2822**: Form Abandonment
- **2823-2860**: Augmenting Form Behavior
- **2861-2917**: Taking Over Rendering
    - **2892-2917**: Subforms

#### Polymorphic Subforms (Lines 2918-2994)

- **2918-2994**: Subforms with Multiple Target types

#### Navigation (Lines 2995-3011)

- **2995-3011**: Choosing Where to go After an Edit

### = Reports (Lines 3012-3433)

**Primary topic: Report system**

- **3012-3060**: Reports intro and basic usage
- **3061-3108**: Handling Report Queries
- **3109-3142**: Customizing Report Rendering
- **3143-3148**: Report Row Rendering
- **3149-3168**: Report Performance
- **3169-3433**: Report Rendering with Multimethods
    - **3187-3239**: The Rendering Multimethods
    - **3240-3433**: Example Setup (extensive examples)

### = Arbitrary Form and Report UI Composition (Lines 3434-3520+)

**Primary topic: Dynamic composition**

- **3434-3446**: Chapter intro
- **3447-3456**: Composing a Component in Dynamically
- **3457-3484**: Starting Forms
- **3485-3489**: Starting Reports
- **3490+**: Better Dynamic Composition: Hooks-based Forms and Reports

## Topic Mapping

| Doc File                        | Guide Lines                             | Key Sections                                              |
|---------------------------------|-----------------------------------------|-----------------------------------------------------------|
| 01-attributes-data-model.md     | 168-594                                 | Attribute-Centric (168-240), Attributes chapter (331-603) |
| 02-relationships-cardinality.md | 473-545, 1331-1551                      | Referential Attributes, Form Relationships                |
| 03-attribute-options.md         | 495-588, entire attributes-options.cljc | All Attributes section                                    |
| 04-forms-basics.md              | 273-284, 1094-1181                      | Forms intro, Basic Forms                                  |
| 05-form-relationships.md        | 1308-1551                               | Default Values through To-Many relationships              |
| 06-form-validation.md           | 1182-1258                               | UI Validation                                             |
| 07-dynamic-forms.md             | 1552-1700                               | Dynamic Forms (computed, derived, I/O)                    |
| 08-reports-basics.md            | 285-313, 3012-3108                      | Reports intro, basic usage, queries                       |
| 09-report-rendering.md          | 3109-3433                               | Report rendering customization                            |
| 10-server-setup.md              | 604-823                                 | Server Setup chapter                                      |
| 11-database-adapters.md         | 871-987                                 | Database Adapters chapter                                 |
| 12-client-setup.md              | 824-870, 1130-1181                      | Client Setup, Complete Client                             |
| 13-custom-rendering.md          | 988-1093, 2290-2917, 3169-3433          | Rendering Plugins, Form/Report Multimethods               |
| 14-file-upload.md               | 1866-2120                               | File Upload/Download chapter                              |
| 15-type-support.md              | 1701-1865                               | Extended Data Type Support                                |

## Finding Specific Topics

### Attributes

- Basic definition: 168-240
- Comprehensive reference: 331-603
- Options list: 495-588
- Referential: 473-494

### Forms

- Introduction: 273-284
- Basic usage: 1094-1181
- Relationships: 1308-1551
- Validation: 1182-1258
- Dynamic: 1552-1700
- Advanced: 2121-3011
- File upload: 1866-2120

### Reports

- Introduction: 285-313
- Basic: 3012-3108
- Rendering: 3109-3433

### Server/Client

- Server: 604-823
- Client: 824-870
- Database: 871-987

### Rendering

- Plugins: 988-1093
- Form multimethods: 2305-2917
- Report multimethods: 3169-3433

### Special Topics

- BLOBs: 1866-2120
- Date/Time: 1711-1803
- Decimal: 1804-1865
- Dynamic composition: 3434-3520+

## Notes

1. Line numbers are approximate - verify with actual file
2. Some topics span multiple sections
3. Examples are often in the latter parts of sections
4. Cross-references between sections are common
5. Check source code for definitive behavior

## Usage

When creating a new extraction prompt:

1. Find topic in this reference
2. Note primary and related line ranges
3. Check for cross-references
4. Verify line numbers in actual file:
   ```bash
   sed -n '168,240p' docs/DevelopersGuide.adoc
   ```
5. Update prompt template with accurate ranges

---

**Note**: This is a living document. Update as the DevelopersGuide changes.
