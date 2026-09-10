#!/usr/bin/env bash
#
# Beast Mode v3 - Utility Functions
#
# Provides helper functions for state management, git operations, and CI integration

set -euo pipefail

# Use absolute path to avoid any directory confusion
PROJECT_ROOT="/data/data/com.termux/files/home/mine-host-import.lY0iUy/Java-integration-3-main"
STATE_FILE="${PROJECT_ROOT}/.claude/beastmode_state.json"

# Function to read state
read_state() {
    if [ -f "$STATE_FILE" ]; then
        cat "$STATE_FILE"
    else
        # Return default state if file doesn't exist
        echo '{"beastModeEnabled":false,"currentTask":"","objectives":[],"currentObjectiveId":null,"gitBranch":"","lastCiRunUrl":"","knownIssues":[],"blockers":[],"timestamp":""}'
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

# Function to update state with new values
update_state() {
    local updates="$1"  # JSON string of updates
    local current_state
    current_state=$(read_state)

    # Merge updates into current state
    local new_state
    new_state=$(echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
updates = json.load(sys.stdin, '$updates') if '$updates' != '' else {}
data.update(updates)
print(json.dumps(data))
")
    write_state "$new_state"
}

# Function to get current branch
get_current_branch() {
    cd "$PROJECT_ROOT"
    git symbolic-ref --quiet --short HEAD 2>/dev/null || git rev-parse --short HEAD
}

# Function to create feature branch
create_feature_branch() {
    local branch_name="$1"
    cd "$PROJECT_ROOT"
    git fetch origin main
    git checkout -b "$branch_name" origin/main
}

# Function to commit changes
commit_changes() {
    local message="$1"
    cd "$PROJECT_ROOT"
    git add -u
    git add . 2>/dev/null || true  # Add new files
    git commit -m "$message"
}

# Function to push using gated mechanism
push_changes() {
    cd "$PROJECT_ROOT"
    ./tools/push-gated.sh
}

# Function to get latest commit SHA
get_latest_commit_sha() {
    cd "$PROJECT_ROOT"
    git rev-parse HEAD
}

# Function to monitor CI for a specific commit
monitor_ci() {
    local commit_sha="$1"
    cd "$PROJECT_ROOT"
    ./tools/ci-watch.sh --sha "$commit_sha" --update-state
}

# Function to get CI logs for a failed run
get_ci_logs() {
    local run_id="$1"
    cd "$PROJECT_ROOT"
    gh run view "$run_id" --repo "$(git remote get-url origin | sed 's/.*github.com[:\/]\(.*\)\.git/\1/')" --log-failed 2>/dev/null || echo "Failed to get logs for run $run_id"
}

# Function to extract failure information from CI logs
extract_failure_info() {
    local logs="$1"
    echo "$logs" | grep -A 10 -B 5 "FAILED\|Error\|error\|Exception\|exception" | head -30
}

# Function to check if local verification is available
is_local_verification_available() {
    # Check if we have JDK/Gradle/ADB available
    if command -v java >/dev/null 2>&1 && command -v gradle >/dev/null 2>&1; then
        return 0  # true
    else
        return 1  # false
    fi
}

# Function to run local verification
run_local_verification() {
    # This would run local Gradle checks if available
    echo "Local verification not implemented in this version"
    return 1
}

# Function to mark objective as verified
mark_objective_verified() {
    local objective_id="$1"
    local current_state
    current_state=$(read_state)

    local new_state
    new_state=$(echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['id'] == '$objective_id':
        obj['status'] = 'VERIFIED'
        break
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
    write_state "$new_state"
}

# Function to increment objective attempts
increment_objective_attempts() {
    local objective_id="$1"
    local current_state
    current_state=$(read_state)

    local new_state
    new_state=$(echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['id'] == '$objective_id':
        obj['attempts'] = (obj['attempts'] or 0) + 1
        break
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
    write_state "$new_state"
}

# Function to get current objective
get_current_objective() {
    local current_state
    current_state=$(read_state)
    local current_obj_id
    current_obj_id=$(echo "$current_state" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data.get('currentObjectiveId') or '')" 2>/dev/null || echo "")

    if [ -n "$current_obj_id" ]; then
        echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['id'] == '$current_obj_id':
        print(json.dumps(obj))
        break
"
    else
        echo "{}"
    fi
}

# Function to set current objective
set_current_objective() {
    local objective_id="$1"
    local current_state
    current_state=$(read_state)

    local new_state
    new_state=$(echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
data['currentObjectiveId'] = '$objective_id'
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
    write_state "$new_state"
}

# Function to add an objective
add_objective() {
    local description="$1"
    local objective_id
    objective_id=$(date +%s%N | cut -b1-13)  # Simple ID generation

    local current_state
    current_state=$(read_state)

    local new_state
    new_state=$(echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
new_obj = {
    'id': '$objective_id',
    'description': '$description',
    'status': 'PENDING',
    'attempts': 0,
    'lastCommit': '',
    'ciStatus': '',
    'verificationNotes': ''
}
data['objectives'].append(new_obj)
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
    write_state "$new_state"
    echo "$objective_id"
}

# Function to get next pending objective
get_next_pending_objective() {
    local current_state
    current_state=$(read_state)

    echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['status'] == 'PENDING':
        print(json.dumps(obj))
        break
"
}

# Function to check if all objectives are verified
# Outputs "true" or "false" to stdout, always returns 0
all_objectives_verified() {
    local current_state
    current_state=$(read_state)

    echo "$current_state" | python3 -c "
import sys, json
data = json.load(sys.stdin)
all_verified = all(obj['status'] == 'VERIFIED' for obj in data['objectives'])
print(str(all_verified).lower())
"
}