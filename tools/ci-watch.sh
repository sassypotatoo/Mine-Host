#!/usr/bin/env bash
#
# ci-watch.sh — progress-aware GitHub Actions watchdog for the MineHost loop.
#
# Tracks REAL observable progress (step transitions + best-effort live log
# tails), not merely total runtime. A long-running build is fine; ~20 minutes
# with NO new step/event/log change triggers an investigation report (and
# keeps watching). Cancellation is never automatic.
#
# Usage:
#   tools/ci-watch.sh [--sha SHA] [--timeout MIN] [--interval SEC] [--once]
#                     [--tail N] [--stall-min N] [--update-state [NOTE]]
#
#   --sha           commit to watch (default: HEAD)
#   --timeout       max minutes to watch at all (default: 60)
#   --interval      seconds between polls (default: 60)
#   --once          single check, do not loop
#   --tail          max lines of failed-step log to print on FAIL (default: 150)
#   --stall-min     minutes of no observable progress before INVESTIGATING
#                   (default: 20)
#   --update-state  [NOTE]  on terminal state (PASS or FAIL), record the result
#                   into .claude/beastmode_state.json via
#                   tools/ci-state-update.sh. Optional NOTE is recorded.
#
# Exit codes:
#   0  run(s) concluded successfully
#   1  at least one run failed (real logs printed)
#   2  inconclusive: no run appeared / still running in --once / timed out
#   Requires: authenticated `gh` CLI, git, python3. No jq needed.
set -euo pipefail

SHA="HEAD"; TIMEOUT_MIN=60; INTERVAL=60; ONCE=0; TAIL=150; STALL_MIN=20
UPDATE_STATE=0; STATE_NOTE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --timeout) TIMEOUT_MIN="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --once) ONCE=1; shift ;;
    --tail) TAIL="$2"; shift 2 ;;
    --stall-min) STALL_MIN="$2"; shift 2 ;;
    --update-state)
      UPDATE_STATE=1
      # Optional positional NOTE following the flag (only if not another flag).
      if [ $# -ge 2 ] && [ "${2:-}" != "--"* ]; then
        STATE_NOTE="$2"; shift 2
      else
        shift 1
      fi
      ;;
    *) echo "[ci-watch] unknown argument: $1" >&2; exit 2 ;;
  esac
done


command -v gh >/dev/null 2>&1 || { echo "[ci-watch] gh CLI missing" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "[ci-watch] python3 missing" >&2; exit 2; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "[ci-watch] not a git repository" >&2; exit 2; }

FULL_SHA=$(git rev-parse "$SHA")
BRANCH=$(git symbolic-ref --quiet --short HEAD 2>/dev/null || echo "detached")

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

maybe_update_state() {
  local run_id="$1"
  local conclusion="$2"
  local sha="$3"
  local url="$4"

  "$PROJECT_ROOT/tools/ci-state-update.sh" --run-id "$run_id" --conclusion "$conclusion" --sha "$sha" --url "$url" || echo "WARNING: ci-state-update failed" >&2
}

