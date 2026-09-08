# MineHost — Session Orientation (auto-loaded)

MineHost: standalone Android app hosting real Minecraft Java/Bedrock servers on-device.
No fake state, no PRoot/Termux dependency in the product, protected systems stay frozen.

## Non-negotiables

- Formerly-protected systems (native JVM launcher, runtime extraction/validation, CMake config, engine launch commands, engine download system): modify only with documented evidence of a concrete defect; smallest safe change only.
- Push only via `./tools/push-gated.sh`; monitor CI with `./tools/ci-watch.sh`. Never force-push; never overwrite remote work.
- This Termux device has NO JDK/Gradle/adb: local compile/test/device gates are UNAVAILABLE — say so, rely on CI, keep Runtime Verification `UNVERIFIED` without real device evidence.
- Known baseline CI failure: gradle-wrapper.jar validation at "Set up Gradle". Autonomous repair is authorized once CI evidence confirms root cause; retry limits still hard.
- Completed successful tasks are committed and pushed through `tools/push-gated.sh` automatically — do not ask the operator for routine confirmation (standing instruction 2026-08-25).

## Environment notes

- ripgrep is unavailable on arm64-android: Glob/Grep tools error here — use Bash `grep`, `find`, or Explore agents.
- `jq` missing: use `gh ... --jq` or python3.
