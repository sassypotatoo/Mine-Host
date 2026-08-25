# AUTONOMOUS STATE — living task/gate tracker

> Update this file at EVERY phase transition. On session restart, read it first,
> reconcile against `git log` / `./tools/ci-watch.sh --sha HEAD --once`, then resume.

## Current task

- **Task:** Production-readiness loop — Phase A: P0 blockers
- **Status:** IN_PROGRESS (2026-08-25)
- **Plan (P0–P4, audit-derived 2026-08-25):**
  - **P0#1 corrupt gradle-wrapper.jar** — sha256 `a5e75118...` (78783 B), BadZipFile
    confirmed locally. Fix: fetch official wrapper jar for v9.3.1 from
    gradle/gradle tag v9.3.1, verify zip integrity, replace, gated push, monitor.
  - **P0#2 native launcher absent from build** — externalNativeBuild cmake block is
    COMMENTED OUT in app/build.gradle.kts (~lines 116–121); zero .so files in repo;
    `PackageMineHostLauncherTask` claimed in PATCH_REPORT.md does NOT exist anywhere
    (grep across *.kts/*.kt/*.gradle = no match) => PATCH_REPORT claim stale. APK will
    lack lib/arm64-v8a/libminehost_jvm_launcher.so => CI verify script fails AND
    JavaRuntimeManager (strict launcher requirement) cannot launch any server. Fix:
    re-enable cmake block as smallest change; iterate on CI evidence (NDK/cmake
    availability under AGP 9).
  - **P1** broken-core: JavaRuntimeManager/PaperEngine/BedrockJavaEngineBase launch-
    contract consistency vs launcher env vars; TermuxPackageResolver.kt contradicts
    documented no-Termux principle (investigate usage, remove/gate); empty
    verified_remote_versions.json catalog bootstrap; engine-metadata resolution.json
    requires actual artifact SHA-256 at runtime.
  - **P2** app features per master context (console, lifecycle UI, downloads, tunneling,
    version management UX), P3 reliability/error-handling polish, P4 docs-follow-reality
    (README empty; PATCH_REPORT corrections).

## Iteration counters (reset per task)

| Counter | Used | Limit |
|---|---|---|
| MAX_BUILD_FIX_ITERATIONS | 1 | 5 |
| MAX_TEST_FIX_ITERATIONS | 1 | 5 |
| MAX_RUNTIME_FIX_ITERATIONS | 0 | 5 |
| MAX_TOTAL_ITERATIONS | 2 | 15 |

## Gate status (last verified 2026-08-26)

| Gate | State | Evidence |
|---|---|---|
| Local compile (Termux) | UNAVAILABLE | no JDK/Gradle installed (see AUTONOMOUS_WORKFLOW.md §1) |
| Local unit tests | UNAVAILABLE | same |
| CI Set up Gradle + main compile | **PASSING** | run 32889978741: official v9.3.1 wrapper jar accepted; :app:compileDebugKotlin succeeded (first green stages ever) |
| CI unit-test compile+run | FAILING | run 32889978741: `:app:compileDebugUnitTestKotlin` — EngineVersionTransactionManagerTest.kt:32 called nonexistent `catalog.refresh(false)`; fixed in b42b3c1 (awaiting CI) |
| CI APK artifact | NOT PRODUCED | blocked by test stage above |
| Device/runtime verification | UNVERIFIED | no adb/android-tools; no device evidence yet |

## Known blockers

1. ~~Wrapper-jar validation~~ **RESOLVED 2026-08-26**: corrupt jar (78783 B,
   sha256 `a5e75118...`, BadZipFile) replaced with official gradle/gradle v9.3.1
   wrapper jar (46175 B, sha256 `b3a875dd...`). Run 32889978741 passed
   "Set up Gradle" and Kotlin compilation — root cause confirmed and closed.
2. **P0#2 native launcher absent from build (NEXT).** externalNativeBuild cmake
   block commented out in app/build.gradle.kts (~116–121); zero .so files in repo;
   PATCH_REPORT's `PackageMineHostLauncherTask` does not exist anywhere. Even once
   tests pass, assembleDebug will produce an APK without
   lib/arm64-v8a/libminehost_jvm_launcher.so → verify_native_launcher_apk.sh fails
   and JavaRuntimeManager cannot launch any server. Plan: re-enable cmake block,
   iterate on CI NDK/cmake evidence.
3. ci-watch.sh per-SHA index lag worked around client-side (64514ae).

## History (append-only, newest last)

- 2026-08-25 — Audited environment (gh auth OK; no JDK/adb/rg). Created autonomous
  infrastructure (.claude command/settings, docs/autonomous/, tools scripts, CLAUDE.md,
  context doc copied to root). Validated scripts against known-failed run 32850125139
  before committing. Baseline CI failure documented above.
- 2026-08-25 — Commit feb3bdb (infrastructure only) pushed via tools/push-gated.sh;
  CI run 32874750765 monitored via tools/ci-watch.sh -> FAIL at "Set up Gradle",
  identical wrapper checksum `a5e75118...`. Change cannot have caused it (no build
  inputs touched) => BASELINE BLOCKER reconfirmed on new HEAD. Fix iterations used: 0
  (repair requires human approval). Hard STOP recorded per protocol.
- 2026-08-25 — POLICY UPDATE by operator: full-autonomy mandate. Edit denies on
  formerly-protected paths (cpp/, gradle/wrapper/, JavaRuntimeInstaller/Manager,
  JvmServerEngineBase) removed from project settings; those systems are now
  EVIDENCE-GATED instead of hands-off. Successful completed tasks auto-commit and
  auto-push via gate without asking confirmation. Safety boundaries unchanged:
  no force-push/--force-with-lease, no remote branch deletion, no reset --hard,
  no clean -fd, no amend/published-history rewrite, gated push only, retry limits
  5/5/5/15 hard, UNVERIFIED device honesty intact.
