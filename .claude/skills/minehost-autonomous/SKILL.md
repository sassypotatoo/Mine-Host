---
name: minehost-autonomous
description: Use when implementing any MineHost feature or bugfix autonomously in the MineHost repository - required before writing code, running builds or tests, claiming completion, committing, or pushing
---

# MineHost Autonomous Development Loop

## Overview

A strict closed-loop workflow: context → plan → implement → build → test → verify → review → only then commit/push. Code that merely compiles is not done. A claim without evidence is forbidden.

**Violating the letter of these rules is violating the spirit of them.**

## When to Use

Use for ANY autonomous implementation task in the MineHost repo. Do not use for pure investigation/audit tasks (no code changes), or when the human partner explicitly overrides a rule for a specific task.

## Context Sources

1. Read `MineHost_MASTER_PROJECT_CONTEXT.md` at the repo root if present; otherwise try `~/storage/downloads/MineHost_MASTER_PROJECT_CONTEXT.md`. Treat it as project specification/context. Its Appendix B rule applies: current verified source/build/test state wins over historical notes.
2. Honor its evidence-gated systems list (native JVM launcher, Java runtime extraction/validation, CMake config, engine launch commands, engine download system): modifications require documented evidence from investigation or CI output identifying a concrete defect or requirement, and the change must be the smallest safe fix addressing that evidence.

## Core Loop

1. Read the project context (above).
2. Inspect current repository state and relevant implementation before changing anything.
3. Create a concrete implementation plan.
4. Implement the requested feature.
5. Run compilation/build checks (see Build Commands).
6. On build failure: inspect COMPLETE error output → identify root cause → smallest appropriate fix → rebuild. Repeat up to `MAX_BUILD_FIX_ITERATIONS`.
7. Run relevant unit/integration tests.
8. On test failure: inspect failure → diagnose root cause → fix → rerun. Repeat up to `MAX_TEST_FIX_ITERATIONS`.
9. Build the debug APK when appropriate for the change.
10. Perform device/runtime verification when an Android test target (device/emulator) is actually available.
11. Collect and inspect runtime/logcat output when relevant.
12. On runtime-verification failure: diagnose → fix → repeat build/test cycle. Repeat up to `MAX_RUNTIME_FIX_ITERATIONS`.
13. Run a final `git diff` review.
14. Run a security review for security-sensitive changes (downloads, crypto, file paths, IPC, auth, tunneling).
15. Only after ALL applicable gates pass: create the final commit and push to the configured GitHub repository.

## Build Commands

Default (matches CI `.github/workflows/android.yml`):

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

If the Gradle wrapper jar is invalid (known historical defect: `BadZipFile` on `gradle/wrapper/gradle-wrapper.jar`), fall back to an installed Gradle per `DEVICE_TEST_CHECKLIST.txt`, and report which launcher was used. Record the EXACT command and result for the report.

## Strict Rules

- Never declare a task complete merely because code was written.
- Never treat compilation as proof the feature works.
- Never claim device testing happened unless an actual device/emulator was tested. If none was available, the report says `UNVERIFIED` — no exceptions.
- Never ignore compiler/test/runtime errors.
- Never push known-broken code to the final branch (`main`).
- Never blindly rewrite large portions; preserve existing architecture.
- Prefer the smallest safe change.
- Do not remove working functionality to make a build pass.
- Do not fabricate test results. Never summarize output you did not capture.

| Excuse | Reality |
|---|---|
| "It compiles, so it works" | Compilation proves syntax only. Run the tests. |
| "Tests probably pass, same as before" | Probably is not evidence. Run them and quote real output. |
| "No device attached, skip runtime gate silently" | Allowed to skip ONLY by stating `Runtime Verification: UNVERIFIED` explicitly. |
| "Just this once, push now, CI later" | Push happens strictly after all applicable gates pass. |
| "The launcher/wrapper would be cleaner rewritten" | Rewrite requires documented evidence of a concrete defect; prefer the smallest safe fix over replacement. |
| "Retry limit reached, one more attempt won't hurt" | One more attempt is how 5 becomes 10. Limits are hard. |

## Retry Limits and Stop Protocol

```
MAX_BUILD_FIX_ITERATIONS=5
MAX_TEST_FIX_ITERATIONS=5
MAX_RUNTIME_FIX_ITERATIONS=5
MAX_TOTAL_ITERATIONS=15   # sum of all fix iterations for one task
```

If any limit is reached: **STOP**. Report: exact failure, evidence (real output excerpts), files changed, attempts made, and what requires human intervention. Do not start another fix attempt, do not commit, do not push.

