# Autonomous Deep Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Autonomously audit and fix items 1-12 listed in the user's request, making smallest correct changes using existing MineHost architecture, preserving working code, and verifying fixes where possible without requiring device/emulator access. Items requiring runtime/device verification will be marked as BLOCKED.

**Architecture:** Leverage existing MineHost architecture and codebase. For each item, inspect the current implementation, identify the root cause of any issues, and apply minimal fixes. Preserve all working code and avoid architectural rewrites. Use the existing dependency injection, version catalogs, and server management systems.

**Tech Stack:** Kotlin, Android, MineHost existing architecture, Gradle, CMake (for native JVM launcher).

**Spec:** Based on the user's message dated 2026-09-27 containing items 1-12 for autonomous deep audit.

## Global Constraints

- Work ONLY on items 1-12 as specified - do not touch unrelated architecture or bugs
- Use existing MineHost architecture - do not create duplicate state/version/download/runtime systems
- Make smallest correct changes only
- Do not implement until plan is shown and approved (but we are executing autonomously after plan creation)
- Verify changes with focused testing appropriate to the item (unit tests, inspection, CI where possible)
- Commit completed item before moving to next item
- Runtime verification remains UNVERIFIED on Termux device - rely on CI gates
- In Beast Mode: run ci-watch.sh after every push before reporting completion

---
### Task 1: COMPLETELY TURN OFF WORLD ADAPTER

**Files:**
- Modify: `app/src/main/java/com/example/server/ServerManager.kt:458` (set worldAdapterEnabled = false)
- Modify: `app/src/main/java/com/example/data/ServerProfile.kt:59` (change default to false)
- Modify: `app/src/main/java/com/example/data/ServerProfile.kt:134` (change default to false)
- Modify: `app/src/main/java/com/example/data/MineHostModels.kt:117` (change default to false)
- Modify: `app/src/main/java/com/example/data/ServerProfileRepository.kt:509` (change default to false)
- Modify: `app/src/main/java/com/example/server/engine/EngineServerConfig.kt:19` (change default to false)

**Interfaces:**
- Consumes: ServerProfile.worldAdapterEnabled from UI and repository
- Produces: EngineServerConfig.worldAdapterEnabled = false to engine

- [ ] **Step 1: Verify current worldAdapterEnabled defaults and usage**
    - Inspect all files where worldAdapterEnabled is defined or used
    - Confirm that setting to false disables the adapter (early return in BedrockJavaEngineBase.kt)

- [ ] **Step 2: Change default values to false**
    - Update ServerProfile.kt default constructor parameter
    - Update ServerProfile.kt copy constructor parameter
    - Update MineHostModels.kt default
    - Update ServerProfileRepository.kt default from JSON
    - Update EngineServerConfig.kt default
    - Keep ServerManager.kt hardcoded false (already)

- [ ] **Step 3: Verify that no other code forces it to true**
    - Search for any other assignments or hardcoded true values

- [ ] **Step 4: Run related unit tests (if any) to ensure no regression**
    - Check for tests involving worldAdapterEnabled

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "feat: completely turn off world adapter by default"

---
### Task 2: PROPER BEDROCK SERVER VERSIONING

**Files:**
- Inspect: `app/src/main/java/com/example/server/version/EngineVersionCatalogRepository.kt`
- Inspect: `app/src/main/java/com/example/server/version/EngineVersion.kt`
- Inspect: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt`
- Inspect: `app/src/main/java/com/example/ui/screens/servers/ServerCreationScreen.kt` (or wizard steps)
- Possibly modify: `app/src/main/java/com/example/server/version/EngineVersionCatalogRepository.kt` if needed
- Possibly modify: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` if needed

**Interfaces:**
- Consumes: Bedrock version metadata from catalog/sources
- Produces: Resolved engine version for server creation

- [ ] **Step 1: Analyze current Bedrock versioning implementation**
    - Check how bedrockVersion is stored in ServerProfile
    - Check how EngineVersionCatalogRepository resolves Bedrock versions
    - Check how EngineUpdateRepository fetches Bedrock releases
    - Check how ServerCreation wizard uses bedrockVersion

- [ ] **Step 2: Identify any issues (e.g., incorrect version mapping, missing validation)**
    - Look for hardcoded version strings
    - Check if supportedBedrockVersions are properly used
    - Verify that selectedBedrockVersion is used to fetch correct engine artifact

