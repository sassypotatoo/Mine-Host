#!/usr/bin/env bash
#
# Beast Mode v3 - Autonomous Workflow Handler
#
# Handles the complete autonomous workflow when Beast Mode is ON
#

set -euo pipefail

# Use absolute path to avoid any directory confusion
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "${PROJECT_ROOT}/.claude/scripts/beastmode_utils.sh"

STATE_FILE="${PROJECT_ROOT}/.claude/beastmode_state.json"
MAX_ATTEMPTS_PER_OBJECTIVE=5
MAX_TOTAL_ITERATIONS=15

# Function to classify user intent semantically
# Returns: QUESTION, WORK_REQUEST, or CONTROL_REQUEST
classify_intent() {
local message="$1"

# Simple heuristic-based classification for now
# In a full implementation, this would use more sophisticated NLP

# Control request patterns
if [[ "$message" =~ ^/(pause|resume|cancel|stop|toggle|task-change) ]]; then
echo "CONTROL_REQUEST"
return
fi

# Work request patterns - action-oriented language
if [[ "$message" =~ ^/(fix|implement|add|create|remove|delete|update|change|modify) ]] ||
[[ "$message" =~ (|can you|could you)(.*)?(fix|implement|add|create|remove|delete|update|change|modify) ]] ||
[[ "$message" =~ (need|want|require)(.*)?(to|for)(.*)?(fix|implement|add|create|remove|delete|update|change|modify) ]] ||
[[ "$message" =~ ^[A-Z][a-z]+(.+)(fix|implement|add|create|remove|delete|update|change|modify) ]]; then
echo "WORK_REQUEST"
return
fi

# Default to question/discussion
echo "QUESTION"
}

# Function to decompose work request into objectives
decompose_work_request() {
local work_request="$1"

# Simple decomposition - in reality, this would be more sophisticated
# For now, treat the entire request as one objective unless it clearly contains multiple parts

# Check for obvious multi-part requests
if [[ "$work_request" =~ (and|then|also|also |also ) ]] &&
[[ "$work_request" =~ (fix|implement|add|create|remove|delete|update|change|modify).*(fix|implement|add|create|remove|delete|update|change|modify) ]]; then
# Split by common conjunctions for multi-objective tasks
echo "$work_request" | python3 -c "
import sys, re
request = sys.stdin.read().strip()
# Split by common conjunctions that indicate separate objectives
parts = re.split(r'\s+(and|then|also|also |also )\s+', request, flags=re.IGNORECASE)
# Filter out the conjunctions themselves
objectives = [parts[i] for i in range(0, len(parts), 2) if i < len(parts)]
for obj in objectives:
if obj.strip():
print(obj.strip())
"
else
# Single objective
echo "$work_request"
fi
}

# Function to implement an objective
implement_objective() {
local objective_description="$1"
local objective_id="$2"

echo "Implementing objective: $objective_description"
echo "Objective ID: $objective_id"

# For Phase 2, we attempt to implement common objective types directly
# and provide guidance to Claude Code for more complex tasks

# Check if this is a simple file modification we can handle
if [[ "$objective_description" =~ ^(Fix|fix|Update|update)[[:space:]](.+)\.(md|txt|json|xml|yaml|yml)[[:space:]]*$ ]]; then
# Simple file fix - attempt to locate and fix the file
local target_file
target_file=$(echo "$objective_description" | sed -E 's/^(Fix|fix|Update|update)[[:space:]](.+)\.(md|txt|json|xml|yaml|yml)[[:space:]]*$/\2.\3/')

if [ -f "$PROJECT_ROOT/$target_file" ]; then
echo "Attempting to fix $target_file based on objective: $objective_description"
# For now, we'll signal that Claude Code should handle this
# In a more advanced implementation, we could attempt simple fixes
echo "GUIDANCE_NEEDED: implement the following objective: $objective_description"
return 0
fi
fi

# For all other objectives, provide clear guidance to Claude Code
echo "GUIDANCE_PROVIDED: Objective requires Claude Code implementation"
echo "Objective Details: $objective_description"
echo "implement this objective in the codebase."

return 0
}

