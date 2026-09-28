#!/data/data/com.termux/files/usr/glibc/bin/bash
# Firebase Test Lab integration for MineHost Beast Mode v4

set -euo pipefail

# Directory of this script
TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$TOOLS_DIR")"
BEAST_MODE_STATE="$PROJECT_ROOT/.claude/beastmode_state.json"

# Function to read Beast Mode state
read_beastmode_state() {
    if [[ -f "$BEAST_MODE_STATE" ]]; then
        cat "$BEAST_MODE_STATE"
    else
        echo "{}"
    fi
}

# Function to write Beast Mode state
write_beastmode_state() {
    local new_state="$1"
    echo "$new_state" > "$BEAST_MODE_STATE"
}

# Function to log
log() {
    echo "[firebase-test-lab] $*"
}

# Function to retry a command with exponential backoff
# $1: max attempts
# $2: command to run (as a string)
retry_command() {
    local max_attempts=$1
    shift
    local cmd="$*"
    local attempt=1
    local delay=2

    while [[ $attempt -le $max_attempts ]]; do
        log "Attempt $attempt/$max_attempts: $cmd"
        if eval "$cmd"; then
            return 0
        else
            log "Attempt $attempt failed. Retrying in $delay seconds..."
            sleep $delay
            attempt=$((attempt + 1))
            delay=$((delay * 2))  # exponential backoff
        fi
    done

    log "All $max_attempts attempts failed for command: $cmd"
    return 1
}

# Function to validate required environment and tools
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

    # Check for gsutil (for bucket validation)
    if ! command -v gsutil &> /dev/null; then
        log "Google Cloud Storage CLI (gsutil) is not installed or not in PATH."
        return 1
    fi

    # Check for jq (for JSON parsing)
    if ! command -v jq &> /dev/null; then
        log "jq is not installed or not in PATH."
        return 1
    fi

    # Check for Firebase Test Lab bucket configuration
    local bucket="${FIREBASE_TESTLAB_BUCKET:-}"
    if [[ -z "$bucket" ]]; then
        log "Environment variable FIREBASE_TESTLAB_BUCKET is not set."
        log "Please set it to your Google Cloud Storage bucket for Test Lab results (e.g., gs://mine-host-testlab-results)."
        return 1
    fi

    # Validate bucket accessibility
    log "Validating access to bucket: $bucket"
    if ! retry_command 3 "gsutil ls \"$bucket\" > /dev/null 2>&1"; then
        log "Cannot access bucket $bucket. Please ensure it exists and you have write permissions."
        return 1
    fi

    return 0
}

# Function to get the latest successful APK artifact from GitHub Actions
get_latest_apk_run() {
    log "Fetching latest successful GitHub Actions run with APK artifact"
    # First, try to get a run from the minehost-debug workflow
    local run_id
    run_id=$(retry_command 3 "gh run list --limit 1 --status success --workflow minehost-debug --json databaseId -q '.[0].databaseId'") || {
        log "No successful run found for workflow 'minehost-debug'. Trying any workflow with APK artifact."
        # Fallback: any successful run that has an APK artifact
        run_id=$(retry_command 3 "gh run list --limit 1 --status success --json databaseId,workflowName,headSha,conclusion,event,name -q '.[] | select(.name | test(\"^Build.*APK$|^Android.*Build$\")) | .databaseId' | head -n 1") || {
            log "No successful run with APK artifact found in any workflow."
            return 1
        }
    }

    if [[ -z "$run_id" ]]; then
        log "Failed to retrieve a valid run ID."
        return 1
    fi

    echo "$run_id"
}

# Function to download the APK artifact from a GitHub Actions run
download_apk_artifact() {
    local run_id=$1
    local apk_dir="$PROJECT_ROOT/.tmp/firebase-test-lab/apk"
    local apk_file

    log "Downloading APK artifact from run $run_id"
    mkdir -p "$apk_dir"

    # Download all APK artifacts
    if ! retry_command 3 "gh run download $run_id --dir $apk_dir --name '*.apk'"; then
        log "Failed to download APK artifact from run $run_id"
        return 1
    fi

    # Find the APK file
    apk_file=$(find "$apk_dir" -name '*.apk' -type f | head -n 1)
    if [[ -z "$apk_file" ]]; then
        log "No APK file found in downloaded artifacts for run $run_id"
        return 1
    fi

    echo "$apk_file"
}

