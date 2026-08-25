# MineHost Autonomous Workflow — Environment Specification

This document defines how autonomous tasks run in THIS environment (Termux/Android,
repo `sassypotatoo/Mine-Host`). It complements `.claude/skills/minehost-autonomous/SKILL.md`,
which remains the governing process document (retry limits 5/5/5/15, hard-stop
protocol, protected systems, mandatory report format — all preserved).

Entry point for any task: `/minehost-auto <task description>`.

---

## 1. Environment capabilities (audited 2026-08-25)

| Capability | Status | Notes |
|---|---|---|
| git | Available | credential helper via `gh auth git-credential` |
| gh CLI | Available, authenticated (`repo`, `workflow` scopes) | drives all GitHub API/Actions access |
| python3, curl, unzip | Available | |
| jq | MISSING | scripts use gh's built-in `--jq` / python3 instead |
| JDK / javac / Gradle / aapt2 | MISSING | **local compile/test gates are UNAVAILABLE in Termux** |
| adb / android-tools | MISSING | device verification not currently performable from this device |
| ripgrep (`rg`) | MISSING on arm64-android | Claude Code Glob/Grep tools fail here; use `grep` via Bash or Explore agents |

Consequence: **GitHub Actions is the authoritative build/test gate.** The local
compile gate in `tools/push-gated.sh` self-detects JDK/Gradle: if they are ever
installed (e.g. `pkg install openjdk-17 gradle`), the gate runs locally automatically;
until then it prints `LOCAL COMPILE GATE: UNAVAILABLE` honestly.

Known caveat if Gradle is later installed natively: Android builds may require an
aapt2 override because upstream aapt2 binaries are not arm64-android; treat any such
setup as its own gated task with evidence, not a silent workaround.

---

## 2. The closed loop (with CI feedback)

```
/minehost-auto <task>
   |
   v
[0] Load context: SKILL.md + MASTER_PROJECT_CONTEXT.md + this file + AUTONOMOUS_STATE.md
   |    (if STATE records IN_PROGRESS work -> resume from recorded phase)
   v
[1] Plan (smallest safe change; protected systems frozen)
   v
[2] Implement
   v
[3] Local gates: only what this device can actually run (currently: none)
   v
[4] Review `git diff`; commit (logical scope, no artifacts/secrets)
   v
[5] ./tools/push-gated.sh          <- the ONLY sanctioned push path
   v
[6] ./tools/ci-watch.sh <sha>      <- poll the Actions run for that SHA
   |                                     |
   PASS                                  FAIL -> real failed-step logs printed
   |                                     v
   |                        root-cause diagnosis -> smallest fix
   |                        -> increment fix iteration counters (max 5/5/5, total 15)
   |                        -> back to [3]; at any limit: HARD STOP + honest report
   v
[7] Update AUTONOMOUS_STATE.md (gates, run ids, next step)
   v
[8] Report using SKILL.md mandatory format. Runtime Verification = UNVERIFIED
    unless real device evidence exists.
```

### Baseline failure (pre-existing, do NOT silently retry)

`gradle/wrapper/gradle-wrapper.jar` has checksum `a5e75118d96b4eac...`, rejected by
`gradle/actions/setup-gradle@v6` wrapper validation ("Found unknown Gradle Wrapper
JAR files"). Every push therefore fails CI at the **Set up Gradle** step until a human
approves wrapper repair. If a run fails ONLY there, compare against this baseline,
record it, and STOP — it is not caused by your change and fixing it is out of scope
without explicit approval.

---

## 3. Git/GitHub determinism contract

1. Branch is verified before anything else; pushes target the current branch only.
2. Pushes happen ONLY through `tools/push-gated.sh`, which:
   - refuses every option (force flags are structurally impossible);
   - refuses when origin is ahead (never overwrites unrelated remote work);
   - refuses with uncommitted tracked changes;
   - reports local-gate availability truthfully.
3. Committed project settings deny force-push/reset-hard patterns as defense-in-depth
   (`.claude/settings.json`) — but the script is the primary guarantee.
4. Never push before gates pass; never push known-broken code to `main`
   (exception documented above: baseline red CI predates infra work).
5. No generated artifacts, keystores, `local.properties`, or `.env` ever committed.

## 4. State persistence (survives session/restart loss)

Everything the loop needs lives IN THE REPOSITORY, not in Claude's memory:

| File | Purpose |
|---|---|
| `MineHost_MASTER_PROJECT_CONTEXT.md` | project spec/source of truth (root copy; Downloads fallback) |
| `.claude/skills/minehost-autonomous/SKILL.md` | governing workflow rules |
| `.claude/commands/minehost-auto.md` | one-command task launcher |
| `docs/autonomous/AUTONOMOUS_WORKFLOW.md` | this specification |
| `docs/autonomous/AUTONOMOUS_STATE.md` | living task/gate state — update at every phase change |
| `tools/ci-watch.sh`, `tools/push-gated.sh` | deterministic loop mechanics |
| `CLAUDE.md` | auto-loaded orientation for any new session |
| `device-test-evidence/` | future real-device logs/screenshots (gate evidence) |

Recovery protocol after any restart: read `AUTONOMOUS_STATE.md`, reconcile with
`git log --oneline -5` and `./tools/ci-watch.sh --sha HEAD --once`, then continue
from the recorded phase or mark the task BLOCKED if reconciliation fails.

## 5. Android verification tiers (honest separation)

- **T1 Termux-local compile/test:** UNAVAILABLE today (no JDK/Gradle). Becomes
  available only after explicit toolchain installation; then record exact commands used.
- **T2 Build/test via CI:** authoritative. Green run + `minehost-debug` APK artifact.
- **T3 Device/runtime verification:** requires a real install+run on this phone with
  evidence stored under `device-test-evidence/<date>/` (logcat excerpts, screenshots,
  checklist results from `DEVICE_TEST_CHECKLIST.txt`). Until such evidence exists,
  Runtime Verification is reported `UNVERIFIED` (or `BLOCKED` when required) — never
  claimed. Designed future option (not yet verified): install `android-tools` in
  Termux, enable Wireless Debugging, `adb connect 127.0.0.1:<port>` for logcat/install
  feedback; enabling it is its own gated setup task requiring user interaction.

## 6. Portability (copying to laptop / another project)

The workflow is repo-contained and project-generic except for names:

1. Copy: `.claude/skills/minehost-autonomous/`, `.claude/commands/minehost-auto.md`,
   `.claude/settings.json`, `docs/autonomous/`, `tools/ci-watch.sh`,
   `tools/push-gated.sh`, `CLAUDE.md`.
2. Rename references to `MineHost_MASTER_PROJECT_CONTEXT.md` in the command file
   and skill to the new project's context doc.
3. Adjust module paths in `push-gated.sh` local gate and `ci-watch.sh` defaults if
   the CI workflow differs.
4. Reset `docs/autonomous/AUTONOMOUS_STATE.md` to a fresh template.

No other machine-specific state exists; credentials stay outside the repo (gh hosts.yml).
