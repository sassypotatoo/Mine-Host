#!/bin/bash
#
# ci-state-update.sh — append-only writer for AUTONOMOUS_STATE.md
# that takes a CI run id, conclusion, URL, and a free-form note and adds a
# History row without manual editing.
#
# Usage: ci-state-update.sh --run-id <id> --conclusion <conclusion> --sha <sha> --branch <branch> --url <url> --note <note>
#

set -euo pipefail

# Default values
RUN_ID=""
CONCLUSION=""
SHA=""
BRANCH=""
URL=""
NOTE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --run-id) RUN_ID="$2"; shift 2 ;;
    --conclusion) CONCLUSION="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --url) URL="$2"; shift 2 ;;
    --note) NOTE="$2"; shift 2 ;;
    *) echo "[ci-state-update] unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Validate required arguments
if [ -z "$RUN_ID" ] || [ -z "$CONCLUSION" ] || [ -z "$URL" ]; then
  echo "[ci-state-update] error: run-id, conclusion, and url are required" >&2
  exit 1
fi

# Get current timestamp in ISO 8601 UTC
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Construct the history line
HISTORY_LINE="- $TIMESTAMP — ci: run=$RUN_ID conclusion=$CONCLUSION url=$URL"

# Path to the state file
STATE_FILE="docs/autonomous/AUTONOMOUS_STATE.md"

# Append the history line to the History section
# Since the History section is the last section, we can simply append to the file.
echo "$HISTORY_LINE" >> "$STATE_FILE"