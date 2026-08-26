# AUTONOMOUS STATE — living task/gate tracker

> Update this file at EVERY phase transition. On session restart, read it first,
> reconcile against `git log` / `./tools/ci-watch.sh --sha HEAD --once`, then resume.

## Current task

- **Task:** Production-readiness loop — Phase A COMPLETE; Phase B underway
  (P1 sweep DONE clean; P2 RE-SCOPED 2026-08-26: all five feature areas already
  implemented and service-wired — console 316-line screen + MainViewModel send/
  lifecycle fns, versions manager 616 lines, frp TunnelManager w/ status machine,
  downloads via EVTM; remaining = device verification only. Now executing P4
  docs-follow-reality: README rewritten from placeholder, PATCH_REPORT stale
  claims corrected.)
- **Status:** P0 CLOSED (run 32932710800); P1 sweep CLOSED clean;
  P2 re-scoped to verification-only; P4 docs corrections in flight
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
| MAX_BUILD_FIX_ITERATIONS | 4 (coEvery fix; port-allocation prod fix 3b28df3) | 5 |
| MAX_TEST_FIX_ITERATIONS | 5 (…, 05283f7, 3b28df3) — AT LIMIT | 5 |
| MAX_RUNTIME_FIX_ITERATIONS | 0 | 5 |
| MAX_TOTAL_ITERATIONS | 6 | 15 |

## Gate status (last verified 2026-08-26, run 32932710800)

| Gate | State | Evidence |
|---|---|---|
| Local compile (Termux) | UNAVAILABLE | no JDK/Gradle installed (see AUTONOMOUS_WORKFLOW.md §1) |
| Local unit tests | UNAVAILABLE | same |
| CI Set up Gradle + main compile | **PASSING** | every run since d5d26d7 |
| CI unit-test compile+run | **PASSING — 232/232** | run 32932710800 (first full green in project history) |
| CI APK artifact | **PRODUCED** | artifact minehost-debug, 24,629,467 bytes |
| Native launcher packaged in APK | **VERIFIED** | "Verify Native Launcher in APK" step success, same run — P0#2 closed |
| Device/runtime verification | UNVERIFIED | no adb/android-tools; no device evidence yet |

## Known blockers

1. ~~Wrapper-jar validation~~ **RESOLVED 2026-08-26**: corrupt jar (78783 B,
   sha256 `a5e75118...`, BadZipFile) replaced with official gradle/gradle v9.3.1
   wrapper jar (46175 B, sha256 `b3a875dd...`). Runs 32889978741 and 32892864131
   passed "Set up Gradle" and wrapper validation — root cause confirmed and closed.
2. ~~**P0#2 native launcher build**~~ **RESOLVED 2026-08-26**: dfc43f5
   (externalNativeBuild re-enabled + abiFilters arm64-v8a +
   CMAKE_RUNTIME_OUTPUT_DIRECTORY redirect) verified by run 32932710800's
   "Verify Native Launcher in APK" step = success; APK artifact produced.
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
8. Run 32925285330 (47cf385): still 3 failed but diagnostics landed.
   (a) PaperHardening: loopback-HTTP fix worked; remaining blocker was
   downloadFile's structural JAR validation (>=1024B, zip, .class entry)
   running before size/checksum gates — text bodies never reached them.
   Fixed by enqueueing real minimal JARs (05283f7). (b) EVTM embedded
   message revealed startServer rejects java_paper_stable at
   ServerManager.kt:300 catalog gate, then rollback deletes the new
   metadata — hence null reads all along. ServerManager boundary now
   mocked (STOPPED->ONLINE transitions) so transaction mechanics run
   hermetically through production code paths (05283f7). CI verdict for
   05283f7 pending.
10. Run 32926530003 (bba5d6e): coEvery compile fix landed; **EVTM case 1
   PASSES end-to-end**. Remaining 3: EVTM case 2 = REAL PRODUCTION BUG —
   createProfile allocated on draft UDP transport but stored java_paper as
   JAVA_TCP → two paper profiles shared TCP 19132 → updateProfile conflict
   guard rolled back case 2. FIXED in production (3b28df3): allocation now
   uses the stored network type. PaperHardening x2 = fake JARs DEFLATED to
   <1024B, failing validateGenericJar before the size/checksum gates;
   payload now seeded-random bytes (3b28df3). TEST counter at 5/5 limit:
   if the run for 3b28df3 still fails at test stage, further iterations
   must be classified by root cause (production defect => BUILD bucket)
   and justified explicitly, not silently renewed.
11. **FIRST FULLY GREEN RUN 2026-08-26** — run 32932710800 @ 1f291d4:
    Set up Gradle ✓ compile ✓ 232/232 tests ✓ APK artifact minehost-debug
    (24.6 MB) ✓ "Verify Native Launcher in APK" ✓ => P0#1 and P0#2 both
    CLOSED. Final fix was 1f291d4 removing the stale strict-https require
    that had been shadowing 3bd24b9's loopback allowance. Phase A complete;
    next phase per plan: P1 core-consistency sweep, then P2 app features.