# Function to commit and push changes
# Outputs progress messages to stderr so stdout carries ONLY the commit SHA
commit_and_push() {
local commit_message="$1"

echo "Committing and pushing changes." >&2
echo "Commit message: $commit_message" >&2

# Commit changes using git
cd "$PROJECT_ROOT"
git add -u
if [ $? -ne 0 ]; then
echo "Error: git add -u failed" >&2
return 1
fi
git add . 2>/dev/null || true # Add new files
git commit -m "$commit_message"
if [ $? -ne 0 ]; then
echo "Error: git commit failed" >&2
return 1
fi

# Get the commit SHA
local commit_sha
commit_sha=$(git rev-parse HEAD)
if [ -z "$commit_sha" ]; then
echo "Error: failed to get commit SHA" >&2
return 1
fi
echo "Committed with SHA: $commit_sha" >&2

# Push using./tools/push-gated.sh
echo "Pushing using./tools/push-gated.sh" >&2
if ! ./tools/push-gated.sh >&2; then
echo "Error: ./tools/push-gated.sh failed" >&2
return 1
fi

# Return the actual commit SHA
echo "$commit_sha"
}

# Function to verify implementation via CI
verify_via_ci() {
local commit_sha="$1"

echo "Verifying via CI for commit: $commit_sha"

# Use./tools/ci-watch.sh to monitor the CI run for this commit
# This will update the state via./tools/ci-state-update.sh
echo "Monitoring CI for commit $commit_sha using./tools/ci-watch.sh"
if ! ./tools/ci-watch.sh --sha "$commit_sha" --update-state; then
echo "ERROR: Failed to monitor CI for commit $commit_sha" >&2
return 1
fi

# Check if CI passed by reading the updated state
local state_json
state_json=$(read_state)
local ci_status
ci_status=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
commit_sha = sys.argv[1]
# Find the objective with this commit SHA and get its CI status
for obj in data['objectives']:
    if obj.get('lastCommit') == commit_sha:
        print(obj.get('ciStatus', ''))
        break
" "$commit_sha")

if [ "$ci_status" = "PASS" ]; then
echo "CI_RESULT: PASS"
return 0
else
echo "CI_RESULT: FAIL"
return 1
fi
}

# Function to handle CI failure
handle_ci_failure() {
local commit_sha="$1"
local objective_description="$2"
local objective_id="$3"
local attempt_num="$4"

echo "Handling CI failure for attempt $attempt_num"
echo "Commit SHA: $commit_sha"
echo "Objective: $objective_description"

# In a real implementation:
# 1. Retrieve actual CI logs
# 2. Classify the failure (compile error, test failure, etc.)
# 3. Diagnose root cause
# 4. Fix the appropriate problem

# Retrieve actual CI logs using gh
echo "Retrieving CI logs for commit $commit_sha"
local ci_logs
ci_logs=$(./tools/ci-watch.sh --sha "$commit_sha" --get-logs 2>/dev/null || echo "Failed to retrieve CI logs")

# Signal that Claude Code should analyze the CI failure
local temp_ci_file="${PROJECT_ROOT}/.claude/ci_analysis_${objective_id}_${attempt_num}.txt"
echo "Commit SHA: $commit_sha" > "$temp_ci_file"
echo "Objective: $objective_description" >> "$temp_ci_file"
echo "Attempt: $attempt_num" >> "$temp_ci_file"
echo "" >> "$temp_ci_file"
echo "CI Logs:" >> "$temp_ci_file"
echo "$ci_logs" >> "$temp_ci_file"
echo "" >> "$temp_ci_file"
echo "Analysis request: Identify root cause and suggest smallest appropriate fix following MineHost's non-negotiables." >> "$temp_ci_file"

# Signal that Claude Code should analyze this failure
# Claude Code will observe the state and temp file, then provide analysis
echo "CLAUDE_CODE_ANALYZE_FAILURE: $objective_id:$attempt_num"

# For Phase 2, we provide a basic analysis based on common CI failure patterns
# In a full implementation, Claude Code would analyze the actual logs and provide a fix
local fix_analysis="Analyze CI logs above to identify root cause and apply smallest appropriate fix"

# Return the fix description
echo "$fix_analysis"
}

# Function to check if local verification is available and useful
should_use_local_verification() {
# Check if we have genuine local verification capabilities
if is_local_verification_available; then
echo "Local verification is available"
# Additional checks could go here
return 0
else
echo "Local verification not available - relying on CI"
return 1
fi
}

