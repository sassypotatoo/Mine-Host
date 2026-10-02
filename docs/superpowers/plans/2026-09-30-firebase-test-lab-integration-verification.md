# Firebase Test Lab Integration Verification & Validation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify and validate the existing Firebase Test Lab implementation to ensure it's production-ready and meets all 22 specified requirements without consuming Firebase quota during review.

**Architecture:** The Firebase Test Lab implementation appears to be already complete in `/tools/run-firebase-test-lab.sh`. This plan focuses on systematic verification of each requirement through static analysis, local exercising without Firebase submission, and validation of failure paths/provenance/fake state handling. A preflight real Firebase run will be conducted but stopped BEFORE actual Matrix submission to avoid quota consumption.

**Tech Stack:** Bash, gcloud, gsutil, Firebase Test Lab, GitHub Actions, APK validation tools (aapt/apk analyzer), Beast Mode state management

**Spec:** /docs/superpowers/specs/2026-09-30-firebase-test-lab-integration-design.md

## Global Constraints

- Must verify implementation against 22 specific requirements provided by user
- Must not consume Firebase Test Lab quota during verification/validation
- Must validate Beast Mode state updates are atomic and correct
- Must verify provenance tracking includes all required execution/context details
- Must ensure failure paths do not fabricate success indicators
- Must confirm proper shell scripting practices (quoting, path handling, temp files, avoiding eval)
- Must validate GCS permissions/handling for result artifact retrieval
- Must confirm proper state transition handling (PASS/FAIL/ERROR/INCONCLUSIVE/timeout)

## Review Focus

- Input validation for malformed APK URLs or missing GitHub artifacts
- Error handling when Firebase Test Lab API is unreachable or returns unexpected responses
- Validation of ARM64-v8a native ABI detection logic
- Package name validation for "com.aistudio.minehost.qweras"
- SDK version validation against Firebase Test Lab requirements
- Device selection logic for ARM virtual devices and API level filtering
- Matrix creation and submission ID capture persistence
- Results polling with exponential backoff implementation
- Artifact download verification from Google Cloud Storage
- Atomic updates to `.claude/beastmode_state.json`
- Failure path handling that does not mark fabrications as success
- Provenance tracking completeness (GitHub workflow/run/commit SHA, APK SHA-256, device/API level, Firebase terminal state)
- Beast Mode command wrapper integration
- Restartability and idempotency of the implementation
- Proper cleanup of temporary files and resources
- Logging and error handling completeness
- Retry mechanisms with exponential backoff
- Prerequisite validation (gh, gcloud, python3, FIREBASE_TESTLAB_BUCKET)
- APK discovery from GitHub Actions workflow
- Shell security: no eval usage, proper quoting, path handling

---