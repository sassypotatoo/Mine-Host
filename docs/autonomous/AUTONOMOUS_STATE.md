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
| MAX_BUILD_FIX_ITERATIONS | 2 | 5 |
| MAX_TEST_FIX_ITERATIONS | 3 (56e7d90 + 3bd24b9) | 5 |
| MAX_RUNTIME_FIX_ITERATIONS | 0 | 5 |
| MAX_TOTAL_ITERATIONS | 5 | 15 |

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
   wrapper jar (46175 B, sha256 `b3a875dd...`). Runs 32889978741 and 32892864131
   passed "Set up Gradle" and wrapper validation — root cause confirmed and closed.
2. **P0#2 native launcher build** — pushed dfc43f5 (externalNativeBuild
   re-enabled + abiFilters arm64-v8a + CMAKE_RUNTIME_OUTPUT_DIRECTORY redirect;
   AGP packages only artifacts under the library output dir). Verification run
   was auto-cancelled by a later push before reaching assembleDebug; must be
   re-verified on the next green-through-tests run ("Verify Native Launcher
   in APK" step).
3. **Test-suite deadlock ROOT-CAUSED 2026-08-26** (was misread as slow
   Robolectric): BedrockEngineHardeningTest >
   staleProcessExitStillRunsItsCleanup STARTED, never completed (runs
   32892864131: 46+ min silent; 32897642446: same signature). Cause:
   JvmServerEngineBase.handleProcessExit does withContext(Dispatchers.Main);
   Robolectric runs tests ON the main-looper thread, so the Main task queues
   behind the blocked runTest/runBlocking thread forever; neither looper nor
   coroutine timeout can preempt. Fix: shared MainDispatcherRule
   (UnconfinedTestDispatcher) applied to the five engine/manager test classes.
4. Watchdog tooling: ci-watch.sh rewritten progress-aware (step transitions +
   best-effort live-log tails; REST live logs are BlobNotFound mid-step —
   known endpoint limitation; INVESTIGATING at 20m without observable change,
   never auto-cancels). Gradle Test tasks get a hard 35m timeout so a future
   hang fails fast with per-class started-events in the log.
5. P1 findings parked: TermuxPackageResolver is the OpenJDK .deb acquisition
   path (apt mirror over HTTPS; no Termux app dependency — principle intact,
   document reality later); verified_remote_versions.json ships empty
   (`{"catalogVersion": 1, "versions": []}`) by design pending runtime resolution.
6. **13 test failures ROOT-CAUSED + fixed in 56e7d90** (run 32903468852:
   232 tests / 13 failed, first full execution ever): (a) NukkitMot protocol
   regex anchoring bug (matchEntire + "(?:^|/)" cannot consume dir prefixes);
   (b) InstalledEngineVersionRepositoryTest ran on plain JVM where org.json is
   a throwing stub — write() swallowed the exception and always returned
   false → now Robolectric; (c) PaperHardeningTest reflection override of
   PaperResolver.projectUrl never worked (instance field of object;
   set(null) throws) → tests hit real fill.papermc.io → direct internal-set
   assignment; (d) PrepareServerCoordinator gained injectable runtimePreparer
   (default = JavaRuntimeManager.ensureRuntimeReady); (e) EVTM createProfile
   bypasses catalog gating (java_paper only enters catalog via promotion);
   (f) two stale expectations updated (jenkins-build:1361;
   EXTERNAL_ADOPTION_REQUIRED). Pushed; CI verdict pending.
7. Run 32924184430 (56e7d90): 232 tests / **3 failed** (was 13) — Robolectric,
   regex, coordinator-seam, determinism and ownership fixes all CONFIRMED by
   CI. Remaining: PaperHardening x2 (validatePaperArtifactUrl demanded https
   for every artifact URL; MockWebServer is loopback http → resolution failed
   before size/checksum checks; fixed with loopback scheme exception mirroring
   isTrustedPaperHost's localhost allowance, 3bd24b9) and EVTM metadata-flow
   (install() fails pre-write, reason only println'd which log-failed drops;
   test now raises AssertionError embedding install()'s message, 3bd24b9).

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
- 2026-08-26 — Wrapper fix d5d26d7: CI passed Set up Gradle + main compile for the
  first time (run 32889978741), then failed at :app:compileDebugUnitTestKotlin
  (stale catalog.refresh call) -> fixed b42b3c1. ci-watch hardened vs per-SHA
  index lag (64514ae). Native launcher build re-enabled + per-test streaming
  logging pushed dfc43f5. Runs 32892864131/32897642446 stalled silently in test
  step at BedrockEngineHardeningTest > staleProcessExitStillRunsItsCleanup;
  root cause identified as Dispatchers.Main deadlock under Robolectric (no
  setMain override anywhere). Fixed via shared MainDispatcherRule in five test
  classes; Gradle Test task timeout 35m added; ci-watch.sh rewritten into a
  progress-aware watchdog (20m no-observable-progress INVESTIGATING, never
  auto-cancels; REST live logs confirmed BlobNotFound mid-step — endpoint
  limitation, fallback = step transitions).
- 2026-08-26 — Test-failure triage (run 32903468852) completed with full
  root-cause analysis per cluster; fixes pushed as 56e7d90 (2 production
  changes: protocol regex + coordinator runtime seam; 6 test corrections).
  Counters: TEST=2/5, TOTAL=4/15. Awaiting CI verdict; on green, verify
  "Verify Native Launcher in APK" step for the owed P0#2 evidence.
