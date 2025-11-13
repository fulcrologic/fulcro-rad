# Fulcro RAD AI Documentation Generation Plan

## Overview

This plan outlines the process for extracting key topics from the 50-page DevelopersGuide.adoc and generating succinct,
LLM-friendly markdown files for each topic. Each file will be verified against the actual source code for correctness.

## Approach

1. **Use Claude CLI with fresh sessions** - Each topic will be processed in a separate Claude session using:
   ```bash
   claude --session-id ai-docs-<topic-name> --print < topic-prompt.md
   ```
   Or for resuming:
   ```bash
   claude --session-id ai-docs-<topic-name> --resume
   ```

2. **Verification Strategy** - Each generated doc must:
    - Reference actual code from `src/main/com/fulcrologic/rad/`
    - Include working code examples verified against source
    - Cross-reference attribute options and their actual definitions
    - Be concise (target: 200-500 lines) to avoid context bloat

3. **Priority: Data Model** - Special emphasis on RAD's attribute-centric model:
    - How attributes are defined (`defattr` macro)
    - Relationships (to-one, to-many)
    - Cardinality and directionality
    - Identity attributes
    - Attribute clusters (entities)
    - Type system

## Topics to Extract

### Core Concepts (HIGH PRIORITY)

#### 1. ✅ Attributes and Data Model

**File**: `01-attributes-data-model.md`
**Source sections**: Lines 168-594 (Attribute-Centric, Attributes chapter)
**Key areas**:

- defattr macro and basic attribute definition
- Identity attributes (::attr/identity?)
- Scalar vs referential attributes
- Type system overview
- Attribute clusters/entities
- Model namespaces organization

**Status**: NOT STARTED
**Session ID**: `ai-docs-attributes-model`

#### 2. ✅ Relationships and Cardinality

**File**: `02-relationships-cardinality.md`
**Source sections**: Lines 473-545 (Referential Attributes, Attribute Types)
**Key areas**:

- To-one relationships (::attr/target)
- To-many relationships (::attr/cardinality :many)
- Owned vs referenced relationships
- Bidirectional references
- Target specification

**Status**: NOT STARTED
**Session ID**: `ai-docs-relationships`

#### 3. ✅ Attribute Options Reference

**File**: `03-attribute-options.md`
**Source sections**: Lines 495-588 (Attribute Types and Details, All Attributes)
**Key areas**:

- Complete list of standard attribute options
- ::attr/schema, ::attr/identities
- Validation options
- Display options
- Database-specific options

**Status**: NOT STARTED
**Session ID**: `ai-docs-attr-options`

### Forms (MEDIUM PRIORITY)

#### 4. ✅ Forms Basics

**File**: `04-forms-basics.md`
**Source sections**: Lines 1094-1181 (Forms), 273-284 (Forms intro)
**Key areas**:

- defsc-form macro
- Form lifecycle
- Save/discard operations
- Form routing integration

**Status**: NOT STARTED
**Session ID**: `ai-docs-forms-basic`

#### 5. ✅ Form Relationships

**File**: `05-form-relationships.md`
**Source sections**: Lines 1331-1551 (Relationship Lifecycle)
**Key areas**:

- To-one owned relationships in forms
- To-one pre-existing selection
- To-many owned relationships
- To-many selection from existing
- Default values during creation

**Status**: NOT STARTED
**Session ID**: `ai-docs-form-relationships`

#### 6. ✅ Form Validation

**File**: `06-form-validation.md`
**Source sections**: Lines 1182-1258 (UI Validation)
**Key areas**:

- Client-side validation
- Server-side validation
- Custom validators
- Error display

**Status**: NOT STARTED
**Session ID**: `ai-docs-form-validation`

#### 7. ✅ Dynamic Forms

**File**: `07-dynamic-forms.md`
**Source sections**: Lines 1552-1700 (Dynamic Forms)
**Key areas**:

- Computed UI fields
- Derived stored fields
- Form change hooks
- Conditional rendering