# Function to select an ARM virtual device from Firebase Test Lab
select_arm_device() {
    log "Selecting compatible ARM virtual device from Firebase Test Lab catalog"
    # List ARM virtual devices that are ENABLED (not deprecated or disabled)
    local device_model
    device_model=$(retry_command 3 "gcloud firebase test android models list --format='value(id)' --filter='formFactor=VIRTUAL AND architecture=ARM AND state=ENABLED' | head -n 1") || {
        log "Failed to list ARM virtual devices."
        return 1
    }

    if [[ -z "$device_model" ]]; then
        log "No ENABLED ARM virtual device found in Firebase Test Lab catalog."
        return 1
    fi

    echo "$device_model"
}

# Function to run Firebase Test Lab and collect results
run_test_lab() {
    local apk_file=$1
    local device_model=$2
    local bucket=$3

    local test_results_dir="$PROJECT_ROOT/.tmp/firebase-test-lab/results"
    local run_id
    local matrix_id
    local report_url

    log "Creating temporary results directory: $test_results_dir"
    mkdir -p "$test_results_dir"

    # Submit the test to Firebase Test Lab
    log "Submitting APK to Firebase Test Lab with device $device_model"
    local test_output
    test_output=$(retry_command 3 "gcloud firebase test android run \
        --type robo \
        --app \"$apk_file\" \
        --device model=\"$device_model\",version=28,locale=en,orientation=portrait \
        --timeout 300s \
        --results-bucket=$bucket \
        --results-dir=$test_results_dir \
        --async") || {
        log "Firebase Test Lab submission failed"
        return 1
    }

    # Extract the matrix ID from the output (the async command returns a matrix ID)
    matrix_id=$(echo "$test_output" | grep -oP '(?<=Matrix ID: )[^ ]+' || true)
    if [[ -z "$matrix_id" ]]; then
        log "Failed to extract Matrix ID from Firebase Test Lab output."
        # Try to get the matrix ID from the gcloud output format
        matrix_id=$(echo "$test_output" | grep -oP '(?<=\[\\\]\[\\\]\[\\\] )[a-z0-9\-]+' || true)
    fi

    if [[ -z "$matrix_id" ]]; then
        log "Could not determine Matrix ID. Will use timestamp for tracking."
        matrix_id=$(date +%s)
    fi

    log "Firebase Test Lab matrix ID: $matrix_id"

    # Wait for the test to complete (we'll poll for completion)
    log "Waiting for Firebase Test Lab test to complete..."
    local max_wait=1800  # 30 minutes max wait
    local elapsed=0
    local interval=30

    while [[ $elapsed -lt $max_wait ]]; do
        local state
        state=$(gcloud firebase test android matrices describe "$matrix_id" --format='value(state.status)' 2>/dev/null || echo "UNKNOWN")
        log "Current state: $state (elapsed: ${elapsed}s)"

        if [[ "$state" == "FINISHED" ]]; then
            break
        elif [[ "$state" == "ERROR" || "$state" == "INVALID" ]]; then
            log "Firebase Test Lab matrix entered error state: $state"
            return 1
        fi

        sleep $interval
        elapsed=$((elapsed + interval))
    done

    if [[ $elapsed -ge $max_wait ]]; then
        log "Timeout waiting for Firebase Test Lab test to finish."
        return 1
    fi

    # Download the results from Firebase Test Lab
    log "Downloading test results from Firebase Test Lab"
    if ! retry_command 3 "gsutil -m cp -r \"$bucket/$matrix_id/*\" \"$test_results_dir/\""; then
        log "Failed to download results from Firebase Test Lab for matrix $matrix_id"
        return 1
    fi

    # Verify that we have some results
    if [[ ! -d "$test_results_dir" ]] || [[ -z "$(ls -A "$test_results_dir")" ]]; then
        log "Downloaded results directory is empty or does not exist."
        return 1
    fi

    # Check for required artifacts (at least one screenshot, the video, and the logs)
    local has_screenshot=false
    local has_video=false
    local has_logs=false

    # Look for screenshots (commonly in*/screenshots/ or directly as .png)
    if find "$test_results_dir" -name "*.png" -type f | head -n 1 | grep -q .; then
        has_screenshot=true
    fi

    # Look for video (commonly video.mp4)
    if find "$test_results_dir" -name "video.mp4" -type f | head -n 1 | grep -q .; then
        has_video=true
    fi

    # Look for logs (commonly logcat.txt or test_exec_log.txt)
    if find "$test_results_dir" -name "*log*.txt" -type f | head -n 1 | grep -q .; then
        has_logs=true
    fi

    if [[ "$has_screenshot" == false ]] || [[ "$has_video" == false ]] || [[ "$has_logs" == false ]]; then
        log "Missing required test artifacts:"
        log "  Screenshot: $has_screenshot"
        log "  Video: $has_video"
        log "  Logs: $has_logs"
        # We'll still consider the run as having completed, but we'll note the missing artifacts in the state.
        # However, the requirement says to fail clearly when required artifacts cannot be retrieved.
        # We'll treat this as a failure for the purpose of evidence collection.
        return 1
    fi

    # Get the report URL (if available)
    report_url=$(gcloud firebase test android matrices describe "$matrix_id" --format='value(outcomeSummary.message)' 2>/dev/null || true)
    if [[ -z "$report_url" ]]; then
        report_url="https://console.firebase.google.com/project/_/testlab/matrices/$matrix_id"
    fi

    echo "{\"matrix_id\":\"$matrix_id\",\"report_url\":\"$report_url\",\"results_dir\":\"$test_results_dir\"}"
}