## Git Rules

- Never push before verification.
- Keep the working tree understandable; logical scope per task.
- Review the git diff before committing.
- Do not commit generated build artifacts or secrets (`.env`, keystores, `local.properties`).
- Do not alter `.gitignore` without a specific, stated reason.
- Use a feature branch or worktree for substantial autonomous tasks when practical.
- Final push only after all applicable verification gates pass.

## Subagent Rules

Use existing agents where appropriate — not by default:

- `feature-dev:code-explorer`: repository investigation
- `feature-dev:code-architect`: architecture/planning
- `feature-dev:code-reviewer`: final review
- `code-simplifier`: only when simplification is genuinely justified
- security-capable agents for security-sensitive changes

The main agent remains responsible for the final result regardless of delegation.

## Output Format (mandatory for every autonomous task)

```
## Task
What was requested.

## Plan
What will be changed and why.

## Implementation
What was actually changed.

## Build Verification
Exact command(s), result, and failures/fixes.

## Test Verification
Exact tests, result, and failures/fixes.

## Runtime Verification
Device/emulator status and evidence. If unavailable, explicitly state UNVERIFIED.

## Review
Final diff/security review.

## Git
Commit and push information.

## Final Status
PASS only when all applicable gates passed.
BLOCKED when a required verification cannot be completed.
```

Gate applicability: a gate is applicable unless structurally impossible for the change (e.g., Runtime Verification with no device/emulator reachable). Impossible-but-applicable gates yield `BLOCKED`, never `PASS`.

## Red Flags - STOP

- About to commit/push with any failing or unrun gate
- Writing "should work" instead of quoting captured output
- Device testing claimed without a device
- Fix iteration count approaching the limit without a root cause identified
- Editing formerly-protected components without documented evidence

All of these mean: stop, reassess against the Core Loop, report honestly.

---

## v2.1 dispatch layer addendum (2026-09-02)

The governing invariants above are preserved unchanged. v2.1 adds a
dispatch layer in front of this loop and an automatic state/CI bookkeeping
trail behind it. The single canonical entry point is
`/minehost-autonomous`; the v1 entry `/minehost-auto` is kept as a
compatibility alias. Full spec lives in
`.claude/skills/minehost-autonomous-v2/SKILL.md` and
`.claude/commands/minehost-autonomous.md`.

Key behavioral changes vs v1 (still inside the same hard invariants):

- `superpowers:brainstorming` classification (spike / bounded /
  architectural) is recorded for the audit trail, but does NOT pause the
  loop for human approval. The hard invariants above remain the only
  stop conditions.
- Specialist skills (`feature-dev:code-explorer`, `feature-dev:code-architect`,
  `feature-dev:code-reviewer`, `code-simplifier`, `claude-security`,
  `security-guidance`, `ralph-loop`, `context7`, `github`, `playwright`,
  `chrome-devtools-mcp`, `commit-commands`) are invoked only when they
  actually help the current task. No blind "run every plugin" mode.
- `context7` is invoked automatically BEFORE writing code that touches
  external / version-sensitive libraries or APIs (Gradle, AGP, Kotlin,
  Nukkit/Paper/PaperMC, Supabase, FRP, etc.). Skipped for trivial local
  edits where it adds no value.
- `claude-security` + `security-guidance` are invoked automatically when
  the change touches any of: downloads, binaries, command execution,
  JNI/native loading, networking, authentication, tunneling, permissions,
  file extraction, IPC, crypto, Supabase/RLS, or user-controlled input.
- QA: a successful compile alone is not evidence that MineHost
  functionality works. The verification step must address whether the
  change actually does what the task asked for; the report quotes
  captured output.
- The repository is the source of truth. If documentation disagrees with
  code, the discrepancy is recorded in `AUTONOMOUS_STATE.md` under
  "Discovered issues" rather than hallucinated as implemented.
- `AUTONOMOUS_STATE.md` is updated at every phase transition with the
  v2.1 schema: current objective, current phase, classification/path,
  completed work, in-progress work, failed attempts, discovered issues,
  decisions, files changed, verification results, CI result, next action.
- `tools/ci-watch.sh --update-state` auto-feeds CI results into
  `AUTONOMOUS_STATE.md` via `tools/ci-state-update.sh` so the human
  partner never has to copy-paste run ids.

Retry limits, protected systems, mandatory report format, and the
hard-stop protocol are unchanged. v2.1 does not weaken them.
