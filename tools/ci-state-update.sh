#!/usr/bin/env bash
#
# ci-state-update.sh — record a finished CI run into the Beast Mode state
# file (.claude/beastmode_state.json) and advance the turn-based loop.
#
# Matching: the objective whose lastCommit equals --sha is updated; if none
# matches, falls back to the objective whose id equals currentObjectiveId.
# If neither matches, nothing is written and the script exits 2.
#
# Usage:
#   tools/ci-state-update.sh --run-id <id> --conclusion <success|failure>
#                            --sha <sha> --url <url> [--note <note>]
#
# Environment:
#   BEASTMODE_STATE_FILE  override the state file path (used for testing).
#
# Exit codes:
#   0  state updated (summary printed on stdout)
#   1  bad usage, or state file missing/unreadable/unwritable
#   2  no objective matched the given sha (state left untouched)

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="${BEASTMODE_STATE_FILE:-${PROJECT_ROOT}/.claude/beastmode_state.json}"

RUN_ID=""
CONCLUSION=""
SHA=""
URL=""
NOTE=""

usage() {
  echo "usage: $0 --run-id <id> --conclusion <success|failure> --sha <sha> --url <url> [--note <note>]" >&2
}

while [ $# -gt 0 ]; do
  ARG="$1"
  case "$ARG" in
    --run-id|--conclusion|--sha|--url|--note)
      if [ $# -lt 2 ]; then
        echo "[ci-state-update] error: $ARG requires a value" >&2
        exit 1
      fi
      case "$ARG" in
        --run-id)     RUN_ID="$2" ;;
        --conclusion) CONCLUSION="$2" ;;
        --sha)        SHA="$2" ;;
        --url)        URL="$2" ;;
        --note)       NOTE="$2" ;;
      esac
      shift 2
      ;;
    *)
      echo "[ci-state-update] unknown argument: $ARG" >&2
      usage
      exit 1
      ;;
  esac
done

if [ -z "$RUN_ID" ] || [ -z "$CONCLUSION" ] || [ -z "$SHA" ] || [ -z "$URL" ]; then
  echo "[ci-state-update] error: --run-id, --conclusion, --sha and --url are required" >&2
  usage
  exit 1
fi

if [ "$CONCLUSION" != "success" ] && [ "$CONCLUSION" != "failure" ]; then
  echo "[ci-state-update] error: --conclusion must be 'success' or 'failure' (got: $CONCLUSION)" >&2
  exit 1
fi

# All values are passed as argv; the heredoc is quoted so no shell
# interpolation ever reaches Python (apostrophes etc. are safe).
python3 - "$RUN_ID" "$CONCLUSION" "$SHA" "$URL" "$NOTE" "$STATE_FILE" <<'PY'
import datetime
import json
import os
import sys

run_id, conclusion, sha, url, note, state_file = sys.argv[1:7]


def die(message, code):
    sys.stderr.write("[ci-state-update] %s\n" % message)
    sys.exit(code)


if not os.path.isfile(state_file):
    die("state file not found: %s" % state_file, 1)

try:
    with open(state_file, "r", encoding="utf-8") as fh:
        state = json.load(fh)
except ValueError as exc:
    die("cannot parse state file %s: %s" % (state_file, exc), 1)

if not isinstance(state, dict):
    die("state file %s is not a JSON object" % state_file, 1)

objectives = state.get("objectives")
if not isinstance(objectives, list):
    objectives = []
    state["objectives"] = objectives

current_objective_id = state.get("currentObjectiveId")

objective = None
match_reason = ""
for candidate in objectives:
    if candidate.get("lastCommit") == sha:
        objective = candidate
        match_reason = "lastCommit=%s" % sha
        break

if objective is None and current_objective_id:
    for candidate in objectives:
        if candidate.get("id") == current_objective_id:
            objective = candidate
            match_reason = "currentObjectiveId=%s" % current_objective_id
            break

if objective is None:
    die(
        "no objective matches sha=%s and currentObjectiveId=%r; state not updated"
        % (sha, current_objective_id),
        2,
    )


def pick_next_objective():
    for candidate in objectives:
        if candidate.get("status") == "PENDING":
            return candidate
    return None


def advance_to_next():
    """Advance the loop to the next PENDING objective, or finish."""
    next_objective = pick_next_objective()
    if next_objective is not None:
        state["currentObjectiveId"] = next_objective.get("id")
        next_objective["attempts"] = 1
        state["phase"] = "IMPLEMENT"
        state["workflowStatus"] = "ACTIVE"
        return next_objective
    state["currentObjectiveId"] = None
    state["beastModeEnabled"] = False
    if any(o.get("status") == "FAILED_EXCEEDED" for o in objectives):
        state["workflowStatus"] = "BLOCKED"
        state["phase"] = "FAILED"
    else:
        state["workflowStatus"] = "COMPLETE"
        state["phase"] = "COMPLETE"
    return None


old_status = objective.get("status", "?")
old_attempts = objective.get("attempts") or 0
next_objective = None

state["lastCiRunUrl"] = url

if conclusion == "success":
    objective["status"] = "VERIFIED"
    objective["ciStatus"] = "PASS"
    objective["ciRunUrl"] = url
    objective["verificationNotes"] = note if note else "CI verification passed"
    objective["failureNotes"] = ""
    next_objective = advance_to_next()
else:  # failure
    objective["ciStatus"] = "FAIL"
    objective["ciRunUrl"] = url
    objective["failureNotes"] = note if note else "CI verification failed"
    if old_attempts >= 5:
        objective["status"] = "FAILED_EXCEEDED"
        objective["verificationNotes"] = "Exceeded retry budget (5 attempts)"
        next_objective = advance_to_next()
    else:
        objective["attempts"] = old_attempts + 1
        state["phase"] = "IMPLEMENT"
        state["workflowStatus"] = "ACTIVE"

state["timestamp"] = datetime.datetime.now(datetime.timezone.utc).strftime(
    "%Y-%m-%dT%H:%M:%SZ"
)

tmp_path = state_file + ".tmp"
try:
    with open(tmp_path, "w", encoding="utf-8") as fh:
        json.dump(state, fh, indent=2)
        fh.write("\n")
    os.replace(tmp_path, state_file)
except OSError as exc:
    if os.path.exists(tmp_path):
        try:
            os.remove(tmp_path)
        except OSError:
            pass
    die("failed to write state file %s: %s" % (state_file, exc), 1)

summary = (
    "run=%s conclusion=%s; objective '%s' (matched by %s) %s->%s, attempts=%s, "
    "ciStatus=%s; next=%s; phase=%s; workflowStatus=%s; %s"
    % (
        run_id,
        conclusion,
        objective.get("id", "?"),
        match_reason,
        old_status,
        objective.get("status", "?"),
        objective.get("attempts", 0),
        objective.get("ciStatus", "?"),
        next_objective.get("id") if next_objective is not None else "none",
        state.get("phase", "?"),
        state.get("workflowStatus", "?"),
        url,
    )
)
print("[ci-state-update] " + summary)
PY