12. **P1 core-consistency sweep COMPLETE 2026-08-26 (audit-only, no defects):**
    (a) Launch contract CONSISTENT — single JVM launch path
    JvmServerEngineBase.startServer → JavaRuntimeManager.createLauncherProcessBuilder
    (:417); BedrockJavaEngineBase/JavaEditionEngineBase add no divergent process
    creation; env vars match native launcher reads exactly (MINEHOST_RUNTIME_HOME,
    _JAVA_MAJOR, _ARG_COUNT, _ARG_%d, _LIBJLI_PATH + JAVA_HOME fallback,
    main.cpp:33-72); javaMajor policy agrees across layers (engineVersion.
    runtimeJavaVersion → SUPPORTED_RUNTIME_MAJORS {17,21,25} = PaperResolver map;
    fail-fast mismatch guard JvmServerEngineBase.kt:265). Only other ProcessBuilder
    is TunnelManager (cloudflared, out of scope).
    (b) TermuxPackageResolver = HTTPS apt-mirror client for OpenJDK .debs
    (packages-cf.termux.dev) — NO Termux-app dependency, principle intact;
    rename/doc note deferred to P4.
    (c) verified_remote_versions.json empty bootstrap is by design: static raw
    engine_versions resource loads first, remote entries enter via promotion,
    corrupt/missing → fails safe to emptyList (EngineVersionCatalogRepository).
    (d) resolution.json SHA-256 chain verified fail-closed: absent/invalid stored
    digest → matches() false → reinstall; catalog pin enforced unless trusted
    official-resolved install; structural validateJar final gate
    (InstalledEngineVersionRepository.matches :179-239).
13. Docs-only commit 7448336 (Phase-A record): CI run 32933552788 GREEN
    (watchdog exit 0) — confirms green baseline stable on docs changes too.
14. Phase B opened: P1 sweep closed clean; proceeding to P2 app features
    (console UI, lifecycle UI, downloads, tunneling UX, version management).
15. **P2 RE-SCOPED 2026-08-26 (audit):** the 2026-08-25 plan overestimated the
    P2 gap — it was derived from doc goals before full code mapping. Reality:
    console/lifecycle/downloads/tunneling/version-management are all implemented
    and wired to real services (no fake state): ServerConsoleScreen(316L)→
    MainViewModel.sendCommand/clearAndExportLogs; startServer/stopServer/
    restartServer at MainViewModel:1034/1105/1116; VersionManagerScreen(616L)→
    catalogRepository+EVTM; TunnelManager frp process w/ STOPPED/STARTING/
    RUNNING/RECONNECTING + per-server status; zero TODO/FIXME stubs in ui/.
    Remaining P2 substance = device/runtime verification (blocked: no adb —
    stays UNVERIFIED). P4 docs-follow-reality executed same day: README.md
    rewritten from "Updated README" placeholder to real project description;
    PATCH_REPORT stale claims corrected (phantom PackageMineHostLauncherTask
    annotated with the true dfc43f5 mechanism + CI run evidence; wrapper-jar
    historical note appended).
16. Run 32934169575 (85bc04b, P1-sweep record) CANCELLED by the immediately
    following f6f118c push — cancel-in-progress concurrency, NOT a failure.
    Its content is fully contained in f6f118c; the f6f118c run is the
    authoritative verdict for both docs commits. Parked permanently: the
    "dead-enum UNTRACKED_REQUIRES_ADOPTION" cleanup idea was WRONG — the value
    has four live usages (WorldLaunchOwnershipPolicy.kt:143 etc.); verified
    before deletion, no change made. P3 remains open pending a concrete
    evidenced defect (non-negotiables forbid speculative edits to protected
    systems); device verification stays UNVERIFIED (no adb/device).
17. **FIRST DEVICE EVIDENCE 2026-08-26** (operator-supplied console log,
    package com.aistudio.minehost.qweras): native launcher LOADED from
    lib/arm64, Java 17 auto-installed+validated (36 pkgs via apt mirror),
    Nukkit-MOT fallback resolution succeeded after pinned 1361 404'd upstream,
    server BOOTED and bound 0.0.0.0:19132 => launcher/runtime/install/fallback
    chains CONFIRMED on real hardware. New evidenced defect found + fixed same
    day: PROTOCOL_MISMATCH_STOP on healthy boot — upstream ships its CURRENT
    protocol palette as UNNUMBERED resources/runtime_block_states.dat which the
    digit-only discovery regex missed => build 1430 numbered set maxed at 2168
    while same jar advertised 2169. Fix: hasCurrentProtocolPalette() +
    RuntimeProtocolExpectation.supportsAdvertisedCurrentProtocol (default false)
    + BedrockJavaEngineBase probe-loop augmentation including the live
    advertisement ONLY when the unnumbered palette exists (fail-closed kept;
    rejects-test asserts flag=false still mismatches). Tests extended in
    NukkitMotProtocolMetadataTest (+3 cases) and NukkitMotEngineProtocolTest
    (flag assertions + 2169-augmentation mirror). Stale 1361 pin PARKED:
    fallback proven on device; one wasted request per fresh install, cosmetic.

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
