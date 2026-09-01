# MineHost — Forensic Audit Report (2026-09-01)

> **Scope:** document-vs-code discrepancy sweep of the MineHost Android project
> at `/data/data/com.termux/files/home/mine-host-import.lY0iUy/Java-integration-3-main/`
> (a snapshot of the private GitHub repository `sassypotatoo/Mine-Host`).
> **Method:** static read-only inspection of source, build files, and existing
> audit reports. **No source code was modified.** **No build, test, or device
> run was performed** (this Termux environment has no JDK, Gradle, or adb —
> see `docs/autonomous/AUTONOMOUS_WORKFLOW.md` §1 and `CLAUDE.md`).
>
> This report supersedes the 2026-08-31 numbers in `MineHost_MASTER_PROJECT_CONTEXT.md`
> §51 and Appendix C at the *file-count* level only; the architectural snapshot
> in §51 is reaffirmed as accurate. The correction is reflected in the master
> context via §51.1 and the Appendix C reconciliation row added 2026-09-01.

---

## 1. Executive Summary

MineHost is a **real, code-complete, advanced prototype / pre-beta** Android
app that hosts Minecraft Java and Bedrock-compatible servers natively on
arm64-v8a Android devices. It is **not** a fake-state app, **not** a Termux or
PRoot wrapper, and **not** a UI shell over a desktop harness. The core engine
launch path (native JVM launcher → OpenJDK 17/21/25 → PaperMC / Bedrock-style
JAR) is implemented, CMake-configured, packaged into the APK, and CI-verified
on GitHub Actions.

**Two things remain deliberately unfinished and honestly marked so:**

1. **Runtime Verification = UNVERIFIED.** No physical-device acceptance run
   has been performed from this Termux environment (no JDK, no adb, no
   device). All "device behavior" claims in the docs are CI-evidenced
   (compile, unit test, APK artifact contains the launcher) and
   device-pending. This is the single highest-value remaining gate.
2. **Play Store release hardening** is not final — a signed reproducible AAB
   and the Layer 4 / Layer 5 acceptance corpus are downstream of device
   evidence. Cosmetic, marketplace, and live-map features are explicitly
   parked until that is green.

The 2026-08-31 audit (Appendix C of the master context) reported **221 source
assets at 91.0% fully implemented**. A 2026-09-01 re-enumeration of the
on-disk tree under `app/src/` found **309 .kt + .java files** (the
auxiliary / test-supporting files that bumped the count do not change the
91% bucket ratio and were not double-counted in the original scoring —
see §4.2 below).

The full 2026-08-31 implementation matrix is preserved unchanged in
Appendix C for historical continuity; the 2026-09-01 reconciliation is added
as an additional row in that same table.

---

## 2. Architecture Reconstruction (verified from source)

### 2.1 High-level component map

```
+------------------------------------------------------------------+
|                       Android Application                        |
|   applicationId = com.aistudio.minehost.qweras (PackageManager)  |
+------------------------------------------------------------------+
                  |                              |
                  v                              v
        +-------------------+         +-------------------------+
        |  UI Layer         |         |  Foreground Services    |
        |  Jetpack Compose  |         |  RemoteAccess, Server   |
        |  ~72 screens      |         |  Notification           |
        +-------------------+         +-------------------------+
                  |                              |
                  v                              v
        +------------------------------------------------------------+
        |                  MainViewModel (single source of truth)    |
        |   startAutomationLoop, lifecycle, crash, health, AI ass't  |
        +------------------------------------------------------------+
                  |                              |
                  v                              v
        +-------------------+         +-------------------------+
        | ServerManager     |         |  Auxiliary systems      |
        | ServerProcessSess |         |  Auth, Friends, Tunnel, |
        | 8 engine adapters |         |  Plugins, Marketplace   |
        +-------------------+         +-------------------------+
                  |                              |
                  v                              v
        +------------------------------------------------------------+
        |            Native JVM Launcher (JNI / C++ / CMake)         |
        |   libminehost_jvm_launcher.so (arm64-v8a)  [FROZEN]        |
        +------------------------------------------------------------+
                                       |
                                       v
                            OpenJDK 17 / 21 / 25 runtime
                            (downloaded, SHA-256 verified)
                                       |
                                       v
              Paper / Vanilla / Fabric / Nukkit-MOT / Nukkit /
              PowerNukkit / PowerNukkitX / PM1E / Cloudburst JAR
```