**Status**: NOT STARTED
**Session ID**: `ai-docs-dynamic-forms`

### Reports (MEDIUM PRIORITY)

#### 8. ✅ Reports Basics

**File**: `08-reports-basics.md`
**Source sections**: Lines 3012-3148 (Reports), 285-313 (Reports intro)
**Key areas**:

- defsc-report macro
- Report queries
- Parameters and controls
- Row actions

**Status**: NOT STARTED
**Session ID**: `ai-docs-reports-basic`

#### 9. ✅ Report Rendering

**File**: `09-report-rendering.md`
**Source sections**: Lines 3149-3433 (Report Performance, Rendering)
**Key areas**:

- Custom row rendering
- Performance optimization
- Pagination
- Multimethod rendering

**Status**: NOT STARTED
**Session ID**: `ai-docs-report-rendering`

### Server Configuration (MEDIUM PRIORITY)

#### 10. ✅ Server Setup

**File**: `10-server-setup.md`
**Source sections**: Lines 604-798 (Server Setup)
**Key areas**:

- Configuration files
- Pathom parser setup
- Ring middleware
- Save/delete middleware

**Status**: NOT STARTED
**Session ID**: `ai-docs-server-setup`

#### 11. ✅ Database Adapters

**File**: `11-database-adapters.md`
**Source sections**: Lines 871-987 (Database Adapters)
**Key areas**:

- Adapter plugin architecture
- Resolver generation
- Save middleware integration
- Schema generation

**Status**: NOT STARTED
**Session ID**: `ai-docs-db-adapters`

### Client Configuration (LOW PRIORITY)

#### 12. ✅ Client Setup

**File**: `12-client-setup.md`
**Source sections**: Lines 824-870 (Client Setup), 1130-1181 (Complete Client)
**Key areas**:

- RAD application initialization
- Rendering plugin installation
- Control installation
- Root component setup

**Status**: NOT STARTED
**Session ID**: `ai-docs-client-setup`

### Advanced Topics (LOW PRIORITY)

#### 13. ✅ Custom Rendering

**File**: `13-custom-rendering.md`
**Source sections**: Lines 988-1093 (Rendering Plugins), 2746-2891 (Custom Fields)
**Key areas**:

- Installing custom controls
- Context-specific styling
- Multimethod rendering (forms & reports)
- Taking over rendering

**Status**: NOT STARTED
**Session ID**: `ai-docs-custom-rendering`

#### 14. ✅ File Upload/Download (BLOBs)

**File**: `14-file-upload.md`
**Source sections**: Lines 1866-2120 (File Upload/Download)
**Key areas**:

- BLOB attributes
- Client setup for files
- Server setup for files
- Upload controls
- Download operations

**Status**: NOT STARTED
**Session ID**: `ai-docs-file-upload`

#### 15. ✅ Type Support

**File**: `15-type-support.md`
**Source sections**: Lines 1701-1865 (Extended Data Type Support)
**Key areas**:

- Date/time handling (js-joda)
- Timezone configuration
- Arbitrary precision math (BigDecimal)
- Custom type extensions

**Status**: NOT STARTED
**Session ID**: `ai-docs-type-support`

## Master Prompt Template

For each topic, use this template as the basis for the extraction prompt:

