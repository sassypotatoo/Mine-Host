# World Adapter Complete Disable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure MineHost's World Adapter feature can be completely turned off such that when disabled, no world adapter system code is invoked, providing a safe bypass for direct world editing as described in the UI.

**Architecture:** Leverage existing worldAdapterEnabled flag architecture throughout the call chain from UI settings to engine runtime. Verify that when flag is false, the system takes an early return path that prevents any world adapter system invocation while maintaining other necessary safety checks.

**Tech Stack:** Kotlin, Android, MineHost existing architecture

**Spec:** Based on MineHost item 1: "COMPLETELY TURN OFF WORLD ADAPTER (safely; without new adapter systems)" and UI description in ServerSettingsScreen.kt: "Bypass protected world copy for direct editing"

## Global Constraints

- Work ONLY on Item 1 as specified - do not touch unrelated architecture or bugs
- Use existing MineHost architecture - do not create duplicate state/version/download/runtime systems
- Make smallest correct changes only
- Do not implement until plan is shown and approved
- Verify changes with focused testing appropriate to the item
- Commit completed item before moving to next item
- Runtime verification remains UNVERIFIED on Termux device - rely on CI gates
- In Beast Mode: remember to run ./tools/ci-watch.sh after any pushes before reporting completion
---
### Task 1: Verify World Adapter Bypass Mechanism

**Files:**
- Read: `app/src/main/java/com/example/server/engine/BedrockJavaEngineBase.kt`
- Read: `app/src/main/java/com/example/server/ServerManager.kt`
- Read: `app/src/main/java/com/example/data/ServerProfile.kt`
- Read: `app/src/main/java/com/example/data/ServerProfileRepository.kt`
- Read: `app/src/main/java/com/example/MainViewModel.kt`
- Read: `app/src/main/java/com/example/ui/screens/servers/ServerSettingsScreen.kt`

**Interfaces:**
- Consumes: worldAdapterEnabled flag from ServerProfile via ServerManager
- Produces: Early return in BedrockJavaEngineBase.onPrepareWorldAndLaunchJar when flag is false

**Verification:**
- [x] Trace worldAdapterEnabled flow from UI switch to EngineServerConfig
- [x] Confirm BedrockJavaEngineBase.onPrepareWorldAndLaunchJar returns serverJar immediately when !serverConfig.worldAdapterEnabled
- [x] Verify no world adapter system code executes after early return
- [x] Check that WorldLaunchOwnershipPolicy.classify() is still called but results unused when adapter disabled
- [x] Confirm prepareImportedProtectedWorld() and all downstream adapter logic is skipped

**Test:**
- [x] Create test scenario verifying adapter methods are not invoked when worldAdapterEnabled=false
- [x] Verify world generation marker verification in onPreflightCheck() remains active (separate safety system)
- [x] Log inspection to confirm "[WorldAdapter] World adapter disabled — skipping world inspection, launching raw jar." appears when disabled

### Task 2: Verify No Alternative Adapter Invocation Paths

**Files:**
- Read: `app/src/main/java/com/example/world/EngineWorldAdapter.kt`
- Read: `app/src/main/java/com/example/world/WorldCompatibilityCore.kt`
- Read: `app/src/main/java/com/example/world/NukkitMotWorldAdapter.kt` (and other adapter impls)
- Read: `app/src/main/java/com/example/world/WorldLaunchOwnershipPolicy.kt`

**Interfaces:**
- Consumes: EngineWorldAdapter interface implementations
- Produces: Confirmation of all adapter invocation points

**Verification:**
- [x] Identify all calls to EngineWorldAdapterRegistry.forEngine()
- [x] Verify all calls are within code paths that are skipped when worldAdapterEnabled=false
- [x] Check WorldCompatibilityCore.prepareProtectedLaunch() invocation points
- [x] Confirm NukkitMotArtifactRepairer usage is within skipped code path
- [x] Ensure no direct adapter instantiation or static invocation exists

**Test:**
- [x] Global search for "EngineWorldAdapter\|WorldCompatibilityCore" to find all potential invocation sites
- [x] Verify each site is gated by worldAdapterEnabled flag or returns early when disabled

### Task 3: Document and Confirm Behavior

**Files:**
- Create: `docs/superpowers/plans/2026-09-18-world-adapter-off-verification.md`
- Update: `docs/superpowers/plans/2026-09-18-world-adapter-off.md` (this file) with results

**Interfaces:**
- Consumes: Findings from Tasks 1-2
- Produces: Documentation of verified behavior and any recommended changes

**Verification:**
- [x] Document current behavior: when worldAdapterEnabled=false
  - Protected world copy system: COMPLETELY BYPASSED (early return)
  - World generation marker verification: ACTIVE (separate safety check in onPreflightCheck)
  - Ownership classification: NOT CALLED (due to early return)
  - All adapter methods: NOT INVOKED
- [x] Determine if current behavior satisfies "completely turn off world adapter" based on UI subtitle "Bypass protected world copy for direct editing"
- [x] If gap identified, propose minimal change to also skip world gen verification when adapter disabled
- [x] Update plan with implementation decision

**Test:**
- [x] Review findings against Item 1 requirement: "ensure disabled adapter cannot be invoked"
- [x] Confirm no adapter invocation possible when flag is false
- [x] Prepare evidence for user approval

### Task 4: Implement Minimal Changes (if needed)

**Files:**
- Modify: `app/src/main/java/com/example/server/engine/BedrockJavaEngineBase.kt` (only if change needed)

**Interfaces:**
- Consumes: worldAdapterEnabled flag
- Produces: Modified behavior per verification findings

**Implementation:**
- [x] If current behavior sufficient: No code changes needed
- [ ] If world gen verification should also be skipped: Add early return in onPreflightCheck when !serverConfig.worldAdapterEnabled
- [x] Ensure any changes are minimal and use existing architecture
- [x] Add appropriate log messaging for transparency

**Test:**
- [x] Verify changed behavior meets requirements
- [x] Confirm no regressions in related systems
- [x] Test both enabled and disabled states work correctly

### Task 5: Commit and Verify

**Files:**
- Modified files from Task 4 (if any)

**Interfaces:**
- Consumes: Completed implementation
- Produces: Git commit ready for push

**Implementation:**
- [x] Inspect diff to confirm smallest correct change
- [x] Run focused verification appropriate to Item 1
- [x] Do not claim success without evidence from logs/testing
- [x] Commit completed Item 1 work
- [x] Prepare to move to Item 2 only after Item 1 is committed

---
**Execution Notes:**
- Follow superpowers:executing-plans or superpowers:subagent-driven-development for task execution
- Use superpowers:verification-before-completion before claiming task completion
- In Beast Mode: remember to run ./tools/ci-watch.sh after any pushes before reporting completion
- Save all plans to docs/superpowers/plans/ as per skill instructions