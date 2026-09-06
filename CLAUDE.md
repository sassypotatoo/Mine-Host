# MineHost — Session Orientation (auto-loaded)

MineHost: standalone Android app hosting real Minecraft Java/Bedrock servers on-device.
No fake state, no PRoot/Termux dependency in the product, protected systems stay frozen.

## Start a task with the single canonical entry point

```
/minehost-autonomous <task description>
```

(`/minehost-auto` remains a compatibility alias — new invocations should
use `/minehost-autonomous` / MineHost Autonomous v2.1.)

## Before any implementation task

1. Read `.claude/skills/minehost-autonomous/SKILL.md` (governing workflow: 5/5/5/15 retry limits, hard-stop protocol, mandatory report format).
2. Read `.claude/skills/minehost-autonomous-v2/SKILL.md` (v2.1 dispatch layer: classification, dynamic specialist selection, automatic state/CI bookkeeping).
3. Read `.claude/commands/minehost-autonomous.md` (single canonical command file).
4. Read `MineHost_MASTER_PROJECT_CONTEXT.md` (repo root; fallback `$HOME/storage/downloads/`). Its Appendix B: current verified source/build state wins.
5. Read `docs/autonomous/AUTONOMOUS_WORKFLOW.md` and check `docs/autonomous/AUTONOMOUS_STATE.md` for in-progress work to resume.
6. Discover + read only the task-relevant Markdown in the repo. The repository is the source of truth; if documentation disagrees with code, record the discrepancy in `AUTONOMOUS_STATE.md` under "Discovered issues".

## Non-negotiables

- Formerly-protected systems (native JVM launcher, runtime extraction/validation, CMake config, engine launch commands, engine download system): modify only with documented evidence of a concrete defect; smallest safe change only.
- Push only via `./tools/push-gated.sh`; monitor CI with `./tools/ci-watch.sh` (`--update-state` auto-feeds CI results into `AUTONOMOUS_STATE.md`). Never force-push; never overwrite remote work.
- This Termux device has NO JDK/Gradle/adb: local compile/test/device gates are UNAVAILABLE — say so, rely on CI, keep Runtime Verification `UNVERIFIED` without real device evidence.
- Known baseline CI failure: gradle-wrapper.jar validation at "Set up Gradle" (see AUTONOMOUS_STATE.md). Autonomous repair is authorized once CI evidence confirms root cause; retry limits still hard.
- Completed successful tasks are committed and pushed through `tools/push-gated.sh` automatically — do not ask the operator for routine confirmation (standing instruction 2026-08-25).
- v2.1 semantics: no per-step human approval required inside the loop. Only stop for (a) retry-limit breach, (b) fundamental ambiguity that blocks all planning, (c) operator override.

## Environment notes

- ripgrep is unavailable on arm64-android: Glob/Grep tools error here — use Bash `grep`, `find`, or Explore agents.
- `jq` missing: use `gh ... --jq` or python3.