- [ ] **Step 3: Make smallest correct changes to ensure real catalog/versioning**
    - Ensure that Bedrock versions are fetched from real upstream (e.g., Jenkins, GitHub) as per existing sources
    - Ensure that version metadata (like versionName, displayName) is correct
    - Ensure that compatibility information is used

- [ ] **Step 4: Verify that changes do not break Java edition**
    - Ensure that Java engine versioning remains intact

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "feat: proper Bedrock server versioning"

---
### Task 3: SERVER FILE DOWNLOAD PERFORMANCE

**Files:**
- Inspect: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt`
- Inspect: `app/src/main/java/com/example/server/updates/GitHubReleaseSource.kt`
- Inspect: `app/src/main/java/com/example/server/updates/JenkinsReleaseSource.kt`
- Inspect: `app/src/main/java/com/example/server/updates/CompatibilityParser.kt`
- Inspect: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` for caching
- Possibly modify: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` to add jitter to backoff
- Possibly modify: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` to improve caching

**Interfaces:**
- Consumes: HTTP responses from release sources
- Produces: Cached engine artifacts

- [ ] **Step 1: Analyze current download implementation**
    - Check for repeated downloads, missing caching, inefficient buffering
    - Check HTTP behavior (redirects, retries, dependency retrieval)

- [ ] **Step 2: Identify performance issues**
    - Look for missing cache checks before download
    - Look for serial work that could be parallelized
    - Look for lack of jitter in exponential backoff (Item 10 mentions API retry improvement)

- [ ] **Step 3: Implement smallest correct performance improvements**
    - Ensure that cached artifacts are checked before download
    - Ensure that HTTP client uses connection pooling (if not already)
    - Add jitter to exponential backoff in downloadFile (if not present)
    - Ensure that extracted runtime is validated and not re-extracted unnecessarily

- [ ] **Step 4: Verify that changes do not break existing functionality**
    - Ensure that downloads still succeed and artifacts are valid

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "perf: improve server file download performance"

---
### Task 4: CLOUDBURST NUKKIT "SERVER OUTDATED"

**Files:**
- Inspect: `app/src/main/java/com/example/server/engine/CloudburstEngine.kt`
- Inspect: `app/src/main/java/com/example/server/version/NukkitMotProtocolMetadata.kt` (if used)
- Inspect: `app/src/main/java/com/example/server/version/EngineVersion.kt` for Bedrock version options
- Inspect: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` for Cloudburst source
- Possibly modify: `app/src/main/java/com/example/server/engine/CloudburstEngine.kt` to adjust protocol version
- Possibly modify: `app/src/main/java/com/example/server/version/NukkitMotProtocolMetadata.kt` if needed

**Interfaces:**
- Consumes: Cloudburst Nukkit artifact and version metadata
- Produces: Server that reports correct version to client

- [ ] **Step 1: Analyze current Cloudburst Nukkit implementation**
    - Check the version of Cloudburst Nukkit being downloaded
    - Check the protocol version it reports
    - Check if there is a version mismatch causing "server outdated"

- [ ] **Step 2: Identify if the issue is in version selection or protocol handling**
    - Verify that the selected Cloudburst version supports the expected Bedrock protocol
    - Check if the engine reports the correct protocol version to the client

- [ ] **Step 3: Implement smallest correct fix**
    - If the issue is version selection, ensure that a compatible version is chosen from the catalog
    - If the issue is protocol reporting, adjust the engine to report the correct version
    - Do not fake versions or suppress errors without evidence

- [ ] **Step 4: Verify that the fix does not break compatibility with clients**
    - Ensure that the server can still be started and clients can connect (source-level)

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "fix: Cloudburst Nukkit server outdated issue"

---
### Task 5: OPENJDK EXTRACTION PERFORMANCE

**Files:**
- Inspect: `app/src/main/java/com/example/server/JavaRuntimeInstaller.kt`
- Inspect: `app/src/main/java/com/example/server/JavaRuntimeManager.kt`
- Inspect: `app/src/main/java/com/example/server/termux_deb_cache` related code
- Possibly modify: `app/src/main/java/com/example/server/JavaRuntimeInstaller.kt` to optimize extraction
- Possibly modify: `app/src/main/java/com/example/server/JavaRuntimeManager.kt` to avoid redundant extraction

**Interfaces:**
- Consumes: Termux DEB packages for OpenJDK
- Produces: Extracted Java runtime

- [ ] **Step 1: Analyze current OpenJDK extraction implementation**
    - Check for repeated extraction, unnecessary copying, inefficient I/O
    - Check validation of extracted runtime
    - Check temporary locations and cleanup

- [ ] **Step 2: Identify performance issues**
    - Look for extraction when cached valid runtime exists
    - Look for unnecessary file copying during extraction
    - Look for lack of validation of extracted files

- [ ] **Step 3: Implement smallest correct performance improvements**
    - Ensure that valid cached Java installations are not extracted again
    - Ensure that extraction only happens when necessary
    - Ensure that extracted runtime is validated (check for required files)
    - Ensure that temporary files are cleaned up

- [ ] **Step 4: Verify that changes do not break runtime validity**
    - Ensure that the extracted runtime can still launch Java processes

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "perf: improve OpenJDK extraction performance"

---
### Task 6: UNNESSARY DOWNLOADED LIBRARIES

**Files:**
- Inspect: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` for list of sources
- Inspect: `app/src/main/java/com/example/server/updates/GitHubReleaseSource.kt` and similar for what they download
- Inspect: `app/src/main/java/com/example/server/engine/` for what engines are actually used
- Possibly modify: `app/src/main/java/com/example/server/updates/EngineUpdateRepository.kt` to remove unnecessary sources
- Possibly modify: `app/src/main/java/com/example/server/updates/GitHubReleaseSource.kt` if it downloads unnecessary dependencies

