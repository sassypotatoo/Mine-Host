#!/usr/bin/env bash
#
# push-gated.sh — deterministic, non-forcing push gate for the autonomous loop.
#
# Guarantees enforced structurally (not just by convention):
#   * accepts NO options at all  -> a force flag can never be passed through
#   * pushes only the CURRENT branch to its same-named ref on origin
#   * refuses to push if origin has commits absent locally (never overwrites
#     unrelated remote work; operator must rebase/merge manually)
#   * refuses to push with uncommitted tracked changes
#   * reports local build-gate availability honestly; never fakes it
#
# Usage: tools/push-gated.sh        (no arguments, no options)
set -euo pipefail

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; RST=$'\033[0m'
block() { printf '%s[PUSH-GATE BLOCKED]%s %s\n' "$RED" "$RST" "$*" >&2; exit 1; }
note()  { printf '%s[push-gate]%s %s\n' "$YLW" "$RST" "$*"; }
ok()    { printf '%s[push-gate]%s %s\n' "$GRN" "$RST" "$*"; }

for arg in "$@"; do
  case "$arg" in
    -*) block "option '$arg' refused: this gate performs plain non-forcing pushes only." ;;
  esac
done

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || block "not a git repository"
BRANCH=$(git symbolic-ref --quiet --short HEAD) || block "detached HEAD: refusing to push"
git remote get-url origin >/dev/null 2>&1 || block "no 'origin' remote configured"

if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  git fetch origin "$BRANCH" --quiet || block "could not fetch origin/$BRANCH"
  BEHIND=$(git rev-list --count "HEAD..origin/$BRANCH")
  AHEAD=$(git rev-list --count "origin/$BRANCH..HEAD")
  [ "$BEHIND" -eq 0 ] || block "origin/$BRANCH contains $BEHIND commit(s) not present locally.\n  Rebase/merge manually, re-run gates, then retry. This gate never overwrites remote work."
  ok "branch=$BRANCH ahead=$AHEAD behind=0"
else
  note "branch $BRANCH does not exist on origin yet (first push)"
fi

DIRTY=$(git status --porcelain --untracked-files=no)
[ -z "$DIRTY" ] || block "uncommitted tracked changes present; commit them first:
$DIRTY"
ok "working tree clean"

if command -v java >/dev/null 2>&1 && command -v gradle >/dev/null 2>&1; then
  note "JDK + Gradle detected: running local compile gate"
  gradle :app:compileDebugKotlin :app:compileDebugUnitTestKotlin \
    || block "local compile gate failed; fix before pushing."
  ok "local compile gate passed"
else
  note "LOCAL COMPILE GATE: UNAVAILABLE (no JDK/Gradle on this device) - CI is the authoritative build/test gate for this push."
fi

SHA=$(git rev-parse HEAD)
note "pushing ${SHA:0:12} -> origin/$BRANCH (plain push; force is impossible through this gate)"
git push origin "HEAD:$BRANCH"
ok "pushed."
printf '[push-gate] monitor CI with: ./tools/ci-watch.sh %s\n' "${SHA:0:12}"