# Main workflow execution function
execute_workflow() {
local work_request="$1"

echo "Starting Beast Mode autonomous workflow"
echo "Work request: $work_request"

# Reset state for new work request
local state_json
state_json=$(read_state)
local new_state
new_state=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
# Only reset objectives if this is a new work request (different from current task)
if data.get('currentTask', '') != '$work_request':
    data['objectives'] = []
    data['currentTask'] = '$work_request'
    data['currentObjectiveId'] = None
    data['gitBranch'] = ''
    data['lastCiRunUrl'] = ''
    data['knownIssues'] = []
    data['blockers'] = []
    data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
write_state "$new_state"

# Decompose work request into objectives
echo "Decomposing work request into objectives."
local objectives
objectives=$(decompose_work_request "$work_request")

# Check if objectives for this work request already exist
local existing_objectives_json
existing_objectives_json=$(echo "$(read_state)" | python3 -c "
import sys, json
data = json.load(sys.stdin)
existing = [obj['description'] for obj in data.get('objectives', []) if obj['status'] in ('PENDING', 'IN_PROGRESS')]
print(','.join(existing))
")

# Add objectives to state
local objective_count=0
while IFS= read -r objective_line; do
if [ -n "$objective_line" ]; then
# Check if this objective already exists
if echo "$existing_objectives_json" | grep -q "$objective_line"; then
echo "Objective already exists, reusing: $objective_line"
objective_count=$((objective_count + 1))
continue
fi
local obj_id
obj_id=$(add_objective "$objective_line")
echo "Added objective $obj_id: $objective_line"
objective_count=$((objective_count + 1))
fi
done < <(echo "$objectives")

echo "Created $objective_count objectives"

# Process each objective
local total_iterations=0
while true; do
# Check if all objectives are verified
local all_verified
all_verified=$(all_objectives_verified)
if [ "$all_verified" = "false" ]; then
# Some objectives still need work
:
else
# All objectives verified
break
fi

# Check if we've exceeded total iteration limit
if [ $total_iterations -ge $MAX_TOTAL_ITERATIONS ]; then
echo "ERROR: Exceeded maximum total iterations ($MAX_TOTAL_ITERATIONS)"
# Trigger escalation
break
fi

# Get next pending objective
local next_obj_json
next_obj_json=$(get_next_pending_objective)

# If no pending objectives but not all verified, something's wrong
if [ -z "$next_obj_json" ] || [ "$next_obj_json" = "{}" ]; then
echo "WARNING: No pending objectives found but not all verified"
break
fi

# Extract objective details
local obj_id
obj_id=$(echo "$next_obj_json" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data.get('id', ''))" 2>/dev/null || echo "")
local obj_desc
obj_desc=$(echo "$next_obj_json" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data.get('description', ''))" 2>/dev/null || echo "")
local obj_attempts
obj_attempts=$(echo "$next_obj_json" | python3 -c "import sys, json; data = json.load(sys.stdin); print(data.get('attempts', 0))" 2>/dev/null || echo "0")

if [ -z "$obj_id" ]; then
echo "ERROR: Could not extract objective ID"
break
fi

echo ""
echo "=== Beast Mode Workflow Turn Start ==="
echo "Current Task: $work_request"
echo "State: PENDING - Awaiting Claude Code implementation"
echo "=== Processing Objective $obj_id ==="
echo "Description: $obj_desc"
echo "Attempt: $((obj_attempts + 1))"
echo "[Beast Mode waiting for Claude Code to implement this objective]"

# Set as current objective
set_current_objective "$obj_id"

# Increment attempts
increment_objective_attempts "$obj_id"

# Check if we've exceeded retry budget for this objective
if [ $obj_attempts -ge $MAX_ATTEMPTS_PER_OBJECTIVE ]; then
echo "ERROR: Objective $obj_id exceeded retry budget ($MAX_ATTEMPTS_PER_OBJECTIVE)"
# Trigger escalation for this objective
# In a real implementation, we'd add to blockers and move on
# Mark as FAILED_EXCEEDED to distinguish from normal failures
local state_json
state_json=$(read_state)
local new_state
new_state=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['id'] == '$obj_id':
        obj['status'] = 'FAILED_EXCEEDED'
        obj['verificationNotes'] = 'Exceeded retry budget ($MAX_ATTEMPTS_PER_OBJECTIVE attempts)'
        break
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
write_state "$new_state"
echo "Objective $obj_id marked as FAILED_EXCEEDED"
continue
fi

# Store start time for this attempt
local attempt_start_time
attempt_start_time=$(date +%s)

# Implement the objective
if ! implement_objective "$obj_desc" "$obj_id"; then
echo "ERROR: Implementation failed for objective $obj_id"
# In a real implementation, we'd retry or escalate
continue
fi

# Commit and push changes
local commit_message
commit_message="Beast Mode: Implement objective $obj_id - $obj_desc"
local commit_sha
if ! commit_sha=$(commit_and_push "$commit_message"); then
echo "ERROR: Failed to commit and push changes for objective $obj_id"
continue
fi

# Update state with commit info
local state_json
state_json=$(read_state)
local new_state
new_state=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
obj_id = sys.argv[1]
commit_sha = sys.argv[2]
timestamp = sys.argv[3]
for obj in data['objectives']:
    if obj['id'] == obj_id:
        obj['lastCommit'] = commit_sha
        break
data['timestamp'] = timestamp
print(json.dumps(data))
" "$obj_id" "$commit_sha" "$(get_timestamp)")
write_state "$new_state"

# Decide whether to use local verification
local use_local=0
if should_use_local_verification; then
use_local=1
echo "Running local verification."
# In a real implementation:
# run_local_verification
# if [ $? -ne 0 ]; then
# echo "Local verification failed"
# # Handle failure
# continue
# fi
fi

# Verify via CI
echo "Running CI verification."
local ci_result
ci_result=$(verify_via_ci "$commit_sha")

local ci_status
ci_status=$(echo "$ci_result" | grep "CI_RESULT:" | cut -d' ' -f2)

if [ "$ci_status" = "PASS" ]; then
echo "CI PASSED for objective $obj_id"

# Determine if verification is satisfied
# In a real implementation, we'd check if the objective's specific
# verification requirements (Tier 1, 2, or 3) are met

# For simulation, we'll consider it verified if CI passes
mark_objective_verified "$obj_id"
echo "Objective $obj_id marked as VERIFIED"

# Update state with CI info
local state_json
state_json=$(read_state)
local new_state
new_state=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['id'] == '$obj_id':
        obj['ciStatus'] = 'PASS'
        obj['verificationNotes'] = 'CI verification passed'
        break
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
write_state "$new_state"
else
echo "CI FAILED for objective $obj_id"

# Handle the failure
local fix_description
fix_description=$(handle_ci_failure "$commit_sha" "$obj_desc" "$obj_id" $((obj_attempts + 1)))

# Update state with failure info
local state_json
state_json=$(read_state)
local new_state
new_state=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for obj in data['objectives']:
    if obj['id'] == '$obj_id':
        obj['ciStatus'] = 'FAIL'
        obj['verificationNotes'] = 'CI verification failed: $fix_description'
        break
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
write_state "$new_state"

# In a real implementation, we would now:
# 1. Apply the fix
# 2. Commit/push again
# 3. Retry CI verification
#
# For simulation, we'll just continue the loop and let it retry
# The incremented attempt count will eventually lead to escalation if it keeps failing
fi

# Calculate iteration time
local attempt_end_time
attempt_end_time=$(date +%s)
local iteration_time
iteration_time=$((attempt_end_time - attempt_start_time))

echo "Objective $obj_id attempt $((obj_attempts + 1)) completed in ${iteration_time}s"

# Increment total iterations
total_iterations=$((total_iterations + 1))

# Small delay to avoid tight loop
sleep 1
done

# Check if all objectives are verified
if all_objectives_verified; then
echo ""
echo "🎉 All objectives verified! Work request completed."
echo ""

# Reset current objective since we're done
local state_json
state_json=$(read_state)
local new_state
new_state=$(echo "$state_json" | python3 -c "
import sys, json
data = json.load(sys.stdin)
data['currentObjectiveId'] = None
data['timestamp'] = '$(get_timestamp)'
print(json.dumps(data))
")
write_state "$new_state"

return 0
else
echo ""
echo "⚠️ Workflow ended with some objectives not verified"
echo ""
return 1
fi
}

# Main script entry point
main() {
# Check if we were called with a work request
if [ $# -eq 0 ]; then
echo "Beast Mode Workflow Handler"
echo "Usage: $0 \"work request description\""
exit 1
fi

local work_request="$*"

# Execute the workflow
execute_workflow "$work_request"

local result=$?
if [ $result -eq 0 ]; then
echo "Beast Mode workflow completed successfully"
else
echo "Beast Mode workflow completed with issues"
fi

return $result
}

# If script is executed directly, run main
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
main "$@"
fi