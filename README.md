# MineHost

MineHost is a standalone Android app that hosts **real Minecraft servers** on-device:
Java Edition (PaperMC) and Bedrock-style dedicated engines, running natively on arm64.
No PRoot, no chroot, no Termux app required.

## How it works

- A bundled native JVM launcher (`libminehost_jvm_launcher.so`, built via CMake/NDK)
  starts an OpenJDK runtime installed on demand (Java 17 / 21 / 25, arm64).
- Server engines are downloaded at runtime from official sources only
  (PaperMC fill API, verified catalogs), pinned by SHA-256 and size checks.
- Launch configuration is passed to the launcher through a documented
  environment-variable contract (`MINEHOST_RUNTIME_HOME`, `MINEHOST_JAVA_MAJOR`,
  `MINEHOST_ARG_COUNT`, `MINEHOST_ARG_*`, `MINEHOST_LIBJLI_PATH`).

## Features

- Multi-server profiles (Java Paper, Bedrock-compatible engines)
- Live console with command input, log filtering, export
- Lifecycle control (start / stop / restart) with health monitoring
- Player tracking from real console events
- Backups, file manager, plugin manager, world tools
- Play tunnels (frp-based) for joining over the internet
- Version manager with verified-catalog updates

## Build

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
```

CI (`.github/workflows/android.yml`) compiles, runs the unit-test suite,
assembles the debug APK, and verifies the native launcher is packaged inside it.

## Status

Unit-tested on CI; device/runtime behavior is **UNVERIFIED until tested on a real
arm64 Android device**. See `PATCH_REPORT.md` (with corrections) and
`docs/autonomous/AUTONOMOUS_STATE.md` for verified build history.
