#!/usr/bin/env bash
#
# Beast Mode v3.3 - Utility Functions
#
# State management for the turn-based autonomous workflow. All shell-to-Python
# value passing goes through sys.argv with quoted heredocs — never through
# string interpolation into Python literals (injection-safe).
#
# Sourced by beastmode_workflow.sh. Redirect the state file for tests:
#   BEASTMODE_STATE_FILE=/tmp/test-state.json bash .claude/scripts/beastmode_workflow.sh --status

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_FILE="${BEASTMODE_STATE_FILE:-${PROJECT_ROOT}/.claude/beastmode_state.json}"

DEFAULT_STATE_JSON='{"beastModeEnabled":false,"currentTask":"","objectives":[],"currentObjectiveId":null,"gitBranch":"","lastCiRunUrl":"","knownIssues":[],"blockers":[],"timestamp":"","workflowStatus":"IDLE","phase":"IDLE"}'

read_state() {
    if [ -f "$STATE_FILE" ]; then
        content=$(cat "$STATE_FILE")
        # Trim leading and trailing whitespace
        content=$(echo "$content" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
        if [ -n "$content" ]; then
            echo "$content"
            return
        fi
    fi
    echo "$DEFAULT_STATE_JSON"
}

get_timestamp() {
    date -u +"%Y-%m-%dT%H:%M:%SZ"
}

# Merge a JSON object of top-level updates into the state.
update_state() {
    local updates="$1"
    local tmp
    tmp="$(mktemp "${STATE_FILE}.XXXXXX")"
    read_state | python3 - "$updates" "$(get_timestamp)" > "$tmp" <<'PY'
import sys, json
data = json.load(sys.stdin)
data.update(json.loads(sys.argv[1]))
data["timestamp"] = sys.argv[2]
print(json.dumps(data, indent=2))
PY
    mv "$tmp" "$STATE_FILE"
}

# Print a top-level state field ("" if missing/null; bools as true/false; lists as JSON).
get_state_field() {
    local key="$1"
    read_state | python3 - "$key" <<'PY'
import sys, json
data = json.load(sys.stdin)
value = data.get(sys.argv[1])
if value is None:
    print("")
elif isinstance(value, bool):
    print("true" if value else "false")
elif isinstance(value, list):
    print(json.dumps(value))
else:
    print(value)
PY
}

# Print one field of one objective ("" if objective or field is missing).
get_objective_field() {
    local obj_id="$1" key="$2"
    read_state | python3 - "$obj_id" "$key" <<'PY'
import sys, json
data = json.load(sys.stdin)
obj_id, key = sys.argv[1], sys.argv[2]
for obj in data.get("objectives", []):
    if obj.get("id") == obj_id:
        value = obj.get(key)
        if value is None:
            print("")
        elif isinstance(value, bool):
            print("true" if value else "false")
        else:
            print(value)
        break
PY
}

# Merge a JSON object of updates into one objective. Fails (return 3) if the
# objective id is unknown.
set_objective_fields() {
    local obj_id="$1" updates="$2"
    local tmp
    tmp="$(mktemp "${STATE_FILE}.XXXXXX")"
    if ! read_state | python3 - "$obj_id" "$updates" "$(get_timestamp)" > "$tmp" <<'PY'
import sys, json
data = json.load(sys.stdin)
obj_id, updates, ts = sys.argv[1], json.loads(sys.argv[2]), sys.argv[3]
for obj in data.get("objectives", []):
    if obj.get("id") == obj_id:
        obj.update(updates)
        data["timestamp"] = ts
        print(json.dumps(data, indent=2))
        sys.exit(0)
sys.exit(3)
PY
    then
        rm -f "$tmp"
        return 3
    fi
    mv "$tmp" "$STATE_FILE"
}

# Append a PENDING objective; prints its id.
add_objective() {
    local description="$1"
    local obj_id
    obj_id="$(date +%s%N | cut -b1-13)"
    local tmp
    tmp="$(mktemp "${STATE_FILE}.XXXXXX")"
    read_state | python3 - "$obj_id" "$description" "$(get_timestamp)" > "$tmp" <<'PY'
import sys, json
data = json.load(sys.stdin)
obj_id, desc, ts = sys.argv[1], sys.argv[2], sys.argv[3]
data.setdefault("objectives", []).append({
    "id": obj_id,
    "description": desc,
    "status": "PENDING",
    "attempts": 0,
    "lastCommit": "",
    "ciStatus": "",
    "ciRunUrl": "",
    "verificationNotes": "",
    "failureNotes": "",
})
data["timestamp"] = ts
print(json.dumps(data, indent=2))
PY
    mv "$tmp" "$STATE_FILE"
    echo "$obj_id"
}

# Print the id of the first PENDING objective; exit 1 if none exists.
get_next_pending_objective_id() {
    read_state | python3 <<'PY'
import sys, json
data = json.load(sys.stdin)
for obj in data.get("objectives", []):
    if obj.get("status") == "PENDING":
        print(obj["id"])
        sys.exit(0)
sys.exit(1)
PY
}

count_pending_objectives() {
    read_state | python3 <<'PY'
import sys, json
data = json.load(sys.stdin)
print(sum(1 for o in data.get("objectives", []) if o.get("status") == "PENDING"))
PY
}

has_failed_objectives() {
    read_state | python3 <<'PY'
import sys, json
data = json.load(sys.stdin)
failed = any(o.get("status") == "FAILED_EXCEEDED" for o in data.get("objectives", []))
print("true" if failed else "false")
PY
}

# Write a JSON state object to the state file.
write_state() {
    local state_json="$1"
    echo "$state_json" > "$STATE_FILE"
}

# Write a JSON state object to the state file.
write_state() {
    local state_json="$1"
    echo "$state_json" > "$STATE_FILE"
}
