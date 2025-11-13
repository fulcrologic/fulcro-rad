# Fulcro RAD Topic Extraction Prompt Template

**Instructions**: Copy this template, fill in the placeholders marked with `<>`, and use with Claude CLI.

---

# Extract Documentation: <TOPIC_NAME>

## Your Task

Extract and condense documentation for **<TOPIC_NAME>** from the Fulcro RAD Developer's Guide into a succinct, accurate,
LLM-friendly markdown file.

## Source Information

- **Source File**: `/workspace/docs/DevelopersGuide.adoc`
- **Relevant Lines**: <START_LINE>-<END_LINE>
- **Output File**: `/workspace/docs/ai/<OUTPUT_FILENAME>.md`
- **Target Length**: 200-500 lines (adjust if topic demands more/less)

## Verification Requirements

All information must be verified against source code in `/workspace/src/main/com/fulcrologic/rad/`:

1. **Code Examples**: Every code example must match actual implementation
2. **Macro Signatures**: Verify `defattr`, `defsc-form`, `defsc-report` signatures
3. **Options**: Cross-reference with `*-options` namespaces (e.g., `attributes-options.cljc`)
4. **Behavior**: Ensure described behavior matches code logic

### Key Files to Check:

- `attributes.cljc` - Core attribute definitions
- `attributes-options.cljc` - Attribute option definitions
- `form.cljc` - Form implementation
- `form-options.cljc` - Form options
- `report.cljc` - Report implementation
- `report-options.cljc` - Report options
  <ADD_TOPIC_SPECIFIC_FILES>

## Topic-Specific Focus

<TOPIC_SPECIFIC_INSTRUCTIONS>

Example for Attributes:

- Emphasize `defattr` macro usage
- Show identity vs non-identity attributes
- Demonstrate scalar vs ref types
- Explain ::attr/schema grouping
- Show cardinality specifications
- Include entity/cluster patterns

## Required Sections

Your output markdown must include:

### 1. Overview (2-4 sentences)

Brief explanation of what this aspect of RAD does and why it matters.

### 2. Core Concepts

Key ideas with minimal but sufficient explanation. Use subheadings.

### 3. Basic Examples

Simple, working code examples that demonstrate the fundamentals.

### 4. Common Patterns

Real-world usage patterns that developers will encounter frequently.

### 5. Important Details

Non-obvious behaviors, gotchas, or critical information.

### 6. Advanced Usage (if applicable)

More complex scenarios or patterns.

### 7. Related Topics

Links to other AI doc files and relevant source files.

### 8. Source References

List key source files with line numbers where relevant code exists.

## Style Guidelines

- **Be Concise**: Remove fluff, keep substance
- **Be Precise**: Technical accuracy over readability (but keep readable)
- **Show, Don't Tell**: Prefer code examples over prose explanations
- **Use Real Code**: No pseudocode or "..." placeholders
- **Note Locations**: Include file:line references for examples
- **Cross-Reference**: Link to related topics

## Validation Steps

Before finalizing:

1. [ ] Read through generated file - does it make sense?
2. [ ] Verify each code example against source
3. [ ] Check all namespace/function references exist
4. [ ] Confirm option keywords match actual definitions
5. [ ] Ensure no contradictions with source code
6. [ ] Test: Could an LLM implement features using only this doc?

## Output Format Example

```markdown
# <Topic Name>

## Overview

Brief explanation (2-4 sentences) of what this is and why it matters.

## Core Concept 1

Explanation with example:

\`\`\`clojure
;; From attributes.cljc:189-192
(defattr id :item/id :uuid
  {::attr/identity? true
   ::attr/schema :production})
\`\`\`

## Core Concept 2

...

## Common Patterns

### Pattern 1: ...

\`\`\`clojure
;; Example code
\`\`\`

## Important Notes

- Critical detail 1
- Gotcha with explanation

## Related Topics

- See [02-relationships-cardinality.md](02-relationships-cardinality.md)
- Source: `attributes.cljc:54-92`

## Source References

- **Primary**: `com.fulcrologic.rad.attributes` (attributes.cljc)
- **Options**: `com.fulcrologic.rad.attributes-options` (attributes-options.cljc)
- **Key functions**: `defattr` (54), `new-attribute` (29)
```

## Execution

Run this prompt:

```bash
claude --session-id ai-docs-<topic-slug> --print < topic-prompt.md
```

Or if already started:

```bash
claude --session-id ai-docs-<topic-slug> --resume
```

---

## Template Variables Summary

Fill in these before using:

- `<TOPIC_NAME>`: Human-readable topic name
- `<START_LINE>`: Starting line in DevelopersGuide.adoc
- `<END_LINE>`: Ending line in DevelopersGuide.adoc
- `<OUTPUT_FILENAME>`: Target markdown filename
- `<ADD_TOPIC_SPECIFIC_FILES>`: Additional source files to check
- `<TOPIC_SPECIFIC_INSTRUCTIONS>`: Detailed focus areas for this topic
- `<topic-slug>`: Lowercase-hyphenated session identifier