REMOTE_URL=$(git remote get-url origin)
REPO=${REMOTE_URL#*github.com[:\/]}
REPO=${REPO%.git}
[ -n "$REPO" ] && [ "$REPO" != "$REMOTE_URL" ] || { echo "[ci-watch] cannot derive GitHub repo from origin: $REMOTE_URL" >&2; exit 2; }

echo "[ci-watch] repo=$REPO sha=${FULL_SHA:0:12} timeout=${TIMEOUT_MIN}m interval=${INTERVAL}s stall-threshold=${STALL_MIN}m"

# stdin: JSON array of runs; stdout (tab-separated): "PENDING\t<n>\t<firstId>" or
# "DONE\t<id>\t<name>\t<conclusion>\t<url>"
summarize_runs() {
  ONCE_FLAG="$ONCE" EXPECTED_SHA="$FULL_SHA" python3 -c '
import json, sys, os
runs = json.load(sys.stdin)
once = os.environ.get("ONCE_FLAG") == "1"
expected = os.environ.get("EXPECTED_SHA", "")
# Defensive: drop any run whose headSha mismatches (server-side filters can lag).
runs = [r for r in runs if not expected or r.get("headSha") == expected]
pending = sum(1 for r in runs if r.get("status") != "completed")
done = [r for r in runs if r.get("status") == "completed"]
if pending and not (once or not done):
    print("PENDING\t%d\t%s" % (pending, runs[0]["databaseId"]))
elif done:
    push = [r for r in done if r.get("event") == "push"]
    r = (push or done)[0]
    print("\t".join(["DONE", str(r["databaseId"]), r.get("name", "?"),
                     str(r.get("conclusion")), r.get("url", "")]))
else:
    first = str(runs[0]["databaseId"]) if runs else ""
    print("PENDING\t%d\t%s" % (pending, first))
'
}

# Fetch current job/step metadata for a run id.
# Prints tab-separated: jobId \t jobStatus \t currentStepName \t stepStartedAt
fetch_job_state() {
  gh api "repos/$REPO/actions/runs/$1/jobs?per_page=1" 2>/dev/null | \
    python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("\t\t\t")
    raise SystemExit(0)
jobs = data.get("jobs") or []
if not jobs:
    print("\t\t\t")
    raise SystemExit(0)
job = jobs[0]
cur, started = "", ""
for s in job.get("steps", []):
    if s.get("status") == "in_progress":
        cur, started = s.get("name", ""), s.get("started_at", "")
        break
print("%s\t%s\t%s\t%s" % (job.get("id", ""), job.get("status", ""), cur, started))
' || printf '\t\t\t\n'
}

# Best-effort live log tail. Prints nothing when unavailable; echoes
# "UNAVAILABLE" marker on stderr-free failure modes.
fetch_log_tail() {
  local job_id="$1"
  [ -n "$job_id" ] || return 0
  gh api "repos/$REPO/actions/jobs/$job_id/logs" 2>/dev/null | tail -n 200 || true
}

extract_last_event() {
  # stdin: raw log lines; stdout: last meaningful progress line (or empty)
  python3 -c '
import sys, re
last = ""
pat = re.compile(r"(STARTED|PASSED|FAILED|SKIPPED)|(^> Task )|(##\[group\])")
for line in sys.stdin:
    line = line.rstrip("\n")
    # strip leading timestamp column emitted by gh log format if present
    m = pat.search(line)
    if m:
        last = line[-160:]
print(last)
'
}

DEADLINE=$(( $(date +%s) + TIMEOUT_MIN * 60 ))
LAST_SIG=""
LAST_CHANGE=$(date +%s)
LAST_EVENT_DESC="(none observed yet)"
STALL_REPORTED=0

while :; do
  RUNS_JSON=$(gh run list --repo "$REPO" --commit "$FULL_SHA" \
                --json databaseId,name,status,conclusion,event,url,headSha --limit 20 2>/dev/null || echo '[]')
  # GitHub's per-commit run index can lag for many minutes after a push; when
  # the filtered query yields nothing, fall back to an unfiltered scan.
  if [ "$(printf '%s' "$RUNS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')" = "0" ]; then
    RUNS_JSON=$(gh run list --repo "$REPO" \
                  --json databaseId,name,status,conclusion,event,url,headSha --limit 40 2>/dev/null || echo '[]')
  fi

  STATE=$(printf '%s' "$RUNS_JSON" | summarize_runs)

  case "$STATE" in
    DONE*)
      ID=$(printf '%s' "$STATE" | cut -f2)
      NAME=$(printf '%s' "$STATE" | cut -f3)
      CONCLUSION=$(printf '%s' "$STATE" | cut -f4)
      RUN_URL=$(printf '%s' "$STATE" | cut -f5)
      echo "[ci-watch] run #$ID '$NAME' -> $CONCLUSION"
      [ -n "$RUN_URL" ] && echo "[ci-watch] $RUN_URL"

      if [ "$CONCLUSION" = "success" ]; then
        echo "[ci-watch] RESULT: PASS"
        maybe_update_state "$ID" "success" "$FULL_SHA" "$RUN_URL"
        exit 0
      fi

      echo ""
      echo "========== FAILED-STEP LOG (tail $TAIL) =========="
      gh run view "$ID" --repo "$REPO" --log-failed 2>/dev/null | tail -n "$TAIL" \
        || echo "(--log-failed produced nothing; inspect $RUN_URL)"
      echo "========== FAILURE ANNOTATIONS =========="
      for CRID in $(gh api "repos/$REPO/commits/$FULL_SHA/check-runs" \
                      --jq '.check_runs[] | select(.conclusion=="failure") | .id' 2>/dev/null); do
        gh api "repos/$REPO/check-runs/$CRID/annotations" \
          --jq '.[] | select(.annotation_level=="failure") | "  - " + .message' 2>/dev/null || true
      done
      echo "=========================================="
      echo "[ci-watch] RESULT: FAIL"
      maybe_update_state "$ID" "failure" "$FULL_SHA" "$RUN_URL"
      exit 1
      ;;
    PENDING*)
      N=$(printf '%s' "$STATE" | cut -f2)
      RID=$(printf '%s' "$STATE" | cut -f3)
      NOW=$(date +%s)

      CUR_STEP=""; STEP_STARTED=""; JOB_ID=""; JOB_STATUS=""
      if [ -n "$RID" ] && [ "$RID" != "" ]; then
        JSTATE=$(fetch_job_state "$RID")
        JOB_ID=$(printf '%s' "$JSTATE" | cut -f1)
        JOB_STATUS=$(printf '%s' "$JSTATE" | cut -f2)
        CUR_STEP=$(printf '%s' "$JSTATE" | cut -f3)
        STEP_STARTED=$(printf '%s' "$JSTATE" | cut -f4)
      fi

      SIG="$RID|$CUR_STEP"
      TAIL_OUT=""
      if [ -n "$JOB_ID" ]; then
        TAIL_OUT=$(fetch_log_tail "$JOB_ID")
        case "$TAIL_OUT" in
          *"<Code>BlobNotFound"*) TAIL_OUT="" ; SIG="$SIG|nologs" ;;
          *)
            EV=$(printf '%s' "$TAIL_OUT" | extract_last_event)
            [ -n "$EV" ] && SIG="$SIG|$EV"
            ;;
        esac
      fi

      if [ "$SIG" != "$LAST_SIG" ]; then
        LAST_SIG="$SIG"
        LAST_CHANGE=$NOW
        if [ -n "${EV:-}" ]; then
          LAST_EVENT_DESC="$EV"
        elif [ -n "$CUR_STEP" ]; then
          LAST_EVENT_DESC="step -> $CUR_STEP"
        fi
      fi
      EV=""

      INACTIVE=$(( (NOW - LAST_CHANGE) / 60 ))
      INACTIVE_S=$(( NOW - LAST_CHANGE ))
      STATE_LABEL="OK"
      if [ "$INACTIVE_S" -ge $(( STALL_MIN * 60 )) ]; then
        STATE_LABEL="INVESTIGATING"
      fi

      echo "[ci-watch] $(date '+%H:%M:%S') run=$RID job=$JOB_ID step='${CUR_STEP:-?}' inactive=${INACTIVE_S}s state=$STATE_LABEL last-event: ${LAST_EVENT_DESC}"

      if [ "$STATE_LABEL" = "INVESTIGATING" ] && [ "$STALL_REPORTED" = "0" ]; then
        STALL_REPORTED=1
        echo ""
        echo "========== WATCHDOG: NO OBSERVABLE PROGRESS FOR ${STALL_MIN}m =========="
        echo "Job:      $JOB_ID ($JOB_STATUS)"
        echo "Step:     $CUR_STEP (started $STEP_STARTED)"
        echo "Last event: $LAST_EVENT_DESC"
        echo "--- steps so far ---"
        [ -n "$RID" ] && gh api "repos/$REPO/actions/runs/$RID/jobs" 2>/dev/null | \
          python3 -c '
import json, sys
try: data = json.load(sys.stdin)
except Exception: raise SystemExit(0)
for j in data.get("jobs", []):
    for s in j.get("steps", []):
        print("  %-12s %-40s %s -> %s" % (s.get("status"), s.get("name"), s.get("started_at"), s.get("completed_at")))
' || true
        echo "--- live log tail (best effort; may be unavailable mid-step) ---"
        if [ -n "$TAIL_OUT" ]; then printf '%s\n' "$TAIL_OUT" | tail -n 25; else echo "(unavailable)"; fi
        echo "NOTE: long builds are legitimate; this is an investigation aid."
        echo "======================================================================"
        echo ""
      fi

      if [ "$ONCE" -eq 1 ]; then
        echo "[ci-watch] runs exist but none completed yet: $N pending"
        exit 2
      fi
      ;;
    *)
      echo "[ci-watch] unexpected state: $STATE"
      exit 2
      ;;
  esac

  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "[ci-watch] TIMED OUT after ${TIMEOUT_MIN}m without a completed run."
    echo "[ci-watch] last observable progress: $LAST_EVENT_DESC (${INACTIVE_S}s ago)"
    exit 2
  fi
  sleep "$INTERVAL"
done