**Interfaces:**
- Consumes: Release metadata from sources
- Produces: Engine artifacts (JARs, native libraries)

- [ ] **Step 1: Analyze what libraries/dependencies are downloaded**
    - List all sources in EngineUpdateRepository
    - Check what each source downloads (e.g., GitHubReleaseSource downloads assets)
    - Check if any downloaded assets are not used by any engine

- [ ] **Step 2: Identify unnecessary downloads**
    - Check if any engine does not require certain assets (e.g., documentation, source code)
    - Check if any downloaded libraries are not required for runtime or extraction

- [ ] **Step 3: Remove unnecessary downloads**
    - Modify sources to only download necessary assets (if possible without breaking engine functionality)
    - If a source downloads a bundle and we only need part, see if we can filter post-download
    - Do not remove dependencies that are required for any engine (Java, Bedrock, etc.)

- [ ] **Step 4: Verify that engines still have all required files**
    - Ensure that each engine's required JAR, native libraries, etc. are still downloaded

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "refactor: remove unnecessary downloaded libraries"

---
### Task 7: PAPER SERVER FAILS START

**Files:**
- Inspect: `app/src/main/java/com/example/server/engine/PaperEngine.kt` (if exists) or `BaseJavaEngine.kt`
- Inspect: `app/src/main/java/com/example/server/engine/JavaEditionEngineBase.kt`
- Inspect: `app/src/main/java/com/example/server/engine/JvmServerEngineBase.kt`
- Possibly modify: `app/src/main/java/com/example/server/engine/JvmServerEngineBase.kt` to handle JNA/OSHI loading
- Possibly modify: `app/src/main/java/com/example/server/engine/BaseJavaEngine.kt` to set up library path

**Interfaces:**
- Consumes: PaperMC JAR and its dependencies (JNA, OSHI)
- Produces: Java server process

- [ ] **Step 1: Analyze current Paper server startup failure**
    - Check if PaperEngine or JavaEditionEngineBase sets up native library path for JNA/OSHI
    - Check if the native library (libjnidispatch.so) is packaged in the APK or extracted
    - Check if the error is due to missing native library in resource path

- [ ] **Step 2: Identify if the issue is packaging or runtime library loading**
    - Verify that the PaperMC bundle includes JNA/OSHI native libraries for Android
    - Check if the app's native library path is set correctly before loading JNA

- [ ] **Step 3: Implement smallest correct fix**
    - If the issue is missing native library in APK, ensure that the PaperMC artifact used includes it or that we package it separately
    - If the issue is library path, ensure that the native library directory is added to java.library.path before starting the server
    - Do not suppress errors or fake native libraries

- [ ] **Step 4: Verify that the fix does not break other Java engines**
    - Ensure that Fabric, Vanilla, etc. still work

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "fix: Paper server fails to start due to JNA/OSHI native library"

---
### Task 8: POWERNUKKITX PORT MISMATCH