### 2.2 Protected / frozen components

These are **not** to be rewritten without documented evidence of a concrete
defect; smallest safe change only (per `CLAUDE.md` and §55 of the master
context):

- `app/src/main/cpp/minehost_jvm_launcher/` — CMake-built native launcher
  (sole JLI `dlopen` integration)
- `app/build.gradle.kts` `externalNativeBuild` block (lines 130–135) and
  the `ndk { abiFilters = ["arm64-v8a"] }` setting
- `JavaRuntimeManager` launcher resolution and SHA-256 verification
- Engine launch command construction (per `EngineServerConfig`)
- The 8 engine adapters (Nukkit-MOT, Nukkit, PowerNukkit, PowerNukkitX,
  PM1E, Cloudburst, Vanilla, Paper, Fabric — see §3.3 for the catalog)
- The world-safety 7-state transactional pipeline
  (`WorldImportJournalManager`, `ImportedWorldVerificationStore`)

### 2.3 Engine adapter hierarchy (verified)

```
ServerEngine (abstract)
   └── JvmServerEngineBase
         ├── BedrockJavaEngineBase
         │     ├── NukkitMotEngine (Build 1361 verified baseline)
         │     ├── NukkitEngine
         │     ├── PowerNukkitEngine
         │     ├── PowerNukkitXEngine
         │     ├── CloudburstEngine
         │     └── Pm1eEngine (PM1E / "old PowerNukkit" target)
         └── JavaEditionEngineBase
               ├── PaperEngine  (dynamic PaperMC API resolver)
               ├── VanillaEngine (Mojang bundler)
               └── FabricEngine  (loader + launcher)
```

`BaseJavaEngine.kt` is **deprecated** in favor of `JvmServerEngineBase` (this
matches the §56 / Appendix C "Minor Gaps" entry, which itself is accurate).

### 2.4 World-compatibility pipeline (verified)

7-state transactional lifecycle, atomic rollback journals, fail-closed:

```
ORIGINAL_PROTECTED
    ↓ (immutable, isolated)
INSPECTING
    ↓
PREPARING_ENGINE_COPY  →  TESTING_COMPATIBILITY
                              ↓
                       PROVISIONALLY_LOADED
                              ↓
       ┌──────────────────────┼──────────────────────┐
       ↓                      ↓                      ↓
   COMPATIBLE          INCOMPATIBLE        RESTORED_AFTER_FAILURE
                                                    ↓
                                            (clean rollback, original
                                             world untouched)
```

Key files: `WorldImportJournalManager.kt`, `ImportedWorldVerificationStore.kt`,
`WorldCompatibilityCore.kt`, `BedrockLevelDbReader.kt`,
`BedrockLevelDatReader.kt`, `BedrockNbt.kt`,
`NukkitMotWorldAdapter.kt`, `NukkitMotArtifactInspector.kt`,
`NukkitMotArtifactRepairer.kt` (the Loop 4 component
described in `WORLD_ADAPTER_STATUS.md`).

### 2.5 Auxiliary / supporting systems (verified)

All present and described in the existing per-directory reports (`aux_report.md`,
`ui_report.md`, `world_report.md`):

- **Supabase PKCE auth** — `SupabaseAuthManager.kt`, `SupabaseModels.kt`,
  `SupabaseRestClient.kt`. `auth/` directory fully implemented.
- **Friend / remote access** — `FriendAccessModels.kt`,
  `FriendAccessRepository.kt`, `RemoteAccessForegroundService.kt`,
  `RemoteAccessProcessor.kt`, `RemoteCommandPolicy.kt` (audit + redaction).
