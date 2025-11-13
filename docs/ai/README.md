# Fulcro RAD AI Documentation

This directory contains LLM-optimized documentation extracted from the main DevelopersGuide.adoc.

## Purpose

These files are designed to be:

- **Concise**: Dense with information but minimal verbosity
- **Accurate**: All code examples verified against source
- **Context-efficient**: Suitable for LLM context windows
- **Practical**: Focus on working examples and real patterns

## Organization

### Planning Files

- `ai-docs-plan.md` - Master plan and progress tracking
- `topic-extraction-template.md` - Reusable template for creating prompts
- `run-extraction.sh` - Helper script for running extractions

### Prompt Files

Located in `prompts/` directory:

- `01-attributes-prompt.md` - Attributes and Data Model extraction prompt
- *(More prompts to be created as needed)*

### Generated Documentation

The numbered markdown files in this directory:

- `01-attributes-data-model.md` - Core attribute system
- `02-relationships-cardinality.md` - Relationships between entities
- `03-attribute-options.md` - Complete attribute options reference
- `04-forms-basics.md` - Form fundamentals
- *(etc.)*

## Quick Start

### 1. Generate a Topic

Run the extraction script:

```bash
./docs/ai/run-extraction.sh 01
```

This will:

- Start a fresh Claude session
- Use the prompt from `prompts/01-attributes-prompt.md`
- Generate `01-attributes-data-model.md`

### 2. Resume if Needed

If the session times out or you need to continue:

```bash
./docs/ai/run-extraction.sh 01 --resume
```

### 3. Verify Output

```bash
# Check the file was created
ls -lh docs/ai/01-*.md

# Review content
less docs/ai/01-attributes-data-model.md

# Verify against source
grep -n "defattr" src/main/com/fulcrologic/rad/attributes.cljc
```

## Creating New Topic Prompts

1. Copy `topic-extraction-template.md`
2. Fill in the placeholders:
    - Topic name and description
    - Source line ranges from DevelopersGuide.adoc
    - Output filename
    - Topic-specific focus areas
    - Files to verify against
3. Save as `prompts/NN-topic-name-prompt.md`
4. Run with `./run-extraction.sh NN`

## Recommended Generation Order

**Phase 1: Foundation** (Do these first)

1. Attributes and Data Model (01)
2. Relationships and Cardinality (02)
3. Attribute Options (03)

**Phase 2: Core Features**

4. Forms Basics (04)
5. Form Relationships (05)
6. Reports Basics (08)
7. Server Setup (10)
8. Database Adapters (11)

**Phase 3: Advanced**

9. Form Validation (06)
10. Dynamic Forms (07)
11. Report Rendering (09)
12. Client Setup (12)

**Phase 4: Specialized**

13. Custom Rendering (13)
14. File Upload (14)
15. Type Support (15)

## Quality Standards

Each generated file should:

- [ ] Be 200-500 lines (some topics may need more)
- [ ] Have all code examples verified against source
- [ ] Include file:line references for examples
- [ ] Cross-reference related topics
- [ ] Be usable by an LLM to implement features
- [ ] Contain no pseudocode or placeholder examples

## Using These Docs with LLMs

### As Context for RAD Development

```
I'm building a Fulcro RAD application. Here's the documentation on attributes:

[paste 01-attributes-data-model.md]

Now help me create an Account entity with email, name, and addresses...
```

### For Code Review

```
Review this RAD attribute definition against the docs:

[paste code]

Reference:
[paste relevant section of docs]
```

### For Learning

```
I'm new to Fulcro RAD. Explain how to set up a to-many relationship
based on this documentation:

[paste 02-relationships-cardinality.md]
```

## Maintenance

### Updating Docs

When RAD source changes:

1. Update the relevant prompt file with new line numbers
2. Note what changed in the prompt's "Topic-Specific Focus"
3. Re-run the extraction
4. Compare with previous version
5. Merge or replace as appropriate

### Tracking Progress

Update `ai-docs-plan.md` progress section after each completion:

```markdown
- [x] 01-attributes-data-model.md - Completed 2025-11-13
- [ ] 02-relationships-cardinality.md
...
```

## Tips

1. **Verify Everything**: Don't trust the guide alone - check source code
2. **Keep It Dense**: Remove fluff but keep all essential information
3. **Use Real Examples**: Actual working code, not simplified pseudo-examples
4. **Cross-Reference**: Link related topics liberally
5. **Note Gotchas**: Document non-obvious behaviors explicitly
6. **Test with LLMs**: Can an LLM correctly implement features using just this doc?

## Troubleshooting

### Claude session not found

```bash
# List active sessions
claude --list-sessions

# Clear and restart
claude --session-id ai-docs-topic --clear
./run-extraction.sh 01
```

### Output file not created

- Check Claude didn't encounter errors in the session
- Verify the prompt file is well-formed
- Resume and ask Claude to create the file

### Code examples don't match source

- Re-run with explicit instruction to verify
- Check if source has been updated since guide was written
- Note discrepancies in the generated doc

## Files in This Directory

```
docs/ai/
├── README.md                          # This file
├── ai-docs-plan.md                    # Master plan and tracking
├── topic-extraction-template.md       # Template for new prompts
├── run-extraction.sh                  # Execution helper script
├── prompts/                           # Topic extraction prompts
│   ├── 01-attributes-prompt.md
│   └── ...
└── NN-topic-name.md                   # Generated docs (numbered)
```

## Questions?

See `ai-docs-plan.md` for:

- Complete topic list
- Detailed requirements
- Quality checklist
- Execution strategy

---

**Created**: 2025-11-13
**Status**: Ready for extraction - start with `./run-extraction.sh 01`