**Files:**
- Inspect: `app/src/main/java/com/example/server/engine/PowerNukkitXEngine.kt` (or `PowerNukkitXWorldAdapter.kt`)
- Inspect: `app/src/main/java/com/example/server/engine/BedrockJavaEngineBase.kt` for port handling
- Inspect: `app/src/main/java/com/example/server/ServerManager.kt` for how port is passed to engine
- Inspect: `app/src/main/java/com/example/data/ServerProfile.kt` for port storage
- Possibly modify: `app/src/main/java/com/example/server/engine/PowerNukkitXEngine.kt` to ensure port is used correctly
- Possibly modify: `app/src/main/java/com/example/server/engine/BedrockJavaEngineBase.kt` if it overrides port

**Interfaces:**
- Consumes: ServerProfile.port
- Produces: Server process bound to that port

- [ ] **Step 1: Analyze current PowerNukkitX port handling**
    - Check how the port from ServerProfile is passed to the engine
    - Check if the engine uses that port for server.properties or command line
    - Check if the engine or adapter overrides the port elsewhere
    - Check if the readiness probe checks the same port

- [ ] **Step 2: Identify any mismatch**
    - Look for hardcoded port values in PowerNukkitXEngine or adapter
    - Look for server.properties being written with a different port
    - Look for the engine ignoring the configured port

- [ ] **Step 3: Implement smallest correct fix**
    - Ensure that the configured port is used consistently in server.properties, command line, and readiness checks
    - Ensure that different server instances can use different ports
    - Ensure that engine defaults do not silently overwrite the selected port

- [ ] **Step 4: Verify that port propagation works**
    - Check that the server starts on the configured port
    - Check that the UI shows the same port

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "fix: PowerNukkitX port mismatch"

---
### Task 9: LIGHT THEME

**Files:**
- Inspect: `app/src/main/java/com/example/ui/theme/` (if exists) or `app/src/main/java/com/example/ui/theme.kt`
- Inspect: `app/src/main/java/com/example/ui/MaterialTheme.kt` (if using Material Design)
- Inspect: `app/src/main/java/com/example/ui/screens/` for hardcoded colors
- Possibly modify: theme files to define light theme colors
- Possibly modify: screen components to use theme attributes instead of hardcoded colors

**Interfaces:**
- Consumes: Theme attributes (colors, elevations, etc.)
- Produces: UI colors for light and dark themes

- [ ] **Step 1: Analyze current theme implementation**
    - Check if there is a light theme defined or if only dark theme exists
    - Check if UI components use theme attributes or hardcoded colors
    - Verify that dark theme remains functional

- [ ] **Step 2: Identify any missing or incorrect light theme definitions**
    - Look for hardcoded dark colors that should be theme attributes
    - Check if light theme is missing entirely or if colors are incorrect

- [ ] **Step 3: Implement smallest correct light theme**
    - Define light theme colors in the theme file (if using custom theme) or in MaterialTheme
    - Ensure that UI components use theme attributes (e.g., colorPrimary, colorSurface) instead of hardcoded values
    - Only modify what is incomplete; do not redesign entire UI

- [ ] **Step 4: Verify that dark theme still works and light theme is coherent**
    - Ensure that text remains readable, surfaces distinguishable, etc. in both themes

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "feat: implement light theme"

---
### Task 10: WORLD GENERATION / SCREENSHOT VERIFICATION

**Files:**
- Inspect: `app/src/main/java/com/example/world/` for world generation logic
- Inspect: `app/src/main/java/com/example/server/engine/` for world seed and level name usage
- Inspect: `app/src/main/java/com/example/data/ServerProfile.kt` for worldSeed and levelName
- Possibly modify: world generation logic if source-level issues are found (but note Item 1 requires world adapter disabled, so we are looking at raw server world generation)

**Interfaces:**
- Consumes: worldSeed, levelName from ServerProfile
- Produces: Minecraft world data

- [ ] **Step 1: Analyze current world generation implementation**
    - Check how worldSeed and levelName are used to generate worlds
    - Check if the server engine respects the worldSeed and levelName from profile
    - Check if there is any world adapter logic that might be interfering (but note world adapter is off)

- [ ] **Step 2: Compare with screenshots (if available) to see if behavior matches**
    - Note: We cannot run the app, but we can inspect if the code logically should produce the expected world generation
    - If the code uses the seed and level name correctly, then world generation should be correct (assuming the engine itself is correct)