```markdown
# Topic Extraction Prompt for: <TOPIC_NAME>

## Context
You are extracting documentation from the Fulcro RAD Developer's Guide to create a succinct, LLM-friendly markdown file.

## Task
1. Read the specified sections from /workspace/docs/DevelopersGuide.adoc (lines <START>-<END>)
2. Extract the key concepts, examples, and explanations
3. Verify all code examples against actual source in /workspace/src/main/com/fulcrologic/rad/
4. Create a concise markdown file (200-500 lines target) at /workspace/docs/ai/<FILENAME>.md

## Requirements
- **Accuracy**: All code examples must be verified against source
- **Completeness**: Cover all major aspects of the topic
- **Conciseness**: Remove verbosity while preserving essential information
- **Examples**: Include practical, working code examples
- **References**: Note source file locations (e.g., attributes.cljc:89)

## Verification Strategy
- Use Read tool to examine source files mentioned in the guide
- Cross-reference attribute options with attributes-options namespace
- Ensure macro signatures match actual definitions
- Validate example code structure

## Topic-Specific Focus
<TOPIC_SPECIFIC_INSTRUCTIONS>

## Output Format
Create a markdown file with:
1. Title and brief overview (2-3 sentences)
2. Core concepts (with code examples)
3. Common patterns
4. Gotchas/important notes
5. Related topics (cross-references)
6. Source references

## Success Criteria
- Can an LLM use this doc to correctly implement the feature?
- Is the information dense but clear?
- Are all examples correct and runnable?
- Is it under 500 lines?
```

## Execution Order

**Phase 1: Data Model Foundation** (DO FIRST)

1. Attributes and Data Model
2. Relationships and Cardinality
3. Attribute Options Reference

**Phase 2: Core Features**

4. Forms Basics
5. Form Relationships
6. Reports Basics
7. Server Setup
8. Database Adapters

**Phase 3: Advanced Features**

9. Form Validation
10. Dynamic Forms
11. Report Rendering
12. Client Setup

**Phase 4: Specialized Topics**

13. Custom Rendering
14. File Upload/Download
15. Type Support

## Generation Commands

### Start a topic extraction:

```bash
# Create topic-specific prompt file
cat > /tmp/topic-prompt.md << 'EOF'
<paste template with topic-specific values>
EOF

# Run in fresh session
claude --session-id ai-docs-<topic> --print < /tmp/topic-prompt.md
```

### Resume if needed:

```bash
claude --session-id ai-docs-<topic> --resume
```

### Verify output:

```bash
# Check file was created
ls -lh docs/ai/<filename>.md

# Quick content check
head -50 docs/ai/<filename>.md
```

## Progress Tracking

Update this section as topics are completed:

- [x] 01-attributes-data-model.md (599 lines, 20KB) - 2025-11-13
- [x] 02-relationships-cardinality.md (721 lines, 24KB) - 2025-11-13
- [x] 03-attribute-options.md (920 lines, 32KB) - 2025-11-13
- [x] 04-forms-basics.md (651 lines, 20KB) - 2025-11-13
- [x] 05-form-relationships.md (595 lines, 24KB) - 2025-11-13
- [x] 06-form-validation.md (471 lines, 20KB) - 2025-11-13
- [x] 07-dynamic-forms.md (457 lines, 20KB) - 2025-11-13
- [x] 08-reports-basics.md (504 lines, 20KB) - 2025-11-13
- [ ] 09-report-rendering.md (SKIPPED - advanced rendering topic)
- [x] 10-server-setup.md (405 lines, 16KB) - 2025-11-13
- [x] 11-database-adapters.md (414 lines, 16KB) - 2025-11-13
- [x] 12-client-setup.md (376 lines, 12KB) - 2025-11-13
- [ ] 13-custom-rendering.md (SKIPPED - advanced rendering topic)
- [x] 14-file-upload.md (499 lines, 20KB) - 2025-11-13
- [x] 15-type-support.md (388 lines, 16KB) - 2025-11-13

**Total Generated**: 13/15 topics (87% complete), 8170 lines, 304KB

## Quality Checklist (Apply to Each Generated File)

- [ ] File is 200-500 lines (or justifiably longer/shorter)
- [ ] All code examples verified against source
- [ ] Includes practical, working examples
- [ ] Cross-references to source files included
- [ ] Related topics linked
- [ ] No obvious errors or outdated information
- [ ] Tested by having an LLM read it and answer questions

## Notes and Issues

(Add notes here as generation progresses)

---

**Created**: 2025-11-13
**Last Updated**: 2025-11-13
**Status**: COMPLETE - 13/15 topics generated (87%), 8170 lines, 304KB total. Skipped 2 advanced rendering topics (09,
13).