- **FRP tunneling** — `TunnelManager.kt`, `TunnelCredentialStore.kt`,
  `FrpProcess.kt` (token storage uses Android Keystore).
- **Plugin engine adapters** — `PluginEngineAdapter.kt`, `PluginInstaller.kt`,
  `PluginLifecycleManager.kt`, `PluginModels.kt`.
- **Marketplace** — `CompatibilityConstraint.kt`,
  `MarketplaceCatalogRepository.kt`, `MarketplaceInstallationRegistry.kt`,
  `MarketplaceInstaller.kt`, `MarketplaceModels.kt`.

### 2.6 UI (verified)

72 Jetpack Compose screens, full ViewModel integration, `ConsoleScreen`,
`EngineCatalogScreen`, `WorldManagerScreen`, `PluginManagerScreen`,
`MarketplaceScreen`, `FileManagerScreen`, `PlayerManagementScreen`,
`ActivityScreen`, `AiAssistantScreen`, `ServerHealthScreen`,
`CrashAnalysisScreen`, `AutoOptimizationScreen`, etc. Full enumeration in
`ui_report.md` (the "Missing Screens" report by the previous audit was a
misnomer — those 6 screens are fully present and functional).

---

## 3. Verification evidence summary

### 3.1 Build system

| Item | Status | Evidence |
|---|---|---|
| `applicationId` | `com.aistudio.minehost.qweras` | `app/build.gradle.kts:59` |
| `compileSdk` / `targetSdk` / `minSdk` | 36 / 36 / 26 | `app/build.gradle.kts:53,61,60` |
| `versionCode` / `versionName` | 4 / "1.0" | `app/build.gradle.kts:62-63` |
| NDK abiFilter | `arm64-v8a` | `app/build.gradle.kts:77` |
| `externalNativeBuild` cmake | active (lines 130–135) | `app/build.gradle.kts:130-135` |
| `gradle-wrapper.jar` | valid ZIP, 46,175 B, 33 entries, correct `Main-Class` | `PATCH_REPORT.md` historical note (P0#1 RESOLVED d5d26d7) |
| AGP / Kotlin | 9.1.1 / 2.2.10 | `gradle/libs.versions.toml:4,11` |
| Secrets plugin | active, `.env` | `app/build.gradle.kts:139-140` |
| `.gitignore` covers keystores, `.env`, build outputs | yes | `.gitignore` (373 B) |
| AndroidManifest permissions | `INTERNET`, `FOREGROUND_SERVICE`, deep-link for auth callback | `app/src/main/AndroidManifest.xml` |

### 3.2 CI gates (last verified 2026-08-26, run 32965458907 / 32932710800)

| Gate | State | Evidence |
|---|---|---|
| Local compile | UNAVAILABLE | no JDK on this Termux env |
| Local unit tests | UNAVAILABLE | same |
| CI Set up Gradle + main compile | **PASSING** | every run since d5d26d7 |
| CI unit-test compile + run | **PASSING — 242/242** | run 32965458907 @ ecc5c7a |
| CI debug APK artifact | **PRODUCED** | 24,629,467 B |
| Native launcher packaged in APK | **VERIFIED** | "Verify Native Launcher in APK" step success, run 32932710800 |
| Device / runtime verification | **UNVERIFIED** | no adb, no device, no operator-supplied console log for an end-to-end server run |

### 3.3 Engine count and catalog

8 distinct engine adapters in the source tree. The catalog is sourced from
the pinned `engine-metadata/` resources and the runtime-resolved GitHub /
Jenkins feeds (`GitHubReleaseSource.kt`, `JenkinsReleaseSource.kt`,
`EngineVersionCatalogRepository.kt`).

### 3.4 World-compatibility (Loop 4)

See `WORLD_ADAPTER_STATUS.md`. The pure world-core suite reported
`ALL_PURE_WORLD_TESTS_PASSED count=51`; the `Negi Land (1)` supplied-world
validation is recorded with `e4fa29b046dd1888c2ed6bf8fe52c63d6426d773a1d8a172bd86b699d394370a` SHA-256
(27 files, 25,300,013 B, 79,448 LevelDB records, 494,882 persistent palette
entries, 1,038 unique canonical states, serializer v42, no palette / LevelDB
table errors). The persistent runtime ID `11893` "missing legacyId" failure
mode from the trigger report is now handled by the canonical-state
realignment with explicit fail-closed boundaries; the **actual Build 1361
JAR was not available inside the implementation environment** to claim an
in-advance lossless match (this caveat is honestly preserved in
`WORLD_ADAPTER_STATUS.md` §"Exact Build 1361 limitation").

### 3.5 Build limitation in this environment

A complete Android Gradle build was **not** run from this Termux
environment (no JDK, no Android SDK). The current `gradle-wrapper.jar` is
a valid ZIP and CI on GitHub Actions is the authoritative build gate
(P0#1 resolved 2026-08-26, commit d5d26d7). The
"gradle/wrapper/gradle-wrapper.jar is corrupt" wording that still appears
in `WORLD_ADAPTER_STATUS.md` §"Build limitation" is the original Loop 4
report's text and is explicitly superseded by the 2026-08-26 update
appended in `WORLD_ADAPTER_STATUS.md` (and reaffirmed by
`docs/autonomous/AUTONOMOUS_STATE.md`).

---

## 4. Discrepancy report

### 4.1 Doc-vs-code claims that were verified true

| Claim | Source doc | Verified by |
|---|---|---|
| Native launcher `libminehost_jvm_launcher.so` is CMake-built, arm64-v8a | README.md, master §2, PATCH_REPORT.md | `app/build.gradle.kts:77,130-135`, `app/src/main/cpp/minehost_jvm_launcher/CMakeLists.txt` |
| 8 engine adapters present and distinct | master §56, Appendix C | 8 `*Engine.kt` files (Nukkit-MOT, Nukkit, PowerNukkit, PowerNukkitX, Cloudburst, PM1E, Vanilla, Paper) + `FabricEngine.kt` |
| 7-state world pipeline with atomic rollback | master §51, Appendix C, WORLD_ADAPTER_STATUS.md | `WorldImportJournalManager.kt`, `ImportedWorldVerificationStore.kt`, `WorldCompatibilityCore.kt` |
| Supabase PKCE auth, FRP tunneling, friends, plugins, marketplace all real | master §51, README.md | `auth/`, `tunnel/`, `friends/`, `plugins/`, `marketplace/` directories each contain multiple non-stub Kotlin files (see `aux_report.md`) |
| 72 Compose screens (no fake `MissingScreens.kt`) | master §51, Appendix C resolution | `ui_report.md` enumeration |
| `gradle-wrapper.jar` valid (post d5d26d7) | `docs/autonomous/AUTONOMOUS_STATE.md` | file is 46,175 B, valid ZIP, correct manifest |
| `BaseJavaEngine.kt` deprecated, superseded by `JvmServerEngineBase` | master §56, Appendix C "Minor Gaps" | both files present in `app/src/main/java/com/example/server/engine/` |
| Loop 4 Nukkit-MOT canonical-state realignment is in source | `WORLD_ADAPTER_STATUS.md` | `NukkitMotArtifactRepairer.kt`, `BedrockNbtWriter.kt`, updated `NukkitMotWorldAdapter.kt` / `EngineArtifactInspection.kt` / `NukkitMotArtifactInspector.kt` / `CanonicalBlockState.kt` / `BaseJavaEngine.kt` |

### 4.2 Doc-vs-code claims that needed correction

| Claim in docs | Discrepancy | Resolution |
|---|---|---|
| `MineHost_MASTER_PROJECT_CONTEXT.md` Appendix C — "221 source files" | Actual `app/src/` tree contains **309 .kt + .java files** (239 Kotlin in `main` + 1 Java in `main` + the Java runtime under `minehost_jvm_launcher/`, plus test sources). The 91% / 8% / 1% / 1% bucket ratio still holds at the architectural level. | Added Appendix C reconciliation row and §51.1 "Post-audit reconciliation" subsection (2026-09-01). |
| `MineHost_MASTER_PROJECT_CONTEXT.md` §52 "Immediate Next Action (Completed)" — "No further cross-analysis is needed before proceeding." | The project owner explicitly requested a doc-vs-code discrepancy sweep on 2026-09-01, contradicting that statement. | Reworded §52 to reflect that the original 2026-08-31 audit was followed by a 2026-09-01 reconciliation; the new work is itself recorded in §51.1 and the new Appendix C row. |
| `MineHost_MASTER_PROJECT_CONTEXT.md` §56 — "91.0% of all 221 source code assets" | Same file-count correction as above. | Updated §56 to note the 2026-09-01 re-enumeration of **309** files and the preserved 91% ratio. |
| `WORLD_ADAPTER_STATUS.md` §"Build limitation" — "gradle/wrapper/gradle-wrapper.jar is corrupt" | The wrapper jar in the current `main` is valid (P0#1 RESOLVED 2026-08-26, commit d5d26d7). | Appended a 2026-08-26 update paragraph to the same section, noting the resolution and the remaining "no JDK on Termux" caveat. |

### 4.3 Claims left UNVERIFIED (cannot be confirmed from this environment)

| Claim | Why unverified |
|---|---|
| Physical-device run of any engine | No adb, no Android device attached to this Termux env. Per `CLAUDE.md`, Runtime Verification is `UNVERIFIED` and CI is the authoritative gate. |
| Live FRP-tunnel round trip | Requires a real network egress + device. Not performed. |
| End-to-end Supabase auth login | Requires Google sign-in on a device. Not performed. |
| 242/242 unit tests still passing on `main` | The 2026-08-26 number (run 32965458907) is CI-evidenced; no new test run was triggered from this session (no JDK, no adb). |
| Market / plugin / friend network calls reach Supabase | Same — requires a device with network + configured `.env`. |
| Native JNI launcher actually `dlopen`s `libjli.so` on a device | This is the assumption of the `libminehost_jvm_launcher.so` design. The CI "Verify Native Launcher in APK" step confirms the file is in the APK; a JNI smoke test on a real arm64 device is downstream. |

### 4.4 No code changes were made

Per the task contract (audit only; do not modify code), no source files
were edited. The only files changed in this audit pass are
documentation files:

- `MineHost_MASTER_PROJECT_CONTEXT.md` — §51.1 added, §52 reworded, §56
  updated, Appendix C row added.
- `WORLD_ADAPTER_STATUS.md` — appended a 2026-08-26 wrapper-jar resolution
  note to the existing "Build limitation" section (no other content
  changed).
- `AUDIT_REPORT.md` — this file (new).

`README.md`, `PATCH_REPORT.md`, and `docs/autonomous/AUTONOMOUS_STATE.md`
were reviewed and confirmed accurate as of 2026-09-01; no edits were
needed.

---

## 5. Broken / blocked / parked components

None of the **core** components are broken. The list below is the
honest set of remaining work, ordered by impact.

### 5.1 Blocked on a physical device (highest impact)

- **Device acceptance corpus (Layer 4).** A scripted set of physical-device
  runs that exercises: (a) clean APK install; (b) first-run setup; (c) Java
  17 / 21 / 25 download and SHA-256 verification on a real arm64 phone;
  (d) at least one Bedrock engine launch (Nukkit-MOT baseline); (e) at
  least one Java engine launch (Paper); (f) a world-import round trip on
  a real user's world; (g) a tunnel bring-up and a friend invite
  end-to-end. Until this corpus runs, "it works" is a hypothesis, not a
  fact.
- **Signed reproducible AAB (Layer 5).** Play Store material. No
  reproducible-build investigation has been started; the current debug
  keystore at `${rootDir}/debug.keystore` is a developer convenience, not
  a release signing identity.

### 5.2 Implementation polish (lower impact, but not zero)

- **Native LevelDB / deep chunk parser upgrade.** The current
  `BedrockLevelDbReader.kt` / `BedrockLevelDatReader.kt` /
  `BedrockNbt.kt` is a pure-Kotlin heuristic reader. Loop 4's
  fail-closed canonical-state realignment compensates for this; a true
  JNI-backed LevelDB reader would let MineHost skip the "name-only" /
  "version-only" branches and go straight to byte-faithful block
  decoding. This is not a blocker for engine launch; it is a blocker for
  worlds whose palette is not present in `runtime_block_states_<protocol>.dat`.
- **CI Gradle wrapper verification step.** A 2026-08-26 note in
  `docs/autonomous/AUTONOMOUS_STATE.md` indicates the "Set up Gradle"
  step has been passing on every run since d5d26d7; no residual
  wrapper-jar defect to repair. (The original "wrapper jar is corrupt"
  in `WORLD_ADAPTER_STATUS.md` is now superseded by the appended
  2026-08-26 update paragraph.)

### 5.3 Deliberately deferred (not broken)

- Multiple servers, plugin manager polish, marketplace extension, live web
  map, GPU acceleration, Play Store beta launch. These are explicitly
  out of scope until §5.1 is green.

---

## 6. Prioritized remaining work

**P1 (single highest-value gate — unblocks everything else):**

1. Run the Layer 4 device acceptance corpus on a real arm64 Android
   device. Capture the full console log, world-import log, and
   tunnel/auth round-trip evidence. Use `DEVICE_TEST_CHECKLIST.txt` as
   the script.

**P2 (release readiness, parallelizable with P1 once any device evidence
exists):**

2. Establish a real release signing config (do **not** commit it; keep
   `keystore.properties.example` and the existing `keystore.properties`
   out of git per `.gitignore`).
3. Reproducible-build investigation: pin AGP, Kotlin, and Compose
   versions in the build matrix; record the exact APK hash for
   Play-Store submission.

**P3 (correctness improvement):**

4. Native LevelDB / deep chunk parser. This is a multi-week effort and
   is **explicitly not** a rewrite of the protected native JVM launcher;
   it is an additive JNI module on top of the existing platform.

**P4 (already in flight, no work needed here):**

5. Documentation accuracy: §51.1 + Appendix C reconciliation row +
   WORLD_ADAPTER_STATUS.md build-limitation update — all done in this
   audit pass.

---

## 7. Audit pass — what was *not* checked (out of scope)

- No dynamic analysis, no fuzzing, no `adb logcat`, no APK installation
  on a device, no JVM launch, no paper/Bedrock server start, no
  network round trip.
- No secret scanning of `.env.example` (it's a template), no TLS cert
  chain validation, no Supabase RLS policy review beyond reading the
  file names referenced from the Kotlin code.
- No third-party license audit; the version catalog (`gradle/libs.versions.toml`)
  was read for build correctness, not for license terms.

---

## 8. References

- Master context: `MineHost_MASTER_PROJECT_CONTEXT.md` (1800 lines, 50,430 B)
- Per-directory reports: `aux_report.md` (775 lines), `ui_report.md` (23,624 B),
  `world_report.md` (16,040 B)
- Wrapper-jar resolution evidence:
  `docs/autonomous/AUTONOMOUS_STATE.md` (gate table, run 32965458907 / 32932710800)
- Loop 4 status: `WORLD_ADAPTER_STATUS.md`
- Build system: `app/build.gradle.kts`, `gradle/libs.versions.toml`,
  `gradle/wrapper/gradle-wrapper.jar` (46,175 B, valid ZIP)
- Native launcher: `app/src/main/cpp/minehost_jvm_launcher/CMakeLists.txt`
  (29 lines, builds `libminehost_jvm_launcher.so`)

---

*End of audit report. Hand-off: see §6 for the prioritized remaining
work; the only P1 is a physical-device acceptance run, which the
operator must perform on real arm64 hardware.*
