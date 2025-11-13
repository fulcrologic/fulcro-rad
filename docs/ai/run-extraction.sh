#!/bin/bash
# RAD AI Documentation Extraction Runner
# Usage: ./run-extraction.sh <topic-number> [--resume]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROMPTS_DIR="$SCRIPT_DIR/prompts"

if [ -z "$1" ]; then
    echo "Usage: $0 <topic-number> [--resume]"
    echo ""
    echo "Available topics:"
    echo "  01 - Attributes and Data Model"
    echo "  02 - Relationships and Cardinality"
    echo "  03 - Attribute Options Reference"
    echo "  04 - Forms Basics"
    echo "  05 - Form Relationships"
    echo "  06 - Form Validation"
    echo "  07 - Dynamic Forms"
    echo "  08 - Reports Basics"
    echo "  09 - Report Rendering"
    echo "  10 - Server Setup"
    echo "  11 - Database Adapters"
    echo "  12 - Client Setup"
    echo "  13 - Custom Rendering"
    echo "  14 - File Upload/Download"
    echo "  15 - Type Support"
    echo ""
    echo "Examples:"
    echo "  $0 01              # Start attributes extraction"
    echo "  $0 01 --resume     # Resume attributes extraction"
    exit 1
fi

TOPIC_NUM="$1"
RESUME="$2"

# Map topic numbers to session IDs
case "$TOPIC_NUM" in
    01) SESSION_ID="ai-docs-attributes" ;;
    02) SESSION_ID="ai-docs-relationships" ;;
    03) SESSION_ID="ai-docs-attr-options" ;;
    04) SESSION_ID="ai-docs-forms-basic" ;;
    05) SESSION_ID="ai-docs-form-relationships" ;;
    06) SESSION_ID="ai-docs-form-validation" ;;
    07) SESSION_ID="ai-docs-dynamic-forms" ;;
    08) SESSION_ID="ai-docs-reports-basic" ;;
    09) SESSION_ID="ai-docs-report-rendering" ;;
    10) SESSION_ID="ai-docs-server-setup" ;;
    11) SESSION_ID="ai-docs-db-adapters" ;;
    12) SESSION_ID="ai-docs-client-setup" ;;
    13) SESSION_ID="ai-docs-custom-rendering" ;;
    14) SESSION_ID="ai-docs-file-upload" ;;
    15) SESSION_ID="ai-docs-type-support" ;;
    *)
        echo "Error: Invalid topic number '$TOPIC_NUM'"
        echo "Valid range: 01-15"
        exit 1
        ;;
esac

PROMPT_FILE="$PROMPTS_DIR/${TOPIC_NUM}-*-prompt.md"

if [ "$RESUME" = "--resume" ]; then
    echo "Resuming session: $SESSION_ID"
    claude --session-id "$SESSION_ID" --resume
else
    # Check if prompt file exists
    PROMPT_FILES=($PROMPT_FILE)
    if [ ! -f "${PROMPT_FILES[0]}" ]; then
        echo "Error: Prompt file not found: $PROMPT_FILE"
        echo "Available prompts:"
        ls -1 "$PROMPTS_DIR"/*.md 2>/dev/null || echo "  (none created yet)"
        echo ""
        echo "Create a prompt file first using the template in topic-extraction-template.md"
        exit 1
    fi

    echo "Starting extraction for topic $TOPIC_NUM"
    echo "Session ID: $SESSION_ID"
    echo "Prompt file: ${PROMPT_FILES[0]}"
    echo ""
    echo "Running Claude..."
    echo ""

    claude --session-id "$SESSION_ID" --print < "${PROMPT_FILES[0]}"
fi

echo ""
echo "----------------------------------------"
echo "Extraction complete!"
echo ""
echo "To resume this session:"
echo "  $0 $TOPIC_NUM --resume"
echo ""
echo "Check output:"
echo "  cat docs/ai/${TOPIC_NUM}-*.md"
