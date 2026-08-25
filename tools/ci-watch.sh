#!/usr/bin/env bash
#
# ci-watch.sh — poll GitHub Actions for workflow runs of a given commit and
# surface REAL failure logs for the autonomous debugging loop.
#
# Usage:
#   tools/ci-watch.sh [--sha SHA] [--timeout MIN] [--interval SEC] [--once] [--tail N]
#
#   --sha       commit to watch (default: HEAD)
#   --timeout   max minutes to wait for completion (default: 30)
#   --interval  seconds between polls (default: 20)
#   --once      single check, do not loop
#   --tail      max lines of failed-step log to print (default: 150)
#
# Exit codes:
#   0  run(s) for the SHA concluded successfully
#   1  at least one run failed (logs printed)
#   2  inconclusive: no run appeared / still running in --once / timed out
#
# Requires: authenticated `gh` CLI, git, python3. No jq needed.
set -euo pipefail

SHA="HEAD"; TIMEOUT_MIN=30; INTERVAL=20; ONCE=0; TAIL=150
while [ $# -gt 0 ]; do
  case "$1" in
    --sha) SHA="$2"; shift 2 ;;
    --timeout) TIMEOUT_MIN="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    --once) ONCE=1; shift ;;
    --tail) TAIL="$2"; shift 2 ;;
    *) echo "[ci-watch] unknown argument: $1" >&2; exit 2 ;;
  esac
done

command -v gh >/dev/null 2>&1 || { echo "[ci-watch] gh CLI missing" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "[ci-watch] python3 missing" >&2; exit 2; }
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "[ci-watch] not a git repository" >&2; exit 2; }

FULL_SHA=$(git rev-parse "$SHA")
REMOTE_URL=$(git remote get-url origin)
REPO=${REMOTE_URL#*github.com[:\/]}
REPO=${REPO%.git}
[ -n "$REPO" ] && [ "$REPO" != "$REMOTE_URL" ] || { echo "[ci-watch] cannot derive GitHub repo from origin: $REMOTE_URL" >&2; exit 2; }

echo "[ci-watch] repo=$REPO sha=${FULL_SHA:0:12} timeout=${TIMEOUT_MIN}m interval=${INTERVAL}s"

# stdin: JSON array of runs; stdout (tab-separated): "PENDING\t<n>" or
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
    print("PENDING\t%d" % pending)
elif done:
    push = [r for r in done if r.get("event") == "push"]
    r = (push or done)[0]
    print("\t".join(["DONE", str(r["databaseId"]), r.get("name", "?"),
                     str(r.get("conclusion")), r.get("url", "")]))
else:
    print("PENDING\t%d" % pending)
'
}

DEADLINE=$(( $(date +%s) + TIMEOUT_MIN * 60 ))
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
      exit 1
      ;;
    PENDING*)
      if [ "$ONCE" -eq 1 ]; then
        echo "[ci-watch] runs exist but none completed yet (or none found): $STATE"
        exit 2
      fi
      echo "[ci-watch] $(date '+%H:%M:%S') $STATE"
      ;;
    *)
      echo "[ci-watch] unexpected state: $STATE" >&2
      exit 2
      ;;
  esac

  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    echo "[ci-watch] TIMED OUT after ${TIMEOUT_MIN}m without a completed run."
    exit 2
  fi
  sleep "$INTERVAL"
done
