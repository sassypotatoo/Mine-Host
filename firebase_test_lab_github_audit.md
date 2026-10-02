# Firebase Test Lab & GitHub Actions Artifacts Audit Report
## MineHost Repository Audit
**Date:** 2026-10-01  
**Scope:** Firebase Test Lab integration, GitHub Actions workflow, artifact handling, and provenance tracking  

---

## 1. GitHub Actions Workflow Analysis

### Workflow File: `.github/workflows/android.yml`

**Configuration Summary:**
- **Name:** Android CI
- **Triggers:** 
  - Push to all branches (`branches: ["**"]`)
  - Pull request to all branches (`branches: ["**"]`)  
  - Manual dispatch (`workflow_dispatch`)
- **Concurrency:** Grouped by `android-ci-${{ github.workflow }}-${{ github.ref }}` with cancel-in-progress enabled
- **Permissions:** Contents read-only
- **Job:** Single build job on `ubuntu-latest` with 60-minute timeout

**Build Environment:**
- JDK 21 (Temurin distribution)
- Gradle setup with wrapper validation and basic caching
- CI environment variable set to "true"

**Build Steps:**
1. Source checkout (`actions/checkout@v4`)
2. JDK 21 setup (`actions/setup-java@v5`)
3. Gradle setup (`gradle/actions/setup-gradle@v6`)
4. Wrapper validation and executable permissions
5. Unit & regression tests (`:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, `:app:testDebugUnitTest`)
6. Debug APK assembly (`:app:assembleDebug` with stacktrace, all warnings, no configuration cache, no parallel)
7. Native launcher verification in APK (`tools/verify_native_launcher_apk.sh`)
8. **Artifact Upload:** Debug APK uploaded as `minehost-debug` (14-day retention, error if no files found)

**Artifact Handling:**
- **Artifact Name:** `minehost-debug`
- **Path:** `app/build/outputs/apk/debug/app-debug.apk`
- **Upload Condition:** `if: success()`
- **Retention:** 14 days
- **Failure Handling:** `if-no-files-found: error`

---

## 2. Firebase Test Lab Integration Analysis

### Integration Script: `tools/run-firebase-test-lab.sh`

**Purpose:** Firebase Test Lab integration for MineHost Beast Mode v4

**Key Functions:**

#### Prerequisites Validation (`validate_prerequisites`)
- Checks for required tools: `gh` (GitHub CLI), `gcloud` (Google Cloud SDK), `python3`
- Validates Firebase Test Lab bucket configuration via `FIREBASE_TESTLAB_BUCKET` environment variable
- Normalizes bucket URI (strips `gs://` prefix, removes trailing slash)
- Verifies bucket access using `gsutil` or `gcloud storage`

#### Artifact Discovery (`get_latest_apk_run`)
- Fetches latest successful GitHub Actions run for "Android CI" workflow
- Fallback: Any successful run with APK-like workflow names
- Returns JSON with `run_id` and `commit_sha`
- Uses retry mechanism (3 attempts with exponential backoff)

#### Artifact Identification (`get_apk_artifact_id`)
- Primary: Looks for artifact named exactly `minehost-debug`
- Fallback: Any artifact with `.apk` extension in name
- Returns artifact ID for download

#### Artifact Download (`download_apk_artifact`)
- Attempts to download specific `minehost-debug` artifact
- Fallback: Download any APK artifact
- Extracts APK file from downloaded directory
- Uses retry mechanism (3 attempts)

#### APK Validation (`validate_apk`)
- Validates APK exists and is non-empty
- **Primary Method (aapt):** 
  - Verifies package name matches `APK_PACKAGE_NAME` (default: `com.aistudio.minehost.qweras`)
  - Checks for native library in `arm64-v8a` directory
  - Validates minimum SDK version (`APK_MIN_SDK_VERSION`, default: 26)
- **Fallback Method (unzip):**
  - Extracts and validates `AndroidManifest.xml`
  - Checks package name, native library presence (`lib/arm64-v8a/*.so`)
  - Validates SDK version from manifest

#### Device Selection (`select_arm_device`)
- Lists virtual ARM devices from Firebase Test Lab catalog
- Filters for: virtual form factor, ARM ABI support (`armeabi*` in supportedAbis), non-deprecated
- Selects highest supported API level ≥ min SDK version (default 26)
- Returns device model and API level as CSV string

#### Test Execution (`run_test_lab`)
- Submits APK to Firebase Test Lab with configured parameters:
  - Test type: `TESTLAB_TYPE` (default: `robo`)
  - Locale: `TESTLAB_LOCALE` (default: `en`)
  - Orientation: `TESTLAB_ORIENTATION` (default: `portrait`)
  - Timeout: `TESTLAB_TIMEOUT` (default: 120s)
- Extracts Matrix ID from test output
- Downloads results from Google Cloud Storage bucket to local directory
- Verifies presence of required artifacts: screenshot (`.png`), video (`video.mp4`), logs (`*log*.txt`)
- Returns JSON with matrix ID, report URL, and results directory

#### State Management (`update_beastmode_state`)
- Updates Beast Mode state (`.claude/beastmode_state.json`) with Firebase Test Lab results
- Stores: run ID, artifact ID, selected device, matrix ID, report URL, results directory, timestamp, status, commit SHA
- Uses `jq` for atomic JSON updates

#### Main Workflow (`main`)
1. Validate prerequisites and get normalized bucket
2. Read current Beast Mode state
3. Get latest successful APK-producing GitHub Actions run
4. Get APK artifact ID from that run
5. Download APK artifact
6. Validate downloaded APK
7. Select compatible ARM virtual device
8. Run Firebase Test Lab and collect results
9. Update Beast Mode state with results
10. Cleanup temporary APK directory

---

## 3. Artifact Provenance & Tracking

### Commit SHA Provenance
- **Source:** GitHub Actions workflow run (via `get_latest_apk_run`)
- **Tracking:** Commit SHA extracted from workflow run via GitHub API
- **Usage:** 
  - Recorded in Beast Mode state during Firebase Test Lab execution
  - Used for result traceability: `firebaseTestLab.lastRun.commitSha`
  - Enables correlation between specific code commits and test outcomes

### Artifact Download Provenance
- **Source:** GitHub Actions artifact `minehost-debug`
- **Tracking:** 
  - Run ID → Artifact ID → Download → Local file path
  - Maintained through script variables: `run_id`, `artifact_id`, `apk_file`
- **Validation:** APK integrity checked via package name, native ABI, and SDK version

### Result Provenance
- **Source:** Firebase Test Lab execution
- **Tracking:**
  - Matrix ID from test output
  - Results downloaded to timestamped directory: `.claude/testlab-results/$timestamp/`
  - Report URL from Firebase Test Lab (when available)
  - All stored in Beast Mode state under `firebaseTestLab.lastRun`

### GitHub Artifact Handling
- **Artifact Name Consistency:** Workflow uploads `minehost-debug`, script downloads `minehost-debug`
- **Retry Mechanism:** All network/GH API calls use 3-attempt exponential backoff
- **Error Handling:** Comprehensive logging to stderr, clean exit codes
- **Cleanup:** Temporary APK directory removed on successful completion

---

## 4. Integration with Beast Mode v4

### State Updates
The Firebase Test Lab integration updates the Beast Mode state structure:
```json
{
  "firebaseTestLab": {
    "lastRun": {
      "runId": "<github_run_id>",
      "artifactId": "<artifact_id>", 
      "selectedDevice": "<model,apiLevel>",
      "resultDirectory": "<local_results_path>",
      "reportUrl": "<firebase_report_url>",
      "timestamp": "<ISO_8601_timestamp>",
      "status": "COMPLETED|FAILED",
      "commitSha": "<git_commit_sha>"
    }
  }
}
```

### Workflow Coordination
- Designed to work within Beast Mode v4 objective-based execution
- Updates state atomically via `write_beastmode_state` function
- Supports both successful and failed test outcomes
- Maintains traceability from code commit → build artifact → test execution → results

---

## 5. Configuration & Customization

### Environment Variables (with defaults)
| Variable | Default | Description |
|----------|---------|-------------|
| `APK_PACKAGE_NAME` | `com.aistudio.minehost.qweras` | Expected APK package name |
| `APK_NATIVE_ABI` | `arm64-v8a` | Required native ABI |
| `APK_MIN_SDK_VERSION` | `26` | Minimum Android SDK version |
| `APK_ARTIFACT_NAME` | `minehost-debug` | GitHub Actions artifact name |
| `TESTLAB_TYPE` | `robo` | Firebase Test Lab test type |
| `TESTLAB_LOCALE` | `en` | Test locale |
| `TESTLAB_ORIENTATION` | `portrait` | Device orientation |
| `TESTLAB_TIMEOUT` | `120` | Test timeout in seconds |
| `GCLOUD_CMD` | `gcloud` | gcloud command location |
| `FIREBASE_TESTLAB_BUCKET` | *(required)* | GS bucket for results (e.g., `gs://mine-host-testlab-results`) |

### Error Handling & Resilience
- **Retry Logic:** Network/GH API calls use exponential backoff (2s, 4s, 8s...)
- **Fallback Mechanisms:** 
  - Primary/secondary artifact naming strategies
  - aapt/unzip dual validation approach
  - gsutil/gcloud storage fallback for bucket operations
- **Atomic Updates:** State file updates use temporary file + move pattern
- **Resource Cleanup:** Trap ensures temporary directory cleanup on success

---

## 6. Security & Compliance Notes

### Permission Model
- Script executes with user privileges (no privilege escalation)
- Relies on existing `gh` and `gcloud` authentication
- No hardcoded credentials or secrets in script

### Data Handling
- APK stored temporarily in `.tmp/firebase-test-lab/apk/`
- Results stored in `.claude/testlab-results/<timestamp>/`
- No logging of sensitive data to stdout (all logging to stderr)
- Temporary files cleaned up automatically

### Audit Trail
- Complete provenance tracking from GitHub commit to test results
- All external interactions logged to stderr for debugging
- State updates provide verifiable chain of custody

---

## 7. Summary

The MineHost repository implements a robust, production-ready integration between GitHub Actions and Firebase Test Lab with the following strengths:

### ✅ **Artifact Management**
- Consistent artifact naming (`minehost-debug`) across workflow and test script
- Reliable artifact discovery with fallback strategies
- Proper retention policies (14 days) and error handling

### ✅ **Test Automation**
- End-to-end automation from build to test execution
- Comprehensive APK validation (package, native code, SDK version)
- Intelligent device selection (ARM virtual, non-deprecated, API level appropriate)
- Result verification (screenshots, video, logs required)

### ✅ **State Integration**
- Seamless integration with Beast Mode v4 state management
- Atomic state updates prevent corruption
- Complete provenance tracking (commit → artifact → test → results)

### ✅ **Resilience & Error Handling**
- Retry mechanisms with exponential backoff for all external calls
- Fallback strategies for tool availability (aapt/unzip, gsutil/gcloud)
- Comprehensive error logging and clean failure modes
- Automatic resource cleanup

### ✅ **Traceability**
- Commit SHA provenance maintained throughout pipeline
- Artifact download provenance tracked via GitHub API
- Result provenance stored with timestamps and metadata
- Full audit trail from code change to test outcome

The implementation satisfies all requirements for Firebase Test Lab audit focusing on artifact handling, provenance tracking, and integration with the existing CI/CD workflow.

---
*Report generated based on static analysis of repository files. No modifications made to source code during audit.*