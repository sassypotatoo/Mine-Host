# MineHost — Session Orientation (auto-loaded)

MineHost: standalone Android app hosting real Minecraft Java/Bedrock servers on-device.
No fake state, no PRoot/Termux dependency in the product, protected systems stay frozen.

## Before ANY implementation task

1. Read `.claude/skills/minehost-autonomous/SKILL.md` (governing workflow: 5/5/5/15 retry limits, hard-stop protocol, mandatory report format).
2. Read `MineHost_MASTER_PROJECT_CONTEXT.md` (repo root; fallback `$HOME/storage/downloads/`). Its Appendix B: current verified source/build state wins.
3. Read `docs/autonomous/AUTONOMOUS_WORKFLOW.md` and check `docs/autonomous/AUTONOMOUS_STATE.md` for in-progress work to resume.

## Start a task with

```
/minehost-auto <task description>
```

## Non-negotiables

- Protected systems (native JVM launcher, runtime extraction/validation, CMake config, engine launch commands, engine download system): no modifications without a confirmed defect; smallest safe change only.
- Push only via `./tools/push-gated.sh`; monitor CI with `./tools/ci-watch.sh`. Never force-push; never overwrite remote work.
- This Termux device has NO JDK/Gradle/adb: local compile/test/device gates are UNAVAILABLE — say so, rely on CI, keep Runtime Verification `UNVERIFIED` without real device evidence.
- Known baseline CI failure: gradle-wrapper.jar validation at "Set up Gradle" (see AUTONOMOUS_STATE.md). Do not repair without human approval.

## Environment notes

- ripgrep is unavailable on arm64-android: Glob/Grep tools error here — use Bash `grep`, `find`, or Explore agents.
- `jq` missing: use `gh ... --jq` or python3.
