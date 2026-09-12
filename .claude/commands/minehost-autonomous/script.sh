#!/usr/bin/env bash
#
# Minehost-autonomous Command Script
# Toggles Beast Mode v3 workflow orchestration system

set -euo pipefail

# Use absolute path to avoid any directory confusion
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STATE_FILE="${PROJECT_ROOT}/.claude/beastmode_state.json"

# Function to read state
read_state() {
    if [ -f "$STATE_FILE" ]; then
        cat "$STATE_FILE"
    else
        # Return default state if file doesn't exist
        echo '{"beastModeEnabled":false,"currentTask":"","objectives":[],"currentObjectiveId":null,"gitBranch":"","lastCiRunUrl":"","knownIssues":[],"blockers":[],"timestamp":"","workflowStatus":"IDLE"}'
    fi
}

# Function to write state
write_state() {
    local state_json="$1"
    echo "$state_json" > "$STATE_FILE"
}

# Function to get current timestamp
get_timestamp() {
    date -u +"%Y-%m-%dT%H:%M:%SZ"
}

# Parse arguments
WORK_REQUEST="$*"

# Read current state
STATE_JSON=$(read_state)
BEAST_MODE_ENABLED=$(echo "$STATE_JSON" | python3 -c "import sys, json; print(str(json.load(sys.stdin)['beastModeEnabled']).lower())" 2>/dev/null || echo "false")

if [ "$BEAST_MODE_ENABLED" = "true" ]; then
    # Turning OFF
    NEW_STATE=$(echo "$STATE_JSON" | python3 -c "
import sys, json
data = json.load(sys.stdin)
data['beastModeEnabled'] = False
data['currentTask'] = ''
data['currentObjectiveId'] = None
data['workflowStatus'] = 'IDLE'
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
    write_state "$NEW_STATE"
    echo "🛑 BEAST MODE: OFF"
else
    # Turning ON
    NEW_STATE=$(echo "$STATE_JSON" | python3 -c "
import sys, json
data = json.load(sys.stdin)
data['beastModeEnabled'] = True
if '$WORK_REQUEST'.strip():
    data['currentTask'] = '$WORK_REQUEST'.strip().replace('\"', '\\\"')
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
    write_state "$NEW_STATE"
    echo "🔥 BEAST MODE: ON"
    echo "⚡ Effort: MAX"

    # If there's a work request, we would start working on it
    # In a full implementation, this would trigger the autonomous workflow
    # For now, we just set the state and wait for subsequent messages
    if [ -n "$WORK_REQUEST" ] && [ "$WORK_REQUEST" != " " ]; then
        echo "Work request received: $WORK_REQUEST"
        echo "Beast Mode is ON and ready to process the work request."
        echo "Send your next message to continue."
    fi
fi