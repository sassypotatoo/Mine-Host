---
description: Run one full autonomous MineHost task through the closed loop (context, plan, implement, build, test, verify, review, gated push, CI monitoring, honest report)
argument-hint: <task description>
---

# MineHost Autonomous Task

Task: $ARGUMENTS

Execute this task under the **minehost-autonomous** workflow. Follow these steps exactly.

## 0. Load context (mandatory before anything else)

1. Read `.claude/skills/minehost-autonomous/SKILL.md` — the governing workflow (retry limits, protected systems, output format, strict rules).
2. Read `MineHost_MASTER_PROJECT_CONTEXT.md` at the repo root; if absent, read `$HOME/storage/downloads/MineHost_MASTER_PROJECT_CONTEXT.md`.
3. Read `docs/autonomous/AUTONOMOUS_WORKFLOW.md` — environment capabilities, verification tiers, CI loop protocol.
4. Read `docs/autonomous/AUTONOMOUS_STATE.md` — if it records an IN_PROGRESS task, resume it from its recorded phase instead of starting over (reconcile against `git log` and `./tools/ci-watch.sh --sha HEAD --once` before trusting it).

## 1. Plan

State what you will change and why, scoped to the smallest safe change. Protected systems (native JVM launcher, runtime extraction/validation, CMake config, engine launch commands, engine download system) are off-limits unless a confirmed defect requires intervention.

## 2. Implement

Make the change. Do not refactor beyond the task. Do not touch protected systems on a hunch.

## 3. Build & test gates

This Termux device has **no JDK/Gradle**: local compile/test gates are UNAVAILABLE here. Say so explicitly; do not simulate them. The authoritative build/test gate is GitHub Actions:

1. Commit locally (only after self-review of `git diff`).
2. Push with `./tools/push-gated.sh` — never any other push form; force-push is forbidden and structurally blocked.
3. Monitor with `./tools/ci-watch.sh` (defaults to HEAD; pass the pushed SHA explicitly if unsure).

## 4. CI feedback loop

- On PASS: record run id/conclusion in `docs/autonomous/AUTONOMOUS_STATE.md`.
- On FAIL: `ci-watch.sh` prints real failed-step logs and annotations. Diagnose the ROOT CAUSE, apply the smallest fix, repeat from step 2.
- Baseline failure: `gradle/wrapper/gradle-wrapper.jar` fails setup-gradle wrapper validation (unknown checksum). If your run fails ONLY at "Set up Gradle" with that annotation and your change cannot have caused it, compare against this baseline and STOP per protocol rather than retrying — repairing the wrapper requires explicit human approval.
- Retry limits remain MAX_BUILD_FIX_ITERATIONS=5, MAX_TEST_FIX_ITERATIONS=5, MAX_RUNTIME_FIX_ITERATIONS=5, MAX_TOTAL_ITERATIONS=15. Hitting any limit means hard STOP: report exact failure with quoted output and stop.

## 5. Runtime/device gate

Never claim device testing. APK artifact (`minehost-debug`) from a green CI run is the deliverable for device verification. Runtime Verification stays `UNVERIFIED` until real device evidence exists in `device-test-evidence/`.

## 6. Report

Use the mandatory output format from SKILL.md (Task / Plan / Implementation / Build Verification / Test Verification / Runtime Verification / Review / Git / Final Status). Quote captured output; never fabricate results.