- [ ] **Step 3: Identify any source-level issues**
    - Look for hardcoded world seeds or level names that override the profile
    - Look for missing seed application

- [ ] **Step 4: Implement smallest correct fix if source-level issue is found**
    - Ensure that the worldSeed and levelName from ServerProfile are passed to the engine correctly
    - Ensure that the engine uses them for world generation

- [ ] **Step 5: Since we cannot run on device, we will mark verification as source-level only**
    - If no source-level issues are found, we assume world generation is correct (subject to engine correctness)
    - If we find and fix an issue, we note that it is fixed at source level

- [ ] **Step 6: Commit changes if any**
    - git add modified files
    - git commit -m "fix: world generation uses correct seed and level name"

---
### Task 11: ENGINE BUILD CHANNEL

**Files:**
- Inspect: `app/src/main/java/com/example/server/version/EngineVersion.kt` for channel field
- Inspect: `app/src/main/java/com/example/server/version/EngineVersionCatalogRepository.kt` for how it parses channel
- Inspect: `app/src/main/java/com.example.server/updates/EngineUpdateRepository.kt` for how it filters versions
- Inspect: `app/src/main/java/com.example/server/updates/GitHubReleaseSource.kt` and similar for what they consider as release
- Possibly modify: EngineVersionCatalogRepository.kt to prefer STABLE channels
- Possibly modify: EngineUpdateRepository.kt to filter by channel

**Interfaces:**
- Consumes: Release metadata with channel information
- Produces: EngineVersion objects with channel set

- [ ] **Step 1: Analyze current engine build channel selection**
    - Check how EngineVersion.channel is set from metadata
    - Check if the catalog/repository prefers STABLE, SNAPSHOT, etc.
    - Check if UpdateRepository filters by channel (e.g., only STABLE)

- [ ] **Step 2: Identify if MineHost unintentionally downloads development/beta/pre-release builds**
    - Look for any logic that selects SNAPSHOT or PREVIEW when STABLE is available
    - Check if the catalog includes all channels and if the UI/selection logic picks the wrong one

- [ ] **Step 3: Implement smallest correct changes to ensure release/final/stable builds are used**
    - If the catalog already marks channels correctly, ensure that the selection logic prefers STABLE
    - If the UpdateRepository fetches all channels, add a filter to prefer STABLE unless otherwise specified
    - Do not blindly switch everything to "latest stable" if the user has selected a specific version (respect user choice)

- [ ] **Step 4: Verify that Java edition also uses correct channels**
    - Ensure that Java engine versioning is not affected

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "feat: prefer stable builds for engine channels"

---
### Task 12: SERVER WIZARD ENGINE VERSION DEPENDENCY

**Files:**
- Inspect: `app/src/main/java/com.example.ui/servercreation/steps/EngineStep.kt`
- Inspect: `app/src/main/java/com.example.ui/servercreation/steps/VersionStep.kt`
- Inspect: `app/src/main/java/com.example.ui/servercreation/CreateServerWizardViewModel.kt`
- Possibly modify: CreateServerWizardViewModel.kt to enforce engine selection first
- Possibly modify: VersionStep.kt to disable until engine is selected
- Possibly modify: EngineStep.kt to trigger version reload on engine change

**Interfaces:**
- Consumes: User selection of engine and version
- Produces: ServerProfile with engineId and engineVersionId

- [ ] **Step 1: Analyze current server wizard flow**
    - Check the order of steps in CreateServerWizardViewModel.kt
    - Check if VersionStep.kt is shown before EngineStep.kt
    - Check if the ViewModel loads versions based on selected engine

- [ ] **Step 2: Identify if the version screen appears before engine selection**
    - Check the step order in the ViewModel
    - Check if VersionStep.kt observes engineId and reloads versions when it changes

- [ ] **Step 3: Implement smallest correct fix to enforce dependency**
    - Ensure that EngineStep comes before VersionStep in the wizard
    - Ensure that VersionStep.kt only enables version selection after an engine is selected
    - Ensure that when engine changes, the version list is reloaded and version selection resets

- [ ] **Step 4: Verify that the flow works correctly**
    - Check that selecting an engine updates the available versions
    - Check that going back from version to engine does not stale the version state incorrectly

- [ ] **Step 5: Commit changes**
    - git add modified files
    - git commit -m "fix: server wizard engine version dependency order"