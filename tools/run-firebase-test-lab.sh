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
        local output
        output=$(eval "$cmd" 2>&1)
        local exit_code=$?
        if [[ $exit_code -eq 0 ]]; then
            return 0
        else
            log "Attempt $attempt failed. Output: $output"
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
        # Try with gcloud storage if gsutil is not available
        if command -v gcloud &> /dev/null && gcloud storage --help >/dev/null 2>&1; then
            if ! retry_command 3 "gcloud storage ls \"$bucket\" > /dev/null 2>&1"; then
                log "Cannot access bucket $bucket. Please ensure it exists and you have write permissions."
                return 1
            fi
        else
            log "Cannot access bucket $bucket. Please ensure it exists and you have write permissions."
            log "Neither gsutil nor gcloud storage is available. Please install gsutil or ensure gcloud storage is available."
            return 1
        fi
    fi

    return 0
}

# Function to get the latest successful GitHub Actions run with APK artifact
get_latest_apk_run() {
    log "Fetching latest successful GitHub Actions run with APK artifact"
    # First, try to get a run from the Android CI workflow
    local run_id
    run_id=$(retry_command 3 "gh run list --limit 1 --status success --workflow 'Android CI' --json databaseId -q '.[0].databaseId'") || {
        log "No successful run found for workflow 'Android CI'. Trying any workflow with APK artifact."
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

# Function to get the APK artifact ID from a GitHub Actions run
get_apk_artifact_id() {
    local run_id=$1
    local artifact_id

    # First, try to get the artifact named "minehost-debug"
    artifact_id=$(retry_command 3 "gh api repos/:owner/:repo/actions/runs/$run_id/artifacts --jq '.artifacts[] | select(.name == \"minehost-debug\") | .id' 2>/dev/null") || {
        log "No artifact named 'minehost-debug' found in run $run_id. Trying any APK artifact."
        # Fallback: any APK artifact
        artifact_id=$(retry_command 3 "gh api repos/:owner/:repo/actions/runs/$run_id/artifacts --jq '.artifacts[] | select(.name | test(\"\\.apk$\")) | .id' 2>/dev/null | head -n 1") || {
            log "No APK artifact found in run $run_id"
            return 1
        }
    }

    if [[ -z "$artifact_id" ]]; then
        log "Failed to retrieve a valid artifact ID for run $run_id"
        return 1
    fi

    echo "$artifact_id"
}

# Function to download the APK artifact from a GitHub Actions run
download_apk_artifact() {
    local run_id=$1
    local apk_dir="$PROJECT_ROOT/.tmp/firebase-test-lab/apk"
    local apk_file

    log "Downloading APK artifact from run $run_id"
    mkdir -p "$apk_dir"

    # First, try to download the specific artifact named "minehost-debug"
    if retry_command 3 "gh run download $run_id --dir $apk_dir --name 'minehost-debug' 2>/dev/null"; then
        # Look for the APK file in the downloaded directory
        apk_file=$(find "$apk_dir" -name '*.apk' -type f | head -n 1)
        if [[ -n "$apk_file" ]]; then
            echo "$apk_file"
            return 0
        fi
    fi

    # Fallback: download any APK artifact
    if retry_command 3 "gh run download $run_id --dir $apk_dir --name '*.apk' 2>/dev/null"; then
        apk_file=$(find "$apk_dir" -name '*.apk' -type f | head -n 1)
        if [[ -n "$apk_file" ]]; then
            echo "$apk_file"
            return 0
        fi
    fi

    log "Failed to download APK artifact from run $run_id"
    return 1
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

    # Create a timestamp for uniqueness
    local timestamp=$(date +%s)
    local bucket_path="firebase-test-lab-results/$timestamp"
    local local_results_dir="$PROJECT_ROOT/.claude/testlab-results/$timestamp"
    mkdir -p "$local_results_dir"

    log "Submitting APK to Firebase Test Lab with device $device_model"
    local test_output
    test_output=$(gcloud firebase test android run \
        --type robo \
        --app "$apk_file" \
        --device model="$device_model",version=28,locale=en,orientation=portrait \
        --timeout 300s \
        --results-bucket=$bucket \
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
        log "Failed to extract Matrix ID from Firebase Test Lab output. Using timestamp as matrix ID."
        matrix_id="$timestamp"
    fi

    log "Firebase Test Lab matrix ID: $matrix_id"

    # Download results from bucket to local directory
    log "Downloading results from bucket: $bucket/$bucket_path to $local_results_dir"
    local download_success=0
    if command -v gsutil &> /dev/null; then
        if ! retry_command 3 "gsutil -m cp -r \"gs://$bucket/$bucket_path/*\" \"$local_results_dir/\""; then
            log "Failed to download results from Firebase Test Lab for matrix $matrix_id using gsutil"
            download_success=1
        fi
    elif command -v gcloud &> /dev/null && gcloud storage --help >/dev/null 2>&1; then
        if ! retry_command 3 "gcloud storage cp -r \"gs://$bucket/$bucket_path/*\" \"$local_results_dir/\""; then
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
    local report_url=""
    local project_id
    project_id=$(gcloud config get-value project 2>/dev/null)
    if [[ -n "$project_id" ]]; then
        report_url="https://console.firebase.google.com/project/$project_id/testlab/matrices/$matrix_id"
    else
        log "Could not determine project ID for report URL fallback"
    fi

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

    # Generate a timestamp for the state update
    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    # Update the state with the firebaseTestLab object using python3
    local updated_state
    updated_state=$(python3 -c "
import json,sys
state=json.loads(sys.argv[1])
state.setdefault('firebaseTestLab', {})
state['firebaseTestLab']['lastRun'] = {
    'runId': sys.argv[2],
    'artifactId': sys.argv[3],
    'selectedDevice': sys.argv[4],
    'resultDirectory': sys.argv[5],
    'reportUrl': sys.argv[6],
    'timestamp': sys.argv[7],
    'status': sys.argv[8]
}
print(json.dumps(state))
" "$state_json" "$run_id" "$artifact_id" "$device_model" "$results_dir" "$report_url" "$timestamp" "$status"
)

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

    # Step 4: Select an ARM virtual device
    local device_model
    device_model=$(select_arm_device) || {
        log "Failed to select a compatible ARM virtual device."
        exit 1
    }

    log "Selected device model: $device_model"

    # Step 5: Run Firebase Test Lab and collect results
    local bucket="${FIREBASE_TESTLAB_BUCKET:-gs://mine-host-testlab-results}"
    local firebase_info
    firebase_info=$(run_test_lab "$apk_file" "$device_model" "$bucket") || {
        log "Firebase Test Lab run failed."
        # Update state to FAILED
        local failed_matrix_id
        failed_matrix_id=$(date +%s)
        update_beastmode_state "$state_json" "$run_id" "$artifact_id" "$device_model" "$failed_matrix_id" "" "" "FAILED"
        exit 1
    }

    # Parse the Firebase info JSON
    local matrix_id report_url results_dir
    matrix_id=$(echo "$firebase_info" | jq -r '.matrix_id')
    report_url=$(echo "$firebase_info" | jq -r '.report_url')
    results_dir=$(echo "$firebase_info" | jq -r '.results_dir')

    log "Firebase Test Lab run completed successfully"
    log "Matrix ID: $matrix_id"
    log "Report URL: $report_url"
    log "Results directory: $results_dir"

    # Step 6: Update Beast Mode state with the results
    update_beastmode_state "$state_json" "$run_id" "$artifact_id" "$device_model" "$matrix_id" "$report_url" "$results_dir"

    log "Firebase Test Lab workflow completed successfully"
}

# Invoke main
main "$@"