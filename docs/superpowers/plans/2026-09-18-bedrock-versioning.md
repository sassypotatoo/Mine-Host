# Bedrock Server Versioning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure MineHost has proper Bedrock server versioning with real catalog/versioning, correct downloads, validation, and persistence.

**Architecture:** Leverage existing versioning architecture (CatalogRepository, VersionStep, EngineStep, CreateServerWizardViewModel) to ensure proper Bedrock version selection, validation, and persistence throughout the server creation flow.

**Tech Stack:** Kotlin, Android, MineHost existing architecture

**Spec:** Based on MineHost item 2: "PROPER BEDROCK SERVER VERSIONING (real catalog/versioning; correct downloads/validation/persistence)"

## Global Constraints

- Work ONLY on Item 2 as specified - do not touch unrelated architecture or bugs
- Use existing MineHost architecture - do not create duplicate state/version/download/runtime systems
- Make smallest correct changes only
- Do not implement until plan is shown and approved
- Verify changes with focused testing appropriate to the item
- Commit completed item before moving to next item
- Runtime verification remains UNVERIFIED on Termux device - rely on CI gates
- In Beast Mode: run ci-watch.sh after every push before reporting completion

---
### Task 1: Analyze Current Bedrock Versioning Implementation

**Files:**
- Read: `app/src/main/java/com/example/ui/servercreation/steps/VersionStep.kt`
- Read: `app/src/main/java/com/example/ui/servercreation/steps/EngineStep.kt`
- Read: `app/src/main/java/com/example/ui/servercreation/CreateServerWizardViewModel.kt`
- Read: `app/src/main/java/com/example/server/version/EngineVersionCatalogRepository.kt`
- Read: `app/src/main/java/com/example/server/version/BedrockVersionOption.kt`
- Read: `app/src/main/java/com/example/server/version/EngineVersion.kt`
- Read: `app/src/main/java/com/example/data/ServerProfile.kt`

**Interfaces:**
- Consumes: Bedrock version selection from UI
- Produces: Validated and persisted Bedrock version in ServerProfile

**Verification:**
- [ ] Trace Bedrock version flow from VersionStep → EngineStep → ViewModel → Profile persistence
- [ ] Confirm VERSION step comes before ENGINE step in wizard flow
- [ ] Verify EngineStep properly filters templates using `isEngineCompatibleWithVersion`
- [ ] Check that selected Bedrock version is correctly passed to ServerProfile
- [ ] Validate that version catalog provides accurate Bedrock version options
- [ ] Confirm version persistence works correctly (saved/loaded profiles)
- [ ] Check for any version validation gaps (AUTO handling, compatibility modes)

**Test:**
- [ ] Create test scenarios for different Bedrock version selection flows
- [ ] Verify version persistence across app restarts
- [ ] Check compatibility mode handling (SINGLE_VERSION vs MULTI_VERSION)
- [ ] Validate AUTO version selection logic
- [ ] Test version validation rejects incompatible combinations

### Task 2: Identify Versioning Issues and Gaps

**Files:**
- Read: `app/src/test/java/com/example/server/version/BedrockVersioningTest.kt` (if exists)
- Read: `app/src/main/java/com/example/server/version/EngineCompatibilityValidator.kt`
- Review: Version-related error handling and validation

**Interfaces:**
- Consumes: Findings from Task 1
- Produces: List of versioning issues requiring fixes

**Verification:**
- [ ] Check if version validation properly handles all compatibility modes
- [ ] Verify AUTO version selection works correctly for MULTI_VERSION engines
- [ ] Confirm that version persistence doesn't lose Bedrock version data
- [ ] Look for any hardcoded version values or missing validation
- [ ] Check download/validation integration with version selection
- [ ] Verify version options shown to user are accurate and complete

**Test:**
- [ ] Test edge cases: empty version lists, network failures, invalid versions
- [ ] Verify version downgrade/upgrade paths work correctly
- [ ] Check that version validation prevents incompatible engine/version pairs

### Task 3: Implement Versioning Fixes

**Files:**
- Modify: Based on findings from Tasks 1-2 (likely ViewModel, VersionStep, EngineStep, or version validation)

**Interfaces:**
- Consumes: Identified versioning issues
- Produces: Corrected versioning behavior

**Implementation:**
- [ ] Fix any version selection/persistence bugs
- [ ] Improve version validation if needed
- [ ] Ensure proper AUTO version handling
- [ ] Fix any version filtering issues in EngineStep
- [ ] Make sure version catalog integration works correctly
- [ ] Ensure downloads use correct versions based on selection

**Test:**
- [ ] Verify fixes resolve identified issues
- [ ] Confirm no regressions in existing versioning functionality
- [ ] Test both BEDROCK and JAVA edition version flows

### Task 4: Document and Confirm Behavior

**Files:**
- Create: `docs/superpowers/plans/2026-09-18-bedrock-versioning-verification.md`
- Update: `docs/superpowers/plans/2026-09-18-bedrock-versioning.md` (this file) with results

**Interfaces:**
- Consumes: Findings from Tasks 1-3
- Produces: Documentation of verified behavior and changes made

**Verification:**
- [ ] Document current behavior after fixes
- [ ] Confirm VERSION step properly precedes ENGINE step
- [ ] Verify EngineStep filtering works correctly with selected version
- [ ] Check that version persistence works round-trip
- [ ] Validate version selection prevents incompatible combinations

**Test:**
- [ ] Review findings against Item 2 requirement: "real catalog/versioning; correct downloads/validation/persistence"
- [ ] Confirm all versioning aspects work correctly
- [ ] Prepare evidence for user approval

### Task 5: Commit and Verify

**Files:**
- Modified files from Task 3

**Interfaces:**
- Consumes: Completed implementation
- Produces: Git commit ready for push

**Implementation:**
- [ ] Inspect diff to confirm smallest correct change
- [ ] Run focused verification appropriate to Item 2
- [ ] Do not claim success without evidence from logs/testing
- [ ] Commit completed Item 2 work
- [ ] Prepare to move to Item 3 only after Item 2 is committed

---
**Execution Notes:**
- Follow superpowers:executing-plans or superpowers:subagent-driven-development for task execution
- Use superpowers:verification-before-completion before claiming task completion
- In Beast Mode: remember to run ./tools/ci-watch.sh after any pushes before reporting completion
- Save all plans to docs/superpowers/plans/ as per skill instructions