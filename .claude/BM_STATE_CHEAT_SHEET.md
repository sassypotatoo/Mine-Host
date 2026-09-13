# Beast Mode State Commands — Quick Reference

## Read Current State

```bash
./tools/bm-state.sh status          # Pretty-print current state with objectives
./tools/bm-state.sh get             # Raw JSON state
```

## Task Lifecycle Commands

```bash
./tools/bm-state.sh task-start "<branch>" "<task-description>"
  # Initialize new task, reset objectives
  # Example: task-start "feature/dark-mode" "Add dark mode support"

./tools/bm-state.sh task-done
  # Mark task COMPLETE

./tools/bm-state.sh task-pause
  # Pause workflow (stay in current state)

./tools/bm-state.sh task-resume
  # Resume paused workflow

./tools/bm-state.sh task-cancel
  # Cancel task, reset to IDLE
```

## Objective Lifecycle Commands

```bash
./tools/bm-state.sh objective-add "<description>"
  # Create objective, returns ID
  # Example: objective-add "Update theme provider in settings"
  # RETURNS: objective-id (capture this!)

./tools/bm-state.sh objective-start <id>
  # Mark IN_PROGRESS, increment attempts
  # Call AFTER git commit, BEFORE ci-watch

./tools/bm-state.sh objective-complete <id> "<commit-sha>" "<ci-url>"
  # Mark VERIFIED (CI passed)
  # Example: objective-complete "1694610274123" "abc1234def..." "https://github.com/..."

./tools/bm-state.sh objective-fail <id> "<reason>"
  # Mark FAILED (CI failed)
  # Reason recorded in state

./tools/bm-state.sh objective-retry <id>
  # Reset for retry (useful after analyzing failure)
```

## CI Result Tracking

```bash
./tools/bm-state.sh ci-update <id> <PASS|FAIL> "<commit-sha>" "<ci-url>"
  # Record CI result without marking objective complete/failed
  # Useful if need manual decision before final status
```

## Monitoring & Verification

```bash
./tools/ci-watch.sh --sha <commit-sha>
  # Watch GitHub Actions for commit
  # Exit 0: PASS, Exit 1: FAIL, Exit 2: inconclusive

./tools/push-gated.sh
  # Safe push (no options, no --force possible)
```

## Typical Workflow Sequence

```bash
# 1. Start task
./tools/bm-state.sh task-start "feature-branch" "Implement feature X"

# 2. Create objectives
OBJ1=$(./tools/bm-state.sh objective-add "Part 1: Update model")
OBJ2=$(./tools/bm-state.sh objective-add "Part 2: Update UI")

# 3. Check state
./tools/bm-state.sh status

# 4. For each objective:
# 4a. Start objective
./tools/bm-state.sh objective-start $OBJ1

# 4b. Implement code
# ... make changes, test locally ...

# 4c. Commit and push
git add -u
git commit -m "Implement objective: Part 1"
SHA=$(git rev-parse HEAD)
./tools/push-gated.sh

# 4d. Watch CI
./tools/ci-watch.sh --sha $SHA

# 4e. If PASS (exit 0):
./tools/bm-state.sh objective-complete $OBJ1 "$SHA" "https://..."

# 4f. If FAIL (exit 1):
./tools/bm-state.sh objective-fail $OBJ1 "CI failed: see logs"
# [Fix code]
# [Retry from step 4c]

# 5. Repeat for OBJ2

# 6. When all complete
./tools/bm-state.sh task-done
```

## State File Location
```
.claude/beastmode_state.json
```

## Common Patterns

### Check if a task is running
```bash
./tools/bm-state.sh status | grep "Status"
# Look for: ACTIVE (running) or IDLE (not running)
```

### Find current objective ID
```bash
./tools/bm-state.sh status | grep "→"
# Arrow points to current objective
```

### Get raw JSON for scripting
```bash
STATE=$(./tools/bm-state.sh get)
TASK=$(echo "$STATE" | python3 -c "import sys, json; print(json.load(sys.stdin)['currentTask'])")
```
