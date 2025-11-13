# Extract Documentation: Attributes and Data Model

## Your Task

Extract and condense documentation for **Attributes and Data Model** from the Fulcro RAD Developer's Guide into a
succinct, accurate, LLM-friendly markdown file.

## Source Information

- **Source File**: `/workspace/docs/DevelopersGuide.adoc`
- **Relevant Lines**: 168-594
    - Lines 168-240: Attribute-Centric introduction
    - Lines 331-594: Attributes chapter (Model Namespaces, Identity, Data Types, Clusters, Referential, All Attributes)
- **Output File**: `/workspace/docs/ai/01-attributes-data-model.md`
- **Target Length**: 400-600 lines (this is the most important topic, deserves more depth)

## Verification Requirements

All information must be verified against source code in `/workspace/src/main/com/fulcrologic/rad/`:

1. **Code Examples**: Every code example must match actual implementation
2. **Macro Signatures**: Verify `defattr` signature in `attributes.cljc:54-92`
3. **Options**: Cross-reference with `attributes-options.cljc` for all ::attr/* options
4. **Behavior**: Check cardinality handling, identity?, schema usage

### Key Files to Check:

- `attributes.cljc` - Core attribute definitions (especially lines 1-100)
- `attributes-options.cljc` - All standard attribute options (read entire file)
- `form.cljc` - How forms use attributes
- `report.cljc` - How reports use attributes
- Related namespaces that define specific options

## Topic-Specific Focus

This is THE MOST IMPORTANT topic - the foundation of RAD. Emphasize:

### Critical Areas:

1. **defattr Macro**
    - Full signature and usage
    - Required vs optional parameters
    - How it differs from plain maps
    - Registered maps concept (if relevant)

2. **Identity Attributes**
    - `::attr/identity?` true (primary/natural keys)
    - How they differ from regular attributes
    - UUID vs other identity types
    - Multiple identity attributes on same entity

3. **Attribute Types**
    - Scalar types: :string, :int, :long, :uuid, :instant, :boolean, :decimal, :keyword, etc.
    - Reference type: :ref
    - How types relate to database adapters
    - Type extensibility

4. **Schema/Clustering**
    - `::attr/schema` - what it means and how it's used
    - How attributes cluster into entities/tables/documents
    - Naming conventions for schemas
    - Multiple schemas in one app

5. **Referential Attributes (Basic)**
    - `::attr/target` for to-one
    - `::attr/cardinality :many` for to-many
    - `::attr/targets` for polymorphic refs
    - Basic understanding of directionality

6. **Model Namespaces**
    - Organization strategies
    - Domain-style naming
    - Co-location patterns

## Required Sections

### 1. Overview

What attributes are, why they're the center of RAD, RDF-style inspiration.

### 2. Defining Attributes

- defattr macro detailed
- Basic example
- Minimal vs full attribute
- Common options overview

### 3. Identity Attributes

- What makes an attribute an identity
- Usage patterns
- Examples with UUID, other types

### 4. Scalar Attributes

- Standard types
- Examples of each common type
- Type selection guidance

### 5. The Schema Concept

- What ::attr/schema means
- How it groups attributes into entities
- Examples of multiple schemas

### 6. Referential Attributes (Introduction)

- Basic :ref type
- ::attr/target specification
- Simple to-one example
- Simple to-many example (::attr/cardinality :many)
- Note: Point to 02-relationships-cardinality.md for details

### 7. Model Organization

- Namespace strategies
- File organization patterns
- Attribute registries

### 8. Common Patterns

- Standard entity setup (id + fields)
- Lookup attributes
- Computed attributes (brief mention)

### 9. Important Notes

- CLJC requirement for full-stack
- Qualified keywords requirement
- Schema vs namespace distinction
- Hot reload considerations

### 10. Related Topics

- Detailed relationships: 02-relationships-cardinality.md
- All options: 03-attribute-options.md
- Usage in forms: 04-forms-basics.md
- Usage in reports: 08-reports-basics.md

### 11. Source References

File:line references for key definitions

## Style Guidelines

- **Be Concise**: But this topic needs depth - don't oversimplify
- **Be Precise**: This is foundational - accuracy is critical
- **Show Relationships**: How attributes relate to entities/schemas
- **Use Real Examples**: From actual RAD patterns (check demo if needed)
- **Emphasize Key Points**: Identity, schema, and ref are crucial concepts

## Validation Steps

Before finalizing:

1. [ ] Verify defattr signature matches source exactly
2. [ ] Check all ::attr/* options exist in attributes-options.cljc
3. [ ] Confirm identity? behavior description is correct
4. [ ] Validate ref/target/cardinality examples
5. [ ] Ensure schema explanation matches actual usage
6. [ ] Test: Can someone define a full entity (Person with id, name, address ref) using this doc?

## Output Format

```markdown
# Attributes and Data Model

## Overview

Fulcro RAD is attribute-centric. An attribute is an RDF-style description of a single fact about your domain...

## Defining Attributes with defattr

The `defattr` macro is the primary way to define attributes...

\`\`\`clojure
;; From attributes.cljc:54-92
(defattr symbol qualified-keyword data-type options-map)

;; Minimal example
(defattr id :item/id :uuid
  {::attr/identity? true
   ::attr/schema :production})
\`\`\`

...

## Identity Attributes

Attributes marked with `::attr/identity? true` are special...

## Scalar Types

RAD supports these built-in scalar types:
- `:string` - Text data
- `:uuid` - Universally unique identifiers
...

## The Schema Concept

The `::attr/schema` option groups attributes into logical entities...

## Reference Attributes: Introduction

Attributes with type `:ref` connect entities...

\`\`\`clojure
;; To-one relationship
(defattr address :person/address :ref
  {::attr/target :address/id
   ::attr/schema :production})

;; To-many relationship
(defattr friends :person/friends :ref
  {::attr/target :person/id
   ::attr/cardinality :many
   ::attr/schema :production})
\`\`\`

For complete details on relationships, see [02-relationships-cardinality.md](02-relationships-cardinality.md).

## Model Organization

...

## Common Patterns

### Standard Entity Pattern

\`\`\`clojure
(ns com.example.model.account
  (:require [com.fulcrologic.rad.attributes :as attr :refer [defattr]]))

;; Identity
(defattr id :account/id :uuid
  {::attr/identity? true
   ::attr/schema :production})

;; Scalars
(defattr email :account/email :string
  {::attr/schema :production
   ::attr/required? true})

;; References
(defattr addresses :account/addresses :ref
  {::attr/target :address/id
   ::attr/cardinality :many
   ::attr/schema :production})
\`\`\`

## Important Notes

- **CLJC Files**: For full-stack RAD, attributes MUST be in .cljc files
- **Qualified Keywords**: Attribute names must be qualified (namespace/name)
- **Schema Grouping**: All attributes with the same ::attr/schema form an entity
...

## Related Topics

- **Relationships in Detail**: [02-relationships-cardinality.md](02-relationships-cardinality.md)
- **Complete Option Reference**: [03-attribute-options.md](03-attribute-options.md)
- **Using Attributes in Forms**: [04-forms-basics.md](04-forms-basics.md)
- **Using Attributes in Reports**: [08-reports-basics.md](08-reports-basics.md)

## Source References

- **Macro Definition**: `attributes.cljc:54-92` - defattr macro
- **Attribute Creation**: `attributes.cljc:29-51` - new-attribute function
- **Standard Options**: `attributes-options.cljc` - all ::attr/* options
- **Type Predicates**: `attributes.cljc:94-105` - to-many?, to-one?, etc.
```

## Execution

Create this prompt file and run:

```bash
mkdir -p /workspace/docs/ai/prompts
# Save this content to prompts/01-attributes-prompt.md
# Then run:
claude --session-id ai-docs-attributes --print < /workspace/docs/ai/prompts/01-attributes-prompt.md
```
