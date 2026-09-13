#!/data/data/com.termux/files/usr/bin/bash
#
# Beast Mode v4 — State Management Tool
# Provides atomic read/write access to beastmode_state.json
# Used by Claude Code to track tasks, objectives, and CI results
#

set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$PROJECT_ROOT/.claude/beastmode_state.json"

# ──────────────────────────────────────────────────────────────────────────────
# Utility Functions
# ──────────────────────────────────────────────────────────────────────────────

die() {
    echo "[bm-state] ERROR: $*" >&2
    exit 1
}

read_state() {
    if [[ ! -f "$STATE_FILE" ]]; then
        die "State file not found: $STATE_FILE"
    fi
    cat "$STATE_FILE"
}

write_state() {
    local json="$1"
    local temp_file
    temp_file=$(mktemp "$STATE_FILE.XXXXXX.tmp")

    if ! echo "$json" | python3 -m json.tool > "$temp_file" 2>/dev/null; then
        rm -f "$temp_file"
        die "Invalid JSON"
    fi

    # Atomic replace
    mv "$temp_file" "$STATE_FILE"
}

# ──────────────────────────────────────────────────────────────────────────────
# Commands: Read
# ──────────────────────────────────────────────────────────────────────────────

cmd_get() {
    read_state
}

cmd_status() {
    local state
    state=$(read_state)

    local enabled
    enabled=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('beastModeEnabled', False))")

    local task
    task=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('currentTask', '(none)'))")

    local phase
    phase=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('phase', 'IDLE'))")

    local workflow_status
    workflow_status=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('workflowStatus', 'IDLE'))")

    local branch
    branch=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('taskBranch', ''))" || echo "(none)")
    [[ -z "$branch" ]] && branch="(none)"

    local last_sha
    last_sha=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('lastCommitSha', ''))" || echo "(none)")
    [[ -z "$last_sha" ]] && last_sha="(none)"

    local current_obj_id
    current_obj_id=$(echo "$state" | python3 -c "import sys, json; print(json.load(sys.stdin).get('currentObjectiveId', ''))" || echo "")

    # Header
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    if [[ "$enabled" == "True" ]]; then
        echo "🔥 BEAST MODE: ON  |  Phase: $phase  |  Status: $workflow_status"
    else
        echo "⚪ BEAST MODE: OFF  |  Phase: $phase  |  Status: $workflow_status"
    fi
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "Task       : $task"
    echo "Branch     : $branch"
    echo "Last SHA   : $last_sha"
    echo ""
    echo "Objectives:"

    local obj_count
    obj_count=$(echo "$state" | python3 -c "import sys, json; print(len(json.load(sys.stdin).get('objectives', [])))")

    if [[ "$obj_count" -eq 0 ]]; then
        echo "  (none)"
    else
        python3 << PYTHON
import sys, json
state = json.loads("""$state""")
current_id = state.get('currentObjectiveId')
for obj in state.get('objectives', []):
    obj_id = obj.get('id', '?')
    status = obj.get('status', '?')
    desc = obj.get('description', '?')
    attempts = obj.get('attempts', 0)

    marker = '→' if obj_id == current_id else ' '
    print(f"  {marker} [{status:12s}] {obj_id} — {desc}")
    print(f"      Attempts: {attempts}/5")
PYTHON
    fi

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
}

# ──────────────────────────────────────────────────────────────────────────────
# Commands: Task Lifecycle
# ──────────────────────────────────────────────────────────────────────────────

cmd_task_start() {
    local branch="$1"
    local task="$2"

    [[ -z "$branch" ]] && die "task-start requires branch argument"
    [[ -z "$task" ]] && die "task-start requires task description argument"

    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
state['beastModeEnabled'] = True
state['currentTask'] = """$task"""
state['taskBranch'] = """$branch"""
state['phase'] = 'IMPLEMENT'
state['workflowStatus'] = 'ACTIVE'
state['objectives'] = []
state['currentObjectiveId'] = None
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Task started: $task (branch: $branch)"
}

cmd_task_done() {
    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
state['workflowStatus'] = 'COMPLETE'
state['phase'] = 'IDLE'
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Task marked complete"
}

cmd_task_pause() {
    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
state['workflowStatus'] = 'PAUSED'
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Task paused"
}

cmd_task_resume() {
    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
state['workflowStatus'] = 'ACTIVE'
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Task resumed"
}

cmd_task_cancel() {
    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
state['beastModeEnabled'] = False
state['currentTask'] = ''
state['taskBranch'] = ''
state['objectives'] = []
state['currentObjectiveId'] = None
state['workflowStatus'] = 'IDLE'
state['phase'] = 'IDLE'
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Task cancelled"
}

# ──────────────────────────────────────────────────────────────────────────────
# Commands: Objective Lifecycle
# ──────────────────────────────────────────────────────────────────────────────

cmd_objective_add() {
    local description="$1"

    [[ -z "$description" ]] && die "objective-add requires description argument"

    local state
    state=$(read_state)

    local obj_id
    obj_id=$(date +%s%N)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
new_obj = {
    'id': """$obj_id""",
    'description': """$description""",
    'status': 'PENDING',
    'attempts': 0,
    'lastCommit': '',
    'ciStatus': '',
    'ciUrl': '',
    'failureReason': ''
}
state['objectives'].append(new_obj)
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "$obj_id"
}

cmd_objective_start() {
    local obj_id="$1"

    [[ -z "$obj_id" ]] && die "objective-start requires objective ID argument"

    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
state['currentObjectiveId'] = """$obj_id"""
for obj in state['objectives']:
    if obj['id'] == """$obj_id""":
        obj['status'] = 'IN_PROGRESS'
        obj['attempts'] = obj.get('attempts', 0) + 1
        break
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Objective $obj_id marked IN_PROGRESS"
}

cmd_objective_complete() {
    local obj_id="$1"
    local commit_sha="$2"
    local ci_url="$3"

    [[ -z "$obj_id" ]] && die "objective-complete requires objective ID"
    [[ -z "$commit_sha" ]] && die "objective-complete requires commit SHA"
    [[ -z "$ci_url" ]] && die "objective-complete requires CI URL"

    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
for obj in state['objectives']:
    if obj['id'] == """$obj_id""":
        obj['status'] = 'VERIFIED'
        obj['lastCommit'] = """$commit_sha"""
        obj['ciStatus'] = 'PASS'
        obj['ciUrl'] = """$ci_url"""
        break
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Objective $obj_id marked VERIFIED"
}

cmd_objective_fail() {
    local obj_id="$1"
    local reason="$2"

    [[ -z "$obj_id" ]] && die "objective-fail requires objective ID"
    [[ -z "$reason" ]] && die "objective-fail requires failure reason"

    local state
    state=$(read_state)

    # Check attempt count
    local attempts
    attempts=$(echo "$state" | python3 -c "import sys, json; objs = [obj for obj in json.load(sys.stdin)['objectives'] if obj['id'] == '$obj_id']; print(objs[0]['attempts'] if objs else 0)")

    if [[ "$attempts" -ge 5 ]]; then
        state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
for obj in state['objectives']:
    if obj['id'] == """$obj_id""":
        obj['status'] = 'FAILED_EXCEEDED'
        obj['failureReason'] = """$reason"""
        break
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)
        write_state "$state"
        echo "Objective $obj_id marked FAILED_EXCEEDED (5 attempts exceeded)"
    else
        state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
for obj in state['objectives']:
    if obj['id'] == """$obj_id""":
        obj['status'] = 'FAILED'
        obj['failureReason'] = """$reason"""
        break
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)
        write_state "$state"
        echo "Objective $obj_id marked FAILED (reason: $reason)"
    fi
}

cmd_objective_retry() {
    local obj_id="$1"

    [[ -z "$obj_id" ]] && die "objective-retry requires objective ID"

    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
for obj in state['objectives']:
    if obj['id'] == """$obj_id""":
        obj['status'] = 'PENDING'
        obj['failureReason'] = ''
        break
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Objective $obj_id reset to PENDING for retry"
}

# ──────────────────────────────────────────────────────────────────────────────
# Commands: CI Tracking
# ──────────────────────────────────────────────────────────────────────────────

cmd_ci_update() {
    local obj_id="$1"
    local result="$2"
    local commit_sha="$3"
    local ci_url="$4"

    [[ -z "$obj_id" ]] && die "ci-update requires objective ID"
    [[ -z "$result" ]] && die "ci-update requires result (PASS or FAIL)"
    [[ -z "$commit_sha" ]] && die "ci-update requires commit SHA"
    [[ -z "$ci_url" ]] && die "ci-update requires CI URL"

    local state
    state=$(read_state)

    state=$(python3 << PYTHON
import sys, json
from datetime import datetime
state = json.loads("""$state""")
for obj in state['objectives']:
    if obj['id'] == """$obj_id""":
        obj['lastCommit'] = """$commit_sha"""
        obj['ciStatus'] = """$result"""
        obj['ciUrl'] = """$ci_url"""
        break
state['timestamp'] = datetime.utcnow().isoformat() + 'Z'
print(json.dumps(state, indent=2))
PYTHON
)

    write_state "$state"
    echo "Objective $obj_id CI status updated: $result"
}

# ──────────────────────────────────────────────────────────────────────────────
# Main Entry Point
# ──────────────────────────────────────────────────────────────────────────────

main() {
    local command="$1"
    shift || true

    case "$command" in
        get)
            cmd_get "$@"
            ;;
        status)
            cmd_status "$@"
            ;;
        task-start)
            cmd_task_start "$@"
            ;;
        task-done)
            cmd_task_done "$@"
            ;;
        task-pause)
            cmd_task_pause "$@"
            ;;
        task-resume)
            cmd_task_resume "$@"
            ;;
        task-cancel)
            cmd_task_cancel "$@"
            ;;
        objective-add)
            cmd_objective_add "$@"
            ;;
        objective-start)
            cmd_objective_start "$@"
            ;;
        objective-complete)
            cmd_objective_complete "$@"
            ;;
        objective-fail)
            cmd_objective_fail "$@"
            ;;
        objective-retry)
            cmd_objective_retry "$@"
            ;;
        ci-update)
            cmd_ci_update "$@"
            ;;
        *)
            cat >&2 << 'EOF'
Beast Mode v4 State Management Tool

Usage: bm-state.sh <command> [args...]

Commands:
  get                                    Print raw JSON state
  status                                 Pretty-print state with objectives

  task-start "<branch>" "<description>"  Start new task
  task-done                              Mark task complete
  task-pause                             Pause workflow
  task-resume                            Resume workflow
  task-cancel                            Cancel task, reset to IDLE

  objective-add "<description>"          Create objective (prints ID)
  objective-start <id>                   Mark IN_PROGRESS, increment attempts
  objective-complete <id> "<sha>" "<url>" Mark VERIFIED
  objective-fail <id> "<reason>"         Mark FAILED (or FAILED_EXCEEDED if 5+ attempts)
  objective-retry <id>                   Reset to PENDING for retry

  ci-update <id> <PASS|FAIL> "<sha>" "<url>" Record CI result
EOF
            exit 1
            ;;
    esac
}

main "$@"
