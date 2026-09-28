# Firebase Test Lab Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Firebase Test Lab into MineHost Beast Mode v4 workflow via an explicit `/minehost-beastmode firebase run` command, downloading the latest successful GitHub Actions APK, testing on Firebase Test Lab, and storing results.

**Architecture:** 
- Extend the existing Beast Mode skill (`.claude/skills/minehost-beastmode/SKILL.md`) to handle a new subcommand `firebase run`.
- Use the existing Beast Mode state system (`.claude/beastmode_state.json`) to track Firebase Test Lab runs.
- Leverage GitHub CLI (`gh`) to download the latest successful workflow run's APK artifact.
- Use Firebase CLI (`gcloud firebase test android`) to submit the APK to Firebase Test Lab with a compatible ARM virtual device.
- Store test results (screenshots, video, logs, metadata) under `.claude/testlab-results/<runId>/`.
- Ensure Firebase Test Lab verification is separate from existing runtime verification and does not trigger automatically during normal Beast Mode runs.

**Tech Stack:** 
- Bash scripting for workflow orchestration
- GitHub CLI (`gh`) for artifact download
- Firebase CLI (`gcloud`) for Test Lab submission
- JSON for state management
- Kotlin/Java for any necessary Android-side adjustments (if needed, but primarily tooling)

**Spec:** N/A (This is an internal tooling feature)

## Global Constraints
- Must not modify protected systems (native JVM launcher, runtime extraction/validation, etc.) without documented evidence.
- Must use `./tools/push-gated.sh` for pushing changes.
- Local compile/test/device gates are unavailable; rely on CI.
- Firebase Test Lab verification must be explicit and not interfere with normal Beast Mode runs.

## Review Focus
- Ensure the new subcommand does not accidentally trigger during normal Beast Mode operations.
- Verify that the downloaded APK is the correct artifact from a successful GitHub Actions run.
- Confirm that Firebase Test Lab results are properly stored and accessible.
- Check that the Beast Mode state is updated correctly with Test Lab run information.
- Validate that the workflow handles errors gracefully (e.g., no successful run, Test Lab failures).

---
### Task 1: Extend Beast Mode Skill to Handle Firebase Subcommand

**Files:**
- Modify: `.claude/skills/minehost-beastmode/SKILL.md`

**Interfaces:**
- Consumes: None
- Produces: Updated skill that recognizes `/minehost-beastmode firebase run` and delegates to a new tool/script.

- [ ] **Step 1: Write the failing test** (Not applicable for skill documentation; we will verify by manual testing)
- [ ] **Step 2: Implement the skill update**
  - Add a new case in the skill's command handling for `firebase run`.
  - The skill should invoke a new script (e.g., `./tools/run-firebase-test-lab.sh`) when this command is triggered.
- [ ] **Step 3: Verify the change**
  - Check that the skill correctly routes the command.
  - Ensure no unintended side effects on existing commands.

### Task 2: Create Firebase Test Lab Runner Tool

**Files:**
- Create: `./tools/run-firebase-test-lab.sh`

**Interfaces:**
- Consumes: None (will read Beast Mode state and GitHub/Firebase environment)
- Produces: Exits with 0 on success, non-zero on failure; updates Beast Mode state and stores results.

- [ ] **Step 1: Write the failing test** (We'll test by running the script and expecting it to fail initially due to missing logic)
- [ ] **Step 2: Implement the script**
  1. Parse arguments (expect `run` subcommand).
  2. Read Beast Mode state to check if Firebase Test Lab is allowed (we'll add a flag in state).
  3. If not allowed, output error and exit.
  4. Use `gh run list` to find the latest successful workflow run that has an APK artifact.
  5. Download the APK artifact using `gh run download`.
  6. Determine a compatible non-deprecated ARM virtual device for Firebase Test Lab (we can use a predefined list or query gcloud).
  7. Submit the APK to Firebase Test Lab using `gcloud firebase test android run`.
  8. Wait for test completion (polling or using asynchronous results).
  9. Collect test results (screenshots, video, logs, etc.) from Firebase Test Lab (using `gcloud` to download results).
  10. Store results in `.claude/testlab-results/<runId>/` where `<runId>` is a timestamp or Firebase Test Lab run ID.
  11. Update Beast Mode state (`.claude/beastmode_state.json`) with the Firebase Test Lab run ID and status.
  12. Handle errors and cleanup.
- [ ] **Step 3: Test the script**
  - Run the script in a controlled environment (maybe using a test GitHub repo) to ensure it works.
  - Verify that the APK is downloaded, submitted, and results are stored.

### Task 3: Update Beast Mode State Schema

**Files:**
- Modify: `.claude/beastmode_state.json` (we will update the schema by adding new fields; the actual JSON is updated at runtime by the script)

**Interfaces:**
- Consumes: Current state
- Produces: Updated state with Firebase Test Lab tracking fields

- [ ] **Step 1: Write the failing test** (We'll check that the script can read and write the new fields)
- [ ] **Step 2: Update the state structure in the script**
  - Add fields under a new `firebaseTestLab` object, e.g.:
    ```json
    {
      "firebaseTestLab": {
        "lastRunId": null,
        "lastStatus": null,
        "timestamp": null
      }
    }
    ```
  - Ensure the script initializes these fields if they don't exist.
- [ ] **Step 3: Verify state updates**
  - After running the Firebase Test Lab script, check that the state file is updated correctly.

### Task 4: Ensure Safety and Isolation

**Files:**
- Modify: `./tools/run-firebase-test-lab.sh` (to include safety checks)

**Interfaces:**
- Consumes: None
- Produces: A script that does not trigger Firebase Test Lab during normal Beast Mode operations.

- [ ] **Step 1: Write the failing test** (We'll test that normal Beast Mode commands do not invoke the Firebase Test Lab script)
- [ ] **Step 2: Implement safety checks**
  - The script should only run when explicitly called via the Beast Mode `firebase run` subcommand.
  - Normal Beast Mode commands (e.g., `/minehost-beastmode status`) should not invoke this script.
  - We can achieve this by having the Beast Mode skill only call the script for the `firebase run` command.
- [ ] **Step 3: Verify isolation**
  - Run a normal Beast Mode command and confirm that the Firebase Test Lab script is not executed.

### Task 5: Document and Finalize

**Files:**
- Create: `docs/superpowers/plans/2026-09-28-firebase-test-lab-integration.md` (this plan)
- Update: Any relevant documentation (e.g., README or Beast Mode documentation) if necessary.

**Interfaces:**
- Consumes: None
- Produces: Updated documentation

- [ ] **Step 1: Write the failing test** (We'll verify that the plan is complete and accurate)
- [ ] **Step 2: Finalize the plan**
  - Ensure all tasks are clearly defined and traceable.
- [ ] **Step 3: Verify the plan against the spec** (The spec is the user's request in the pasted content)
  - Check that the plan addresses all points in the user's request.