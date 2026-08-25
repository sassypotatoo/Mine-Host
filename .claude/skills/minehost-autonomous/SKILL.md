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
