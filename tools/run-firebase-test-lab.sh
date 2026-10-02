#!/data/data/com.termux/files/usr/glibc/bin/bash
# Firebase Test Lab integration for MineHost Beast Mode v4

set -euo pipefail

# Directory of this script
TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$TOOLS_DIR")"
BEAST_MODE_STATE="$PROJECT_ROOT/.claude/beastmode_state.json"
TMP_APK_DIR="$PROJECT_ROOT/.tmp/firebase-test-lab/apk"

# Configuration via environment variables with defaults
# APK validation
: "${APK_PACKAGE_NAME:=com.aistudio.minehost.qweras}"
: "${APK_NATIVE_ABI:=arm64-v8a}"
: "${APK_MIN_SDK_VERSION:=26}"
# Artifact name for GitHub Actions download
: "${APK_ARTIFACT_NAME:=minehost-debug}"
# Firebase Test Lab test parameters
: "${TESTLAB_TYPE:=robo}"
: "${TESTLAB_LOCALE:=en}"
: "${TESTLAB_ORIENTATION:=portrait}"
: "${TESTLAB_TIMEOUT:=120}"
# gcloud command location (if not in PATH, adjust as needed)
: "${GCLOUD_CMD:=gcloud}"

# Function to read Beast Mode state
read_beastmode_state() {
    if [[ -f "$BEAST_MODE_STATE" ]]; then
        cat "$BEAST_MODE_STATE"
    else
        echo "{}"
    fi
}

# Function to write Beast Mode state atomically
write_beastmode_state() {
    local new_state="$1"
    local tmp_file="${BEAST_MODE_STATE}.tmp"
    echo "$new_state" > "$tmp_file" && mv "$tmp_file" "$BEAST_MODE_STATE"
}

# Function to log (to stderr to avoid contaminating stdout)
log() {
    echo "[firebase-test-lab] $*" >&2
}

# Function to retry a command with exponential backoff
# $1: max attempts
# $@: command and arguments to run (as an array)
# Returns: the command's stdout on success (via echo), or nothing on failure
# Note: All logging goes to stderr to avoid contaminating stdout
retry_command() {
    local max_attempts=$1
    shift
    local cmd=("$@")   # Command and arguments as an array
    local attempt=1
    local delay=2
    local stdout
    local stderr
    local exit_code

    while [[ $attempt -le $max_attempts ]]; do
        # Run command, capture stdout and stderr to temporary files
        local stdout_file stderr_file
        stdout_file=$(mktemp)
        stderr_file=$(mktemp)
        # Execute command, redirecting stdout and stderr to the temp files
        "${cmd[@]}" >"$stdout_file" 2>"$stderr_file"
        exit_code=$?
        stdout=$(<"$stdout_file")
        stderr=$(<"$stderr_file")
        rm -f "$stdout_file" "$stderr_file"

        if [[ $exit_code -eq 0 ]]; then
            # Success: output stdout (clean) and return 0
            echo "$stdout"
            return 0
        else
            # Failure: log stderr and stdout for debugging, then retry
            log "Attempt $attempt/$max_attempts failed. Command: ${cmd[*]}"
            log "Stderr: $stderr"
            log "Stdout: $stdout"
            attempt=$((attempt+1))
            delay=$((delay*2))
            if [[ $attempt -le $max_attempts ]]; then
                log "Retrying in $delay seconds..."
                sleep $delay
            fi
        fi
    done

    log "All $max_attempts attempts failed for command: ${cmd[*]}"
    return 1
}

