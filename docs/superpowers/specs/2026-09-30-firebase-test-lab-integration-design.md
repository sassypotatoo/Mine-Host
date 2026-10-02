# Firebase Test Lab Integration Design

## Overview
This document describes the design for a reliable Firebase Test Lab integration in MineHost Beast Mode that can be triggered explicitly by the user via `/minehost-beastmodefirebase run`. The integration is designed to be robust, maintainable, and to avoid consuming Firebase Test Lab quota during development or repair.

## Architecture
The Firebase Test Lab workflow remains a shell script (`tools/run-firebase-test-lab.sh`) but is refactored into modular functions for better readability and testability. The script interacts with the Beast Mode state to read and write Firebase-specific state.

Key components:
- **Logging and error handling**: Centralized functions for logging to stderr and handling errors with clear messages.
- **APK discovery**: Functions to download APKs from GitHub Actions workflows, with fallback to previously successful builds.
- **APK validation**: Uses Android tooling (`aapt` or `apkanalyzer`) to validate the APK's package name, ABI, and SDK versions.
- **Device selection**: Queries the Firebase device catalog for ARM64-v8a devices and selects the highest API level device meeting the minimum requirement.
- **Matrix creation and polling**: Creates a Firebase Test Lab matrix and polls it until completion, handling all terminal states.
- **Result handling**: Retrieves test results and artifacts from Firebase Test Lab and stores them locally.
- **State management**: Atomically updates the Beast Mode state at key steps to enable recovery from interruptions.
- **Prerequisite validation**: Checks for required tools (gcloud, gsutil, etc.) at startup.

## APK Discovery and Validation
1. **Discovery**:
   - Attempt to download the APK from the current commit's GitHub Actions workflow (if available and successful).
   - If not available, fall back to the last successful workflow that produced an APK (recorded in Beast Mode state or by searching recent workflows).
   - Record the chosen APK's provenance (commit SHA, workflow run ID, artifact ID, SHA-256) in the Beast Mode state.

2. **Validation**:
   - Verify the APK file exists and is not empty.
   - Use `aapt dump badging` to extract:
     - Package name (must match `com.aistudio.minehost.qweras`)
     - Native libraries (must include the configured ABI, default `arm64-v8a`)
     - minSdk and targetSdk (must be at least the configured minimum, default 26)
   - If validation fails, log the specific error and exit with a non-zero code.

## Firebase Device Selection and Matrix Creation
1. **Device Selection**:
   - Query the Firebase device catalog (`gcloud firebase test android models list`) for ARM64-v8a devices.
   - Filter devices by those with `ARM64` ABI and `VIRTUAL` or `PHYSICAL` form factor.
   - Select the device with the highest API level that is at least the minimum required (from configuration).
   - If no suitable device is found, exit with an error.

2. **Matrix Creation**:
   - Create a Firebase Test Lab matrix using `gcloud firebase test android run` with:
     - The selected device model and API level
     - The validated APK
     - Test type (robo, instrumentation, etc.) from configuration
     - Locale, orientation, and timeout from configuration
   - Capture the matrix ID immediately from the command output and store it in the Beast Mode state.

## Matrix Polling and Result Handling
1. **Polling**:
   - Poll the matrix state using `gcloud firebase test android matrices describe` at intervals (starting at 30 seconds, with exponential backoff up to 5 minutes).
   - Continue until the matrix reaches a terminal state: `SUCCESS`, `FAILURE`, `INCONCLUSIVE`, `ERROR`, `SKIPPED`, `TIMEOUT`, or `CANCELLED`.

2. **Result Handling**:
   - For `SUCCESS` or `FAILURE`: Retrieve test results (if any) and store them in a timestamped directory under `.tmp/firebase-test-lab/results/`.
   - For all terminal states: Download available artifacts (logs, screenshots, etc.) using `gsutil` to the same results directory.
   - Update the Beast Mode state with:
     - Outcome (SUCCESS, FAILURE, etc.)
     - Detailed error message (if applicable)
     - Paths to results and artifacts
     - Completion timestamp

## State Management and Error Handling
- **Atomic State Updates**: The Beast Mode state is updated atomically using a temporary file and move operation for each significant step (APK selected, matrix created, matrix completed).
- **Restartability**: On startup, the script checks the Beast Mode state for:
  - A selected APK (if validation passed)
  - A created matrix ID (if matrix creation succeeded)
  - It can then resume from the appropriate step (e.g., if APK is validated but matrix not yet created, proceed to matrix creation).
- **Error Handling**:
  - All external command executions check exit codes and capture stderr.
  - Errors are logged with context (what step failed, what command was run, what the output was).
  - The script exits with a non-zero code on failure, with a clear error message to stderr.
  - Prerequisites (gcloud, gsutil, aapt/apkanalyzer, etc.) are validated at startup.

## Security and Logging
- **Command Injection Prevention**: All external commands are executed using arrays (in bash) or proper argument passing to avoid shell injection.
- **Temporary Files**: Created in a secure directory (`$PROJECT_ROOT/.tmp/firebase-test-lab/`) with restricted permissions and cleaned up appropriately.
- **Credential Handling**: No Firebase credentials are handled directly by the script; it relies on pre-authenticated `gcloud` and `gsutil` commands.
- **Logging**: All informational and error messages go to stderr to avoid contaminating stdout, which can be used for machine-readable output if needed.

## Assumptions and Requirements
- The Firebase project is already set up and authenticated via `gcloud auth application-default login` or similar.
- The required Android build tools (`aapt` or `apkanalyzer`) are available in the environment.
- The MineHost APK is built and available as a GitHub Actions artifact under the name `minehost-debug` (configurable).
- The Beast Mode state file (`$PROJECT_ROOT/.claude/beastmode_state.json`) is used to persist Firebase-specific state.

## Future Improvements
- Rewrite the workflow in Kotlin/Java for better testability and integration with the Beast Mode state machine.
- Add unit tests for the core validation and selection logic.
- Implement more granular polling strategies based on historical completion times.