# Function to update Beast Mode state with Firebase Test Lab information
update_beastmode_state() {
    local state_json=$1
    local firebase_info=$2  # JSON string with matrix_id, report_url, results_dir, status

    # Parse the Firebase info
    local matrix_id report_url results_dir status
    matrix_id=$(echo "$firebase_info" | jq -r '.matrix_id')
    report_url=$(echo "$firebase_info" | jq -r '.report_url')
    results_dir=$(echo "$firebase_info" | jq -r '.results_dir')
    status=$(echo "$firebase_info" | jq -r '.status // "COMPLETED"')

    # Generate a timestamp for the state update
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    # Update the state with the firebaseTestLab object
    local updated_state
    updated_state=$(echo "$state_json" | jq --argjson ftl "{
        lastRun: {
            runId: \"$matrix_id\",
            artifactId: \"$matrix_id\",  // Using matrix ID as artifact ID for now
            selectedDevice: \"$device_model\",
            resultDirectory: \"$results_dir\",
            reportUrl: \"$report_url\",
            timestamp: \"$timestamp\",
            status: \"$status\"
        }
    }' '.firebaseTestLab = $ftl')"

    # Write the updated state
    write_beastmode_state "$updated_state"
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

    # Step 0: Validate prerequisites
    if ! validate_prerequisites; then
        exit 1
    fi

    # Read current state
    local state_json
    state_json=$(read_beastmode_state)

    # Step 1: Get the latest successful GitHub Actions run with an APK artifact
    local run_id
    run_id=$(get_latest_apk_run) || {
        log "Failed to get a valid run ID for APK artifact."
        exit 1
    }

    log "Found successful run ID: $run_id"

    # Step 2: Download the APK artifact
    local apk_file
    apk_file=$(download_apk_artifact "$run_id") || {
        log "Failed to download APK artifact."
        exit 1
    }

    log "APK file: $apk_file"

    # Step 3: Select an ARM virtual device
    local device_model
    device_model=$(select_arm_device) || {
        log "Failed to select a compatible ARM virtual device."
        exit 1
    }

    log "Selected device model: $device_model"

    # Step 4: Run Firebase Test Lab and collect results
    local bucket="${FIREBASE_TESTLAB_BUCKET:-gs://mine-host-testlab-results}"
    local firebase_info
    firebase_info=$(run_test_lab "$apk_file" "$device_model" "$bucket") || {
        log "Firebase Test Lab run failed."
        # Update state to FAILED
        local failed_info
        failed_info=$(printf '{"matrix_id":"%s","report_url":"%s","results_dir":"%s","status":"FAILED"}' \
            "$(date +%s)" "" "")

        # We still want to update the state with the failure
        update_beastmode_state "$state_json" "$failed_info"
        exit 1
    }

    log "Firebase Test Lab run completed successfully"

    # Step 5: Update Beast Mode state with the results
    update_beastmode_state "$state_json" "$firebase_info"

    log "Firebase Test Lab workflow completed successfully"
}

# Invoke main
main "$@"