# Function to validate required environment and tools
# Outputs the normalized bucket on success (via echo), or nothing on failure
validate_prerequisites() {
    # Check for gh
    if ! command -v gh &> /dev/null; then
        log "GitHub CLI (gh) is not installed or not in PATH."
        return 1
    fi

    # Check for gcloud
    if ! command -v gcloud &> /dev/null; then
        log "Google Cloud SDK (gcloud) is not installed or not in PATH."
        return 1
    fi

    # Check for python3
    if ! command -v python3 &> /dev/null; then
        log "Python3 is not installed or not in PATH."
        return 1
    fi

    # Check for Firebase Test Lab bucket configuration
    local bucket="${FIREBASE_TESTLAB_BUCKET:-}"
    if [[ -z "$bucket" ]]; then
        log "Environment variable FIREBASE_TESTLAB_BUCKET is not set."
        log "Please set it to your Google Cloud Storage bucket for Test Lab results (e.g., gs://mine-host-testlab-results)."
        return 1
    fi

    # Normalize bucket: strip leading gs:// if present
    if [[ "$bucket" =~ ^gs:// ]]; then
        bucket="${bucket#gs://}"
    fi

    # Remove trailing slash
    bucket="${bucket%/}"

    log "Validating access to bucket: gs://$bucket"
    # Check access using gsutil or gcloud storage
    if command -v gsutil &> /dev/null; then
        if ! gsutil ls "gs://$bucket" > /dev/null 2>&1; then
            log "Cannot access bucket gs://$bucket. Please ensure it exists and you have write permissions."
            return 1
        fi
    elif command -v gcloud &> /dev/null && gcloud storage --help >/dev/null 2>&1; then
        if ! gcloud storage ls "gs://$bucket" > /dev/null 2>&1; then
            log "Cannot access bucket gs://$bucket. Please ensure it exists and you have write permissions."
            return 1
        fi
    else
        log "Neither gsutil nor gcloud storage is available. Please install gsutil or ensure gcloud storage is available."
        return 1
    fi

    # Output the normalized bucket for use by the caller
    echo "$bucket"
}

# Function to get the latest successful GitHub Actions run with APK artifact
get_latest_apk_run() {
    log "Fetching latest successful GitHub Actions run with APK artifact"
    # First, try to get a run from the Android CI workflow
    local run_id
    run_id=$(retry_command 3 gh run list --limit 1 --status success --workflow "Android CI" --json databaseId --branch main | python3 -c "import json,sys; data=json.load(sys.stdin); print(data[0]['databaseId'] if len(data) > 0 else '')") || {
        log "No successful run found for workflow 'Android CI'. Trying any workflow with APK artifact."
        # Fallback: any successful run that has an APK artifact
        run_id=$(retry_command 3 gh run list --limit 1 --status success --json databaseId,workflowName,headSha,conclusion,event,name --branch main | python3 -c "
import json,sys
data=json.load(sys.stdin)
for item in data:
    if 'name' in item and ('Build.APK' in item['name'] or 'Android.Build' in item['name']):
        print(item['databaseId'])
        sys.exit(0)
        ") || {
            log "No successful run with APK artifact found in any workflow."
            return 1
        }
        # Take the first line in case of multiple outputs (shouldn't happen with --limit 1, but safe)
        run_id=$(echo "$run_id" | head -n 1)
    }

    if [[ -z "$run_id" ]]; then
        log "Failed to retrieve a valid run ID."
        return 1
    fi

    # Fetch the commit SHA for this run
    local commit_sha
    commit_sha=$(retry_command 3 gh api repos/:owner/:repo/actions/runs/$run_id) || {
        log "Failed to get commit SHA for run $run_id"
        return 1
    }
    commit_sha=$(echo "$commit_sha" | python3 -c "import json,sys; print(json.load(sys.stdin)['head_sha'])")

    # Output both run_id and commit_sha as a JSON object for easy parsing
    python3 -c "import json,sys; print(json.dumps({'run_id': sys.argv[1], 'commit_sha': sys.argv[2]}))" "$run_id" "$commit_sha"
}

# Function to get the APK artifact ID from a GitHub Actions run
get_apk_artifact_id() {
    local run_id=$1
    local artifact_id

    # First, try to get the artifact named "minehost-debug"
    artifact_id=$(retry_command 3 gh api repos/:owner/:repo/actions/runs/$run_id/artifacts) || {
        log "No artifact named 'minehost-debug' found in run $run_id. Trying any APK artifact."
        # Fallback: any APK artifact
        artifact_id=$(retry_command 3 gh api repos/:owner/:repo/actions/runs/$run_id/artifacts) || {
            log "No APK artifact found in run $run_id"
            return 1
        }
        # Take the first line in case of multiple outputs
        artifact_id=$(echo "$artifact_id" | head -n 1)
    }
    # Now filter the artifact_id using python3 instead of jq
    artifact_id=$(echo "$artifact_id" | python3 -c "
import json, sys
data = json.load(sys.stdin)
# First, try to get the artifact named 'minehost-debug'
for artifact in data.get('artifacts', []):
    if artifact.get('name') == 'minehost-debug':
        print(artifact.get('id'))
        sys.exit(0)
    # Fallback: any APK artifact
    if artifact.get('name', '').endswith('.apk'):
        print(artifact.get('id'))
        sys.exit(0)
")

    if [[ -z "$artifact_id" ]]; then
        log "Failed to retrieve a valid artifact ID for run $run_id"
        return 1
    fi

    echo "$artifact_id"
}

# Function to download the APK artifact from a GitHub Actions run
download_apk_artifact() {
    local run_id=$1
    local apk_dir="$TMP_APK_DIR"
    local apk_file

    log "Downloading APK artifact from run $run_id"
    mkdir -p "$apk_dir"

    # First, try to download the specific artifact named "minehost-debug"
    if retry_command 3 gh run download $run_id --dir $apk_dir --name 'minehost-debug' 2>/dev/null; then
        # Look for the APK file in the downloaded directory
        apk_file=$(find "$apk_dir" -name '*.apk' -type f | head -n 1)
        if [[ -n "$apk_file" ]]; then
            echo "$apk_file"
            return 0
        fi
    fi

    # Fallback: download any APK artifact
    if retry_command 3 gh run download $run_id --dir $apk_dir --name '*.apk' 2>/dev/null; then
        apk_file=$(find "$apk_dir" -name '*.apk' -type f | head -n 1)
        if [[ -n "$apk_file" ]]; then
            echo "$apk_file"
            return 0
        fi
    fi

    log "Failed to download APK artifact from run $run_id"
    return 1
}

# Function to validate the downloaded APK
validate_apk() {
    local apk_file=$1

    # Check if APK file exists and is not empty
    if [[ ! -f "$apk_file" ]] || [[ ! -s "$apk_file" ]]; then
        log "APK file does not exist or is empty: $apk_file"
        return 1
    fi

    # Use aapt to check package, native code, and sdkVersion
    # Note: aapt might not be installed, we can use unzip as fallback to inspect the APK
    local package_name="${APK_PACKAGE_NAME}"
    local native_abi="${APK_NATIVE_ABI}"
    local min_sdk_version="${APK_MIN_SDK_VERSION}"

    if command -v aapt &> /dev/null; then
        log "Validating APK with aapt"
        local output
        output=$(aapt dump badging "$apk_file" 2>/dev/null) || {
            log "Failed to run aapt on APK file"
            return 1
        }

        # Extract package
        if ! echo "$output" | grep -q "package: name='$package_name'"; then
            log "APK package mismatch. Expected: $package_name"
            log "aapt output: $output"
            return 1
        fi

        # Extract native libraries (look for arm64-v8a in native library paths)
        if ! echo "$output" | grep -q "native-library:.*arm64-v8a"; then
            log "APK missing native library for arm64-v8a"
            log "aapt output: $output"
            return 1
        fi

        # Extract sdkVersion
        local sdk_version
        sdk_version=$(echo "$output" | grep -oP "sdkVersion:'\K\d+'" | tr -d "'") || {
            log "Could not extract sdkVersion from aapt output"
            return 1
        }
        if [[ "$sdk_version" -lt "$min_sdk_version" ]]; then
            log "APK sdkVersion too low: $sdk_version, minimum required: $min_sdk_version"
            return 1
        fi

    else
        # Fallback to unzip and inspect AndroidManifest.xml
        log "aapt not found, using unzip fallback to validate APK"
        local manifest_path
        manifest_path=$(unzip -l "$apk_file" | grep -E "AndroidManifest.xml$" | head -n 1 | awk '{print $NF}') || {
            log "Could not find AndroidManifest.xml in APK"
            return 1
        }

        # Extract package name from manifest
        local manifest_content
        manifest_content=$(unzip -p "$apk_file" "$manifest_path" 2>/dev/null) || {
            log "Could not extract AndroidManifest.xml from APK"
            return 1
        }

        if ! echo "$manifest_content" | grep -q "package=\"$package_name\""; then
            log "APK package mismatch. Expected: $package_name"
            return 1
        fi

        # Check for native library in lib/arm64-v8a/
        if ! unzip -l "$apk_file" | grep -q "lib/arm64-v8a/.*\.so"; then
            log "APK missing native library for arm64-v8a"
            return 1
        fi

        # Check sdkVersion from manifest
        if ! echo "$manifest_content" | grep -q "sdkVersion=\"$min_sdk_version\""; then
            # Extract the actual sdkVersion integer and compare
            local actual_sdk_version
            actual_sdk_version=$(echo "$manifest_content" | grep -o 'sdkVersion="[0-9]*"' | cut -d'"' -f2)
            if [[ -z "$actual_sdk_version" ]] || [[ "$actual_sdk_version" -lt "$min_sdk_version" ]]; then
                log "APK sdkVersion too low. Expected at least: $min_sdk_version, found: $actual_sdk_version"
                return 1
            fi
        fi
    fi

    log "APK validation passed"
    return 0
}

# Function to select an ARM virtual device from Firebase Test Lab
select_arm_device() {
    log "Selecting compatible ARM virtual device from Firebase Test Lab catalog"
    # List virtual devices that are ARM and not deprecated.
    # We'll get the list of virtual devices in JSON format and filter for ARM architecture and non-deprecated.
    local device_model
    device_model=$(gcloud firebase test android models list --filter=virtual --format='json' 2>/dev/null | \
        python3 -c '
import sys, json
data = json.load(sys.stdin)
for device in data:
    # Check if the device is virtual (formFactor=VIRTUAL) - we already filtered by virtual, but double-check
    if device.get("formFactor") != "VIRTUAL":
        continue
    # Check if the device supports ARM ABI (look for armeabi-v7a or arm64-v8a in supportedAbis)
    abis = device.get("supportedAbis", [])
    if not any(abi.startswith("armeabi") for abi in abis):
        continue
    # Check if the device is deprecated: look for "deprecated" in tags
    tags = device.get("tags", [])
    if "deprecated" in tags:
        continue
    # If we passed all checks, output the model id and break
    print(device.get("id"))
    break
' | head -n 1) || {
        log "Failed to list ARM virtual devices."
        return 1
    }

    if [[ -z "$device_model" ]]; then
        log "No ENABLED ARM virtual device found in Firebase Test Lab catalog."
        return 1
    fi

    # Query supported API versions for the selected device and choose the highest (>= min_sdk_version)
    local api_levels
    api_levels=$(gcloud firebase test android models describe "$device_model" --format='json' 2>/dev/null | \
        python3 -c '
import sys, json
import os
min_sdk_version = int(os.environ.get("APK_MIN_SDK_VERSION", "26"))
data = json.load(sys.stdin)
# The supportedApiLevels is a list of integers
api_levels = data.get("supportedApiLevels", [])
# Filter for API levels >= min_sdk_version
valid_levels = [level for level in api_levels if level >= min_sdk_version]
if not valid_levels:
    print("")  # Empty string to indicate failure
else:
    # Choose the highest
    print(max(valid_levels))
') || {
        log "Failed to get supported API levels for device $device_model"
        return 1
    }

    if [[ -z "$api_levels" ]]; then
        log "No supported API level <= 26 found for device $device_model"
        return 1
    fi

    echo "$device_model,$api_levels"
}

# Function to run Firebase Test Lab and collect results
run_test_lab() {
    local apk_file=$1
    local device_model_version=$2  # Format: model,apiLevel
    local bucket=$3

    # Extract device model and API level
    local device_model
    local api_level
    device_model=$(echo "$device_model_version" | cut -d',' -f1)
    api_level=$(echo "$device_model_version" | cut -d',' -f2)

    # Create a timestamp for uniqueness
    local timestamp=$(date +%s)
    local bucket_path="firebase-test-lab-results/$timestamp"
    local local_results_dir="$PROJECT_ROOT/.claude/testlab-results/$timestamp"
    mkdir -p "$local_results_dir"

    log "Submitting APK to Firebase Test Lab with device $device_model and API level $api_level"
    local test_output
    test_output=$(gcloud firebase test android run \
        --type "${TESTLAB_TYPE}" \
        --app "$apk_file" \
        --device model="$device_model",version="$api_level",locale="${TESTLAB_LOCALE}",orientation="${TESTLAB_ORIENTATION}" \
        --timeout "${TESTLAB_TIMEOUT}s" \
        --results-bucket="gs://$bucket" \
        --results-dir=$bucket_path \
        2>&1) || {
        log "Firebase Test Lab submission failed"
        return 1
    }

    log "Test output received"

    # Extract the matrix ID from the output
    local matrix_id
    matrix_id=$(echo "$test_output" | grep -oP '(?<=Matrix ID: )[^ ]+' || true)
    if [[ -z "$matrix_id" ]]; then
        log "Failed to extract Matrix ID from Firebase Test Lab output. Aborting."
        return 1
    fi

    log "Firebase Test Lab matrix ID: $matrix_id"

    # Download results from bucket to local directory
    log "Downloading results from bucket: gs://$bucket/$bucket_path to $local_results_dir"
    local download_success=0
    if command -v gsutil &> /dev/null; then
        if ! gsutil -m cp -r "gs://$bucket/$bucket_path/*" "$local_results_dir/"; then
            log "Failed to download results from Firebase Test Lab for matrix $matrix_id using gsutil"
            download_success=1
        fi
    elif command -v gcloud &> /dev/null && gcloud storage --help >/dev/null 2>&1; then
        if ! gcloud storage cp -r "gs://$bucket/$bucket_path/*" "$local_results_dir/"; then
            log "Failed to download results from Firebase Test Lab for matrix $matrix_id using gcloud storage"
            download_success=1
        fi
    else
        log "Neither gsutil nor gcloud storage is available for downloading results."
        download_success=1
    fi

    if [[ $download_success -ne 0 ]]; then
        return 1
    fi

    # Verify that we have some results
    if [[ ! -d "$local_results_dir" ]] || [[ -z "$(ls -A "$local_results_dir")" ]]; then
        log "Downloaded results directory is empty or does not exist."
        return 1
    fi

    # Check for required artifacts (at least one screenshot, the video, and the logs)
    local has_screenshot=false
    local has_video=false
    local has_logs=false

    # Look for screenshots (commonly in*/screenshots/ or directly as .png)
    if find "$local_results_dir" -name "*.png" -type f | head -n 1 | grep -q .; then
        has_screenshot=true
    fi

    # Look for video (commonly video.mp4)
    if find "$local_results_dir" -name "video.mp4" -type f | head -n 1 | grep -q .; then
        has_video=true
    fi

    # Look for logs (commonly logcat.txt or test_exec_log.txt)
    if find "$local_results_dir" -name "*log*.txt" -type f | head -n 1 | grep -q .; then
        has_logs=true
    fi

    if [[ "$has_screenshot" == false ]] || [[ "$has_video" == false ]] || [[ "$has_logs" == false ]]; then
        log "Missing required test artifacts:"
        log "  Screenshot: $has_screenshot"
        log "  Video: $has_video"
        log "  Logs: $has_logs"
        return 1
    fi

    # Get the report URL (if available)
    local report_url
    report_url=$(gcloud firebase test android matrices describe "$matrix_id" --format='value(outcomeSummary.message)' 2>/dev/null || true)
    # If empty, leave it empty (which will become null in JSON)

    # Return JSON with matrix_id, report_url, results_dir using python3
    python3 -c "import json,sys; print(json.dumps({'matrix_id': sys.argv[1], 'report_url': sys.argv[2], 'results_dir': sys.argv[3]}))" "$matrix_id" "$report_url" "$local_results_dir"
}

# Function to update Beast Mode state with Firebase Test Lab information
update_beastmode_state() {
    local state_json=$1
    local run_id=$2
    local artifact_id=$3
    local device_model=$4
    local matrix_id=$5
    local report_url=$6
    local results_dir=$7
    local status=${8:-"COMPLETED"}
    local commit_sha=${9:-""}

    # Generate a timestamp for the state update
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    # Update the state with the firebaseTestLab object using python3
    local updated_state
    updated_state=$(echo "$state_json" | python3 -c "
import json, sys
state = json.load(sys.stdin)
if 'firebaseTestLab' not in state:
    state['firebaseTestLab'] = {}
state['firebaseTestLab']['lastRun'] = {
    'runId': '$run_id',
    'artifactId': '$artifact_id',
    'selectedDevice': '$device_model',
    'resultDirectory': '$results_dir',
    'reportUrl': '$report_url',
    'timestamp': '$timestamp',
    'status': '$status',
    'commitSha': '$commit_sha'
}
print(json.dumps(state))
")

    # Write the updated state
    write_beastmode_state "$updated_state"
}

# Cleanup function to remove temporary APK directory on success
cleanup() {
    log "Cleaning up temporary APK directory: $TMP_APK_DIR"
    rm -rf "$TMP_APK_DIR"
}

# Main function
main() {
    local subcommand="${1:-}"
    shift || true

    if [[ "$subcommand" != "run" ]]; then
        log "Usage: $0 run"
        exit 1
    fi

    log "Starting Firebase Test Lab workflow"

    # Register cleanup trap for success exit
    trap cleanup EXIT

    # Step 0: Validate prerequisites and get normalized bucket
    local bucket
    bucket=$(validate_prerequisites) || {
        exit 1
    }

    # Read current state
    local state_json
    state_json=$(read_beastmode_state)

    # Step 1: Get the latest successful GitHub Actions run with an APK artifact
    local run_info
    run_info=$(get_latest_apk_run) || {
        log "Failed to get a valid run ID for APK artifact."
        exit 1
    }

    # Parse run_info JSON to get run_id and commit_sha
    local run_id
    local commit_sha
    run_id=$(echo "$run_info" | python3 -c "import json,sys; print(json.load(sys.stdin)['run_id'])")
    commit_sha=$(echo "$run_info" | python3 -c "import json,sys; print(json.load(sys.stdin)['commit_sha'])")

    log "Found successful run ID: $run_id"
    log "Commit SHA: $commit_sha"

    # Step 2: Get the APK artifact ID
    local artifact_id
    artifact_id=$(get_apk_artifact_id "$run_id") || {
        log "Failed to get APK artifact ID."
        exit 1
    }

    log "Found APK artifact ID: $artifact_id"

    # Step 3: Download the APK artifact
    local apk_file
    apk_file=$(download_apk_artifact "$run_id") || {
        log "Failed to download APK artifact."
        exit 1
    }

    log "APK file: $apk_file"

    # Step 4: Validate the downloaded APK
    validate_apk "$apk_file" || {
        log "APK validation failed."
        exit 1
    }

    # Step 5: Select an ARM virtual device and get highest supported API level <= 26
    local device_model_version
    device_model_version=$(select_arm_device) || {
        log "Failed to select a compatible ARM virtual device."
        exit 1
    }

    log "Selected device model and API level: $device_model_version"

    # Step 6: Run Firebase Test Lab and collect results
    local firebase_info
    firebase_info=$(run_test_lab "$apk_file" "$device_model_version" "$bucket") || {
        log "Firebase Test Lab run failed."
        # Update state to FAILED
        local failed_matrix_id
        failed_matrix_id=$(date +%s)
        update_beastmode_state "$state_json" "$run_id" "$artifact_id" "$device_model_version" "$failed_matrix_id" "" "" "FAILED" "$commit_sha"
        exit 1
    }

    # Parse the Firebase info JSON
    local matrix_id report_url results_dir
    matrix_id=$(echo "$firebase_info" | python3 -c "import json,sys; print(json.load(sys.stdin)['matrix_id'])")
    report_url=$(echo "$firebase_info" | python3 -c "import json,sys; print(json.load(sys.stdin)['report_url'])")
    results_dir=$(echo "$firebase_info" | python3 -c "import json,sys; print(json.load(sys.stdin)['results_dir'])")

    log "Firebase Test Lab run completed successfully"
    log "Matrix ID: $matrix_id"
    log "Report URL: $report_url"
    log "Results directory: $results_dir"

    # Step 7: Update Beast Mode state with the results
    update_beastmode_state "$state_json" "$run_id" "$artifact_id" "$device_model_version" "$matrix_id" "$report_url" "$results_dir" "COMPLETED" "$commit_sha"

    log "Firebase Test Lab workflow completed successfully"
}

# Invoke main
main "$@"