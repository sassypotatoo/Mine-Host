#!/data/data/com.termux/files/usr/glibc/bin/bash
# Firebase Test Lab integration for MineHost Beast Mode v4

set -euo pipefail

# Directory of this script
TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$TOOLS_DIR")"
: "${BEAST_MODE_STATE:=$PROJECT_ROOT/.claude/beastmode_state.json}"
: "${TMP_APK_DIR:=$PROJECT_ROOT/.tmp/firebase-test-lab/apk}"

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
# Command locations (overrideable for testing or custom environments)
: "${GCLOUD_CMD:=gcloud}"
: "${GH_CMD:=gh}"
: "${GSUTIL_CMD:=gsutil}"

# Function to log (to stderr to avoid contaminating stdout)
log() {
    echo "[firebase-test-lab] $*" >&2
}

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
    local state_dir
    state_dir="$(dirname "$BEAST_MODE_STATE")"
    mkdir -p "$state_dir"
    local tmp_file
    tmp_file=$(mktemp "${BEAST_MODE_STATE}.XXXXXX.tmp" 2>/dev/null || echo "${BEAST_MODE_STATE}.tmp")
    echo "$new_state" > "$tmp_file" && mv -f "$tmp_file" "$BEAST_MODE_STATE"
}

# Function to resolve the GitHub repository dynamically (e.g. owner/repo)
get_github_repo() {
    # 1. Check explicit GITHUB_REPOSITORY environment variable
    if [[ -n "${GITHUB_REPOSITORY:-}" && "${GITHUB_REPOSITORY}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
        echo "$GITHUB_REPOSITORY"
        return 0
    fi

    # 2. Check git remote origin URL
    if command -v git &>/dev/null && git rev-parse --is-inside-work-tree &>/dev/null; then
        local remote_url
        remote_url=$(git remote get-url origin 2>/dev/null || true)
        if [[ -n "$remote_url" ]]; then
            local repo="${remote_url#*github.com[:/]}"
            repo="${repo%.git}"
            if [[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
                echo "$repo"
                return 0
            fi
        fi
    fi

    # 3. Check gh CLI repository view
    if command -v "$GH_CMD" &>/dev/null; then
        local gh_repo
        gh_repo=$("$GH_CMD" repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)
        if [[ -n "$gh_repo" && "$gh_repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
            echo "$gh_repo"
            return 0
        fi
    fi

    log "Failed to resolve GitHub repository dynamically (expected owner/repo format)."
    return 1
}

# Function to retry a command with exponential backoff
# $1: max attempts
# $@: command and arguments to run (as an array)
# Returns: the command's stdout on success (via echo), or nothing on failure
retry_command() {
    local max_attempts=$1
    shift
    local cmd=("$@")   # Command and arguments as an array
    local attempt=1
    local delay=${RETRY_INITIAL_DELAY:-2}
    local stdout
    local stderr
    local exit_code=0

    while [[ $attempt -le $max_attempts ]]; do
        local stdout_file stderr_file
        stdout_file=$(mktemp)
        stderr_file=$(mktemp)

        if "${cmd[@]}" >"$stdout_file" 2>"$stderr_file"; then
            exit_code=0
        else
            exit_code=$?
        fi
        stdout=$(<"$stdout_file")
        stderr=$(<"$stderr_file")
        rm -f "$stdout_file" "$stderr_file"

        if [[ $exit_code -eq 0 ]]; then
            echo "$stdout"
            return 0
        else
            log "Attempt $attempt/$max_attempts failed. Command: ${cmd[*]}"
            [[ -n "$stderr" ]] && log "Stderr: $stderr"
            [[ -n "$stdout" ]] && log "Stdout: $stdout"
            attempt=$((attempt+1))
            if [[ $attempt -le $max_attempts ]]; then
                log "Retrying in $delay seconds..."
                sleep "$delay"
                delay=$((delay*2))
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
    if ! command -v "$GH_CMD" &> /dev/null; then
        log "GitHub CLI ($GH_CMD) is not installed or not in PATH."
        return 1
    fi

    # Check for gcloud
    if ! command -v "$GCLOUD_CMD" &> /dev/null; then
        log "Google Cloud SDK ($GCLOUD_CMD) is not installed or not in PATH."
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

    if [[ -z "$bucket" ]]; then
        log "Normalized bucket name is empty after stripping gs:// prefix."
        return 1
    fi

    log "Validating access to bucket: gs://$bucket"
    # Check access using gsutil or gcloud storage
    if command -v "$GSUTIL_CMD" &> /dev/null; then
        if ! "$GSUTIL_CMD" ls "gs://$bucket" > /dev/null 2>&1; then
            log "Cannot access bucket gs://$bucket. Please ensure it exists and you have write permissions."
            return 1
        fi
    elif command -v "$GCLOUD_CMD" &> /dev/null && "$GCLOUD_CMD" storage --help >/dev/null 2>&1; then
        if ! "$GCLOUD_CMD" storage ls "gs://$bucket" > /dev/null 2>&1; then
            log "Cannot access bucket gs://$bucket. Please ensure it exists and you have write permissions."
            return 1
        fi
    else
        log "Neither $GSUTIL_CMD nor $GCLOUD_CMD storage is available. Please install gsutil or ensure gcloud storage is available."
        return 1
    fi

    # Output the normalized bucket for use by the caller
    echo "$bucket"
}

# Function to get the latest successful GitHub Actions run with APK artifact
get_latest_apk_run() {
    local repo="$1"
    log "Fetching latest successful Android/APK GitHub Actions run for repo: $repo"

    # List successful runs on branch main
    local runs_json
    runs_json=$(retry_command 3 "$GH_CMD" run list --repo "$repo" --limit 30 --status success --branch main --json databaseId,workflowName,headSha,conclusion,name) || {
        log "Failed to list workflow runs for repo $repo"
        return 1
    }

    local run_id
    run_id=$(echo "$runs_json" | python3 -c "
import json, sys
try:
    runs = json.load(sys.stdin)
    if not runs:
        sys.exit(1)

    # Must be an Android / APK build workflow that succeeded
    for r in runs:
        if r.get('conclusion') not in (None, '', 'success'):
            continue
        w_name = (r.get('workflowName', '') or '').lower()
        name = (r.get('name', '') or '').lower()
        if 'android' in w_name or 'build apk' in name or 'android ci' in w_name or 'apk' in name or 'build' in w_name:
            print(r.get('databaseId'))
            sys.exit(0)

    # Fail closed: do not select arbitrary unrelated workflows (e.g. docs, website, lint)
    sys.exit(1)
except Exception as e:
    sys.stderr.write(f'Run list parse error: {e}\n')
    sys.exit(1)
") || {
        log "No successful Android/APK build runs found on branch main for repo $repo."
        return 1
    }

    if [[ -z "$run_id" ]]; then
        log "Failed to retrieve a valid run ID."
        return 1
    fi

    # Fetch run details to obtain verified head_sha
    local run_detail
    run_detail=$(retry_command 3 "$GH_CMD" api "repos/$repo/actions/runs/$run_id") || {
        log "Failed to get details for run $run_id from repo $repo"
        return 1
    }

    local commit_sha
    commit_sha=$(echo "$run_detail" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    sha = data.get('head_sha') or data.get('headSha') or ''
    if not sha:
        sys.exit(1)
    print(sha)
except Exception:
    sys.exit(1)
") || {
        log "Failed to extract commit SHA for run $run_id"
        return 1
    }

    # Output JSON object with run_id, commit_sha, repo
    python3 -c "import json, sys; print(json.dumps({'run_id': sys.argv[1], 'commit_sha': sys.argv[2], 'repo': sys.argv[3]}))" "$run_id" "$commit_sha" "$repo"
}

# Function to get the APK artifact ID from a GitHub Actions run
get_apk_artifact_id() {
    local run_id="$1"
    local repo="$2"
    local target_name="${APK_ARTIFACT_NAME}"

    log "Querying artifacts for run $run_id in $repo (looking for exact artifact '$target_name')"

    local artifacts_json
    artifacts_json=$(retry_command 3 "$GH_CMD" api "repos/$repo/actions/runs/$run_id/artifacts") || {
        log "Failed to fetch artifacts for run $run_id"
        return 1
    }

    local artifact_id
    artifact_id=$(echo "$artifacts_json" | python3 -c "
import json, sys
target_name = sys.argv[1].lower()
try:
    data = json.load(sys.stdin)
    artifacts = data.get('artifacts', [])
    if not artifacts:
        sys.exit(1)

    # Priority 1: Exact artifact name match
    for a in artifacts:
        name = (a.get('name') or '').lower()
        if name == target_name and not a.get('expired', False):
            print(a.get('id'))
            sys.exit(0)

    # Priority 2: Name explicitly matching APK artifact conventions (e.g. minehost-debug, app-debug, *.apk)
    for a in artifacts:
        name = (a.get('name') or '').lower()
        if not a.get('expired', False):
            if name.endswith('.apk') or name == 'app-debug' or 'minehost-debug' in name:
                print(a.get('id'))
                sys.exit(0)

    # Fail closed: never fall back to unrelated arbitrary artifacts
    sys.exit(1)
except Exception as e:
    sys.stderr.write(f'Artifact ID parsing error: {e}\n')
sys.exit(1)
" "$target_name") || {
        log "Failed to retrieve a valid artifact ID for run $run_id"
        return 1
    }

    if [[ -z "$artifact_id" ]]; then
        log "No matching artifact ID found in run $run_id"
        return 1
    fi

    echo "$artifact_id"
}

# Function to download the APK artifact from a GitHub Actions run
download_apk_artifact() {
    local run_id="$1"
    local artifact_id="$2"
    local repo="$3"
    local apk_dir="$TMP_APK_DIR"

    log "Downloading exact artifact ID $artifact_id from run $run_id in $repo"
    rm -rf "$apk_dir"
    mkdir -p "$apk_dir"

    local download_success=false

    # Strategy 1: Download exact artifact ZIP via GitHub API endpoint
    local zip_file="$apk_dir/artifact_${artifact_id}.zip"
    if "$GH_CMD" api "repos/$repo/actions/artifacts/$artifact_id/zip" > "$zip_file" 2>/dev/null && [[ -s "$zip_file" ]]; then
        if python3 -c "import zipfile, sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$zip_file" "$apk_dir" 2>/dev/null; then
            download_success=true
            rm -f "$zip_file"
        fi
    fi

    # Strategy 2: Fallback to gh run download with specific artifact name ONLY
    if [[ "$download_success" != "true" ]]; then
        if "$GH_CMD" run download "$run_id" --repo "$repo" --dir "$apk_dir" --name "${APK_ARTIFACT_NAME}" 2>/dev/null; then
            download_success=true
        fi
    fi

    # If exact artifact download strategies failed, FAIL CLOSED. Do NOT download arbitrary other artifacts.
    if [[ "$download_success" != "true" ]]; then
        log "Exact artifact download failed for artifact ID $artifact_id."
        return 1
    fi

    # Find the .apk file inside apk_dir
    local apk_file
    apk_file=$(python3 -c "
import os, sys
apk_dir = sys.argv[1]
found = []
for root, dirs, files in os.walk(apk_dir):
    for f in files:
        if f.endswith('.apk'):
            full_path = os.path.join(root, f)
            if os.path.getsize(full_path) > 0:
                found.append(full_path)
if found:
    print(found[0])
    sys.exit(0)
sys.exit(1)
" "$apk_dir" 2>/dev/null || true)

    if [[ -z "$apk_file" || ! -s "$apk_file" ]]; then
        log "Failed to locate non-empty APK file in downloaded artifact directory $apk_dir"
        return 1
    fi

    echo "$apk_file"
}

# Function to validate the downloaded APK fail-closed
validate_apk() {
    local apk_file="$1"

    # Check if APK file exists and is non-empty
    if [[ ! -f "$apk_file" ]] || [[ ! -s "$apk_file" ]]; then
        log "APK file does not exist or is empty: $apk_file"
        return 1
    fi

    local package_name="${APK_PACKAGE_NAME}"
    local native_abi="${APK_NATIVE_ABI}"
    local min_sdk_version="${APK_MIN_SDK_VERSION}"

    log "Validating APK: $apk_file (expected pkg=$package_name, abi=$native_abi, minSdk>=$min_sdk_version)"

    # Pure Python fail-closed validator for ZIP structure, exact ABI, and binary AndroidManifest.xml
    python3 - "$apk_file" "$package_name" "$native_abi" "$min_sdk_version" <<'PY_VALIDATE'
import sys, os, zipfile, struct

apk_path, expected_pkg, expected_abi, min_sdk_str = sys.argv[1:5]
min_sdk_req = int(min_sdk_str)

if not os.path.isfile(apk_path) or os.path.getsize(apk_path) == 0:
    sys.stderr.write(f"APK file missing or empty: {apk_path}\n")
    sys.exit(1)

try:
    zf = zipfile.ZipFile(apk_path, 'r')
except Exception as e:
    sys.stderr.write(f"Failed to open APK as ZIP archive: {e}\n")
    sys.exit(1)

namelist = zf.namelist()

# 1. Verify Native Libraries (exact arm64-v8a must be present, fail closed if missing)
# Must not accept armeabi-v7a as arm64
arm64_libs = [name for name in namelist if name.startswith(f"lib/{expected_abi}/") and name.endswith(".so")]
if not arm64_libs:
    sys.stderr.write(f"APK verification failed: No native libraries found for ABI '{expected_abi}'\n")
    sys.exit(1)

# Ensure launcher library or valid native shared object exists and has non-zero size
valid_so_found = False
for lib_name in arm64_libs:
    info = zf.getinfo(lib_name)
    if info.file_size > 0:
        valid_so_found = True
        break

if not valid_so_found:
    sys.stderr.write(f"APK verification failed: Native libraries in lib/{expected_abi}/ are empty (0 bytes)\n")
    sys.exit(1)

# 2. Extract and Parse AndroidManifest.xml
if "AndroidManifest.xml" not in namelist:
    sys.stderr.write("APK verification failed: AndroidManifest.xml missing from APK\n")
    sys.exit(1)

manifest_data = zf.read("AndroidManifest.xml")

def parse_axml(data):
    if len(data) < 8:
        raise ValueError("Manifest data too short")
    magic, file_size = struct.unpack_from('<II', data, 0)
    if magic != 0x00080003:
        raise ValueError(f"Invalid AXML magic header: {hex(magic)}")

    offset = 8
    strings = []
    package = None
    min_sdk = None

    while offset < len(data):
        if offset + 8 > len(data):
            break
        chunk_type, header_size, chunk_size = struct.unpack_from('<HHI', data, offset)
        if chunk_size <= 0:
            break

        if chunk_type == 0x0001:  # RES_STRING_POOL_TYPE
            string_count, style_count, flags, strings_start, styles_start = struct.unpack_from('<IIIII', data, offset + 8)
            is_utf8 = bool(flags & (1 << 8))
            offsets_start = offset + 28
            string_offsets = [struct.unpack_from('<I', data, offsets_start + i * 4)[0] for i in range(string_count)]
            data_start = offset + strings_start
            for s_off in string_offsets:
                pos = data_start + s_off
                if pos >= len(data):
                    strings.append('')
                    continue
                if is_utf8:
                    if pos < len(data):
                        u16_len = data[pos]
                        pos += 1
                        if u16_len & 0x80:
                            pos += 1
                    if pos < len(data):
                        u8_len = data[pos]
                        pos += 1
                        if u8_len & 0x80:
                            pos += 1
                    end = data.find(b'\x00', pos)
                    if end != -1:
                        strings.append(data[pos:end].decode('utf-8', errors='replace'))
                    else:
                        strings.append('')
                else:
                    if pos + 2 <= len(data):
                        u16_len = struct.unpack_from('<H', data, pos)[0]
                        pos += 2
                        if u16_len & 0x8000:
                            pos += 2
                        s_bytes = data[pos:pos + u16_len * 2]
                        strings.append(s_bytes.decode('utf-16le', errors='replace'))
                    else:
                        strings.append('')

        elif chunk_type == 0x0102:  # RES_XML_START_ELEMENT_TYPE
            if offset + 36 <= len(data):
                ns_idx, name_idx, attr_start, attr_size, attr_count = struct.unpack_from('<IIHHH', data, offset + 16)
                elem_name = strings[name_idx] if name_idx < len(strings) else ''
                attr_base = offset + 16 + attr_start if attr_start > 0 else offset + 36
                for i in range(attr_count):
                    pos = attr_base + i * attr_size
                    if pos + 20 > len(data):
                        break
                    ans_idx, aname_idx, araw_val_idx, atype_size, ares0, atype, adata = struct.unpack_from('<IIIHBBI', data, pos)
                    aname = strings[aname_idx] if aname_idx < len(strings) else ''

                    if elem_name == 'manifest' and aname == 'package':
                        if araw_val_idx != 0xFFFFFFFF and araw_val_idx < len(strings):
                            package = strings[araw_val_idx]
                        elif atype == 0x03 and adata < len(strings):
                            package = strings[adata]
                    elif elem_name == 'uses-sdk' and aname in ('minSdkVersion', 'min_sdk_version'):
                        if atype in (0x10, 0x11):  # int dec / hex
                            min_sdk = adata
                        elif araw_val_idx != 0xFFFFFFFF and araw_val_idx < len(strings):
                            try:
                                min_sdk = int(strings[araw_val_idx])
                            except ValueError:
                                pass
        offset += chunk_size
    return package, min_sdk, strings

try:
    pkg, min_sdk, strings = parse_axml(manifest_data)
except Exception as e:
    sys.stderr.write(f"Binary XML parsing failed: {e}\n")
    sys.exit(1)

# Package name verification: MUST be exact match on manifest package attribute
if not pkg:
    sys.stderr.write("APK verification failed: Manifest package attribute missing or unparseable\n")
    sys.exit(1)

if pkg != expected_pkg:
    sys.stderr.write(f"APK verification failed: Package '{pkg}' does not match expected '{expected_pkg}'\n")
    sys.exit(1)

# Min SDK verification: FAIL CLOSED if missing or unparseable
if min_sdk is None:
    sys.stderr.write("APK verification failed: minSdkVersion missing or unparseable from uses-sdk in AndroidManifest.xml\n")
    sys.exit(1)

if min_sdk < min_sdk_req:
    sys.stderr.write(f"APK verification failed: minSdkVersion {min_sdk} is less than required {min_sdk_req}\n")
    sys.exit(1)

sys.exit(0)
PY_VALIDATE

    if [[ $? -ne 0 ]]; then
        log "APK validation failed (fail-closed check)."
        return 1
    fi

    log "APK validation passed: package, ARM64 native ABI, and minSdk verified successfully."
    return 0
}

# Function to select an ARM virtual device from Firebase Test Lab catalog
select_arm_device() {
    log "Selecting compatible ARM virtual device from Firebase Test Lab catalog"

    local models_json
    models_json=$(retry_command 3 "$GCLOUD_CMD" firebase test android models list --filter="formFactor=VIRTUAL" --format='json') || {
        log "Failed to list virtual models from Firebase Test Lab"
        return 1
    }

    local device_model
    device_model=$(echo "$models_json" | python3 -c "
import sys, json

try:
    data = json.load(sys.stdin)
    for device in data:
        # 1. Must be VIRTUAL form factor
        if device.get('formFactor') != 'VIRTUAL':
            continue
        # 2. Must support exact arm64-v8a ABI
        abis = device.get('supportedAbis', [])
        if 'arm64-v8a' not in abis:
            continue
        # 3. Must not be deprecated or disabled or unavailable
        tags = [str(t).lower() for t in (device.get('tags') or [])]
        if any(bad in tags for bad in ('deprecated', 'disabled', 'unavailable')):
            continue
        # 4. Must be enabled if enabled flag is present
        if device.get('enabled') is False:
            continue

        print(device.get('id'))
        sys.exit(0)
except Exception as e:
    sys.stderr.write(f'Model selection parse error: {e}\n')
sys.exit(1)
") || {
        log "No enabled ARM64 virtual device found in Firebase Test Lab catalog."
        return 1
    }

    if [[ -z "$device_model" ]]; then
        log "Failed to select an ARM64 virtual device model."
        return 1
    fi

    log "Selected ARM64 virtual device model: $device_model. Describing supported API levels..."

    local model_desc_json
    model_desc_json=$(retry_command 3 "$GCLOUD_CMD" firebase test android models describe "$device_model" --format='json') || {
        log "Failed to describe model $device_model"
        return 1
    }

    local api_level
    api_level=$(echo "$model_desc_json" | python3 -c "
import sys, json, os

min_sdk_version = int(os.environ.get('APK_MIN_SDK_VERSION', '26'))
try:
    data = json.load(sys.stdin)
    api_levels = data.get('supportedApiLevels', [])
    valid_levels = []
    for lvl in api_levels:
        try:
            val = int(lvl)
            if val >= min_sdk_version:
                valid_levels.append(val)
        except (ValueError, TypeError):
            continue
    if not valid_levels:
        sys.exit(1)
    print(max(valid_levels))
    sys.exit(0)
except Exception:
    sys.exit(1)
") || {
        log "No supported API level >= ${APK_MIN_SDK_VERSION} found for device $device_model"
        return 1
    }

    if [[ -z "$api_level" ]]; then
        log "No supported API level >= ${APK_MIN_SDK_VERSION} found for device $device_model"
        return 1
    fi

    echo "$device_model,$api_level"
}

# Function to run Firebase Test Lab and collect results
run_test_lab() {
    local apk_file="$1"
    local device_model_version="$2"  # Format: model,apiLevel
    local bucket="$3"

    local device_model
    local api_level
    device_model=$(echo "$device_model_version" | cut -d',' -f1)
    api_level=$(echo "$device_model_version" | cut -d',' -f2)

    local timestamp
    timestamp=$(date +%s)
    local bucket_path="firebase-test-lab-results/$timestamp"
    local local_results_dir="$PROJECT_ROOT/.claude/testlab-results/$timestamp"
    mkdir -p "$local_results_dir"

    log "Submitting APK to Firebase Test Lab (model=$device_model, apiLevel=$api_level, results=gs://$bucket/$bucket_path)"

    local test_output
    test_output=$("$GCLOUD_CMD" firebase test android run \
        --type "${TESTLAB_TYPE}" \
        --app "$apk_file" \
        --device model="$device_model",version="$api_level",locale="${TESTLAB_LOCALE}",orientation="${TESTLAB_ORIENTATION}" \
        --timeout "${TESTLAB_TIMEOUT}s" \
        --results-bucket="gs://$bucket" \
        --results-dir="$bucket_path" \
        2>&1) || {
        log "Firebase Test Lab submission failed: $test_output"
        local m_id=""
        m_id=$(echo "$test_output" | python3 -c "
import sys, re, json
text = sys.stdin.read()
try:
    data = json.loads(text)
    m = data.get('testMatrixId') or data.get('matrixId')
    if m:
        print(m)
        sys.exit(0)
except Exception:
    pass
m = re.search(r'(?:Matrix ID:\s*|Test matrix\s*\[|Matrix\s*\[|/testMatrices/)([a-zA-Z0-9_-]+)', text)
if m:
    print(m.group(1))
    sys.exit(0)
sys.exit(1)
" 2>/dev/null || true)
        if [[ -n "$m_id" ]]; then
            python3 -c "import json, sys; print(json.dumps({'matrix_id': sys.argv[1], 'error': 'submission_failed'}))" "$m_id"
        fi
        return 1
    }

    log "Firebase Test Lab execution finished. Parsing matrix ID..."

    local matrix_id
    matrix_id=$(echo "$test_output" | python3 -c "
import sys, re, json
text = sys.stdin.read()
try:
    data = json.loads(text)
    m_id = data.get('testMatrixId') or data.get('matrixId')
    if m_id:
        print(m_id)
        sys.exit(0)
except Exception:
    pass

m = re.search(r'(?:Matrix ID:\s*|Test matrix\s*\[|Matrix\s*\[|/testMatrices/)([a-zA-Z0-9_-]+)', text)
if m:
    print(m.group(1))
    sys.exit(0)
sys.exit(1)
") || {
        log "Failed to extract Matrix ID from Firebase Test Lab output. Aborting without fallback."
        return 1
    }

    if [[ -z "$matrix_id" ]]; then
        log "Extracted Matrix ID is empty. Aborting."
        return 1
    fi

    log "Firebase Test Lab matrix ID: $matrix_id"

    # Download results from GCS bucket to local directory
    log "Downloading test results from gs://$bucket/$bucket_path to $local_results_dir"
    local download_ok=false
    if command -v "$GSUTIL_CMD" &> /dev/null; then
        if "$GSUTIL_CMD" -m cp -r "gs://$bucket/$bucket_path/*" "$local_results_dir/" >/dev/null 2>&1; then
            download_ok=true
        fi
    fi

    if [[ "$download_ok" != "true" ]] && command -v "$GCLOUD_CMD" &> /dev/null; then
        if "$GCLOUD_CMD" storage cp -r "gs://$bucket/$bucket_path/*" "$local_results_dir/" >/dev/null 2>&1; then
            download_ok=true
        fi
    fi

    if [[ "$download_ok" != "true" ]]; then
        log "Failed to download results for matrix $matrix_id from gs://$bucket/$bucket_path"
        python3 -c "import json, sys; print(json.dumps({'matrix_id': sys.argv[1], 'error': 'download_failed'}))" "$matrix_id"
        return 1
    fi

    # Verify downloaded artifacts
    local has_images=false
    local has_video=false
    local has_logs=false

    if find "$local_results_dir" -type f \( -name "*.png" -o -name "*.jpg" \) | grep -q .; then
        has_images=true
    fi
    if find "$local_results_dir" -type f \( -name "*.mp4" -o -name "*video*" \) | grep -q .; then
        has_video=true
    fi
    if find "$local_results_dir" -type f \( -name "*log*.txt" -o -name "*.log" -o -name "*logcat*" \) | grep -q .; then
        has_logs=true
    fi

    if [[ "$has_images" != "true" || "$has_video" != "true" || "$has_logs" != "true" ]]; then
        log "Missing required test artifacts in $local_results_dir (images=$has_images, video=$has_video, logs=$has_logs)"
        python3 -c "import json, sys; print(json.dumps({'matrix_id': sys.argv[1], 'error': 'artifacts_incomplete'}))" "$matrix_id"
        return 1
    fi

    # Retrieve real report URL from matrix describe JSON
    local matrix_desc
    matrix_desc=$("$GCLOUD_CMD" firebase test android matrices describe "$matrix_id" --format='json' 2>/dev/null || true)
    local report_url
    report_url=$(echo "$matrix_desc" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    url = (
        data.get('resultStorage', {}).get('resultsUrl') or
        data.get('webUrl') or
        data.get('clientInfo', {}).get('details', {}).get('url') or
        ''
    )
    if url.startswith('http://') or url.startswith('https://'):
        print(url)
    else:
        print('')
except Exception:
    print('')
")

    # Output JSON summary
    python3 -c "import json, sys; print(json.dumps({'matrix_id': sys.argv[1], 'report_url': sys.argv[2], 'results_dir': sys.argv[3]}))" "$matrix_id" "$report_url" "$local_results_dir"
}

# Function to update Beast Mode state atomically with Firebase Test Lab result
update_beastmode_state() {
    local state_json="$1"
    local run_id="$2"
    local artifact_id="$3"
    local device_model="$4"
    local matrix_id="$5"
    local report_url="$6"
    local results_dir="$7"
    local status="${8:-COMPLETED}"
    local commit_sha="${9:-}"

    local timestamp
    timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    local updated_state
    updated_state=$(python3 - "$state_json" "$run_id" "$artifact_id" "$device_model" "$matrix_id" "$report_url" "$results_dir" "$status" "$commit_sha" "$timestamp" <<'PY_STATE'
import sys, json

raw_state, run_id, artifact_id, device_model, matrix_id, report_url, results_dir, status, commit_sha, ts = sys.argv[1:11]

try:
    state = json.loads(raw_state) if raw_state.strip() else {}
except Exception:
    state = {}

if 'firebaseTestLab' not in state or not isinstance(state['firebaseTestLab'], dict):
    state['firebaseTestLab'] = {}

state['firebaseTestLab']['lastRun'] = {
    'runId': run_id,
    'artifactId': artifact_id,
    'selectedDevice': device_model,
    'matrixId': matrix_id if matrix_id else None,
    'resultDirectory': results_dir if results_dir else None,
    'reportUrl': report_url if report_url else None,
    'timestamp': ts,
    'status': status,
    'commitSha': commit_sha,
}
state['timestamp'] = ts

# Transition workflowStatus from RUNNING to COMPLETE or FAILED
if state.get('currentTask') == 'Firebase Test Lab run' or state.get('workflowStatus') == 'RUNNING':
    if status == 'COMPLETED':
        state['workflowStatus'] = 'COMPLETE'
        state['phase'] = 'IDLE'
    else:
        state['workflowStatus'] = 'FAILED'
        state['phase'] = 'IDLE'

# Ensure physical runtime verification is NOT set to VERIFIED
if state.get('physicalVerification') == 'VERIFIED':
    state['physicalVerification'] = 'UNVERIFIED'

print(json.dumps(state, indent=2))
PY_STATE
)

    write_beastmode_state "$updated_state"
}

# Cleanup function on exit
cleanup() {
    log "Cleaning up temporary APK directory: $TMP_APK_DIR"
    rm -rf "$TMP_APK_DIR"
}

# Main workflow function
main() {
    local subcommand="${1:-}"
    shift || true

    if [[ "$subcommand" != "run" ]]; then
        log "Usage: $0 run"
        exit 1
    fi

    log "Starting Firebase Test Lab workflow (strict isolation, no fake state)"
    trap cleanup EXIT

    # 0. Validate prerequisites and get normalized bucket
    local bucket
    bucket=$(validate_prerequisites) || {
        log "Prerequisites validation failed."
        exit 1
    }

    # 1. Resolve GitHub repository dynamically
    local repo
    repo=$(get_github_repo) || {
        log "Failed to resolve GitHub repository dynamically."
        exit 1
    }
    log "Resolved GitHub repository: $repo"

    # Read current state
    local state_json
    state_json=$(read_beastmode_state)

    # 2. Get latest successful run and commit SHA
    local run_info
    run_info=$(get_latest_apk_run "$repo") || {
        log "Failed to get latest successful GitHub Actions run with APK artifact."
        exit 1
    }

    local run_id commit_sha
    run_id=$(echo "$run_info" | python3 -c "import json, sys; print(json.load(sys.stdin)['run_id'])")
    commit_sha=$(echo "$run_info" | python3 -c "import json, sys; print(json.load(sys.stdin)['commit_sha'])")

    log "Target run ID: $run_id (commit SHA: $commit_sha)"

    # 3. Get APK artifact ID
    local artifact_id
    artifact_id=$(get_apk_artifact_id "$run_id" "$repo") || {
        log "Failed to resolve APK artifact ID from run $run_id"
        exit 1
    }
    log "Target artifact ID: $artifact_id"

    # 4. Download APK artifact
    local apk_file
    apk_file=$(download_apk_artifact "$run_id" "$artifact_id" "$repo") || {
        log "Failed to download APK artifact."
        exit 1
    }
    log "Downloaded APK file: $apk_file"

    # 5. Validate APK
    validate_apk "$apk_file" || {
        log "APK validation failed."
        exit 1
    }

    # 6. Select ARM64 virtual device
    local device_model_version
    device_model_version=$(select_arm_device) || {
        log "Failed to select compatible ARM64 virtual device."
        exit 1
    }
    log "Selected device: $device_model_version"

    # 7. Run Firebase Test Lab
    local firebase_info=""
    local run_err=0
    firebase_info=$(run_test_lab "$apk_file" "$device_model_version" "$bucket") || run_err=$?

    if [[ $run_err -ne 0 ]]; then
        log "Firebase Test Lab run failed."
        local preserved_matrix_id=""
        if [[ -n "$firebase_info" ]]; then
            preserved_matrix_id=$(echo "$firebase_info" | python3 -c "import json, sys; print(json.load(sys.stdin).get('matrix_id') or '')" 2>/dev/null || true)
        fi
        update_beastmode_state "$state_json" "$run_id" "$artifact_id" "$device_model_version" "$preserved_matrix_id" "" "" "FAILED" "$commit_sha"
        exit 1
    fi

    local matrix_id report_url results_dir
    matrix_id=$(echo "$firebase_info" | python3 -c "import json, sys; print(json.load(sys.stdin)['matrix_id'])")
    report_url=$(echo "$firebase_info" | python3 -c "import json, sys; print(json.load(sys.stdin)['report_url'])")
    results_dir=$(echo "$firebase_info" | python3 -c "import json, sys; print(json.load(sys.stdin)['results_dir'])")

    log "Firebase Test Lab completed successfully: matrix=$matrix_id"

    # 8. Update Beast Mode state
    update_beastmode_state "$state_json" "$run_id" "$artifact_id" "$device_model_version" "$matrix_id" "$report_url" "$results_dir" "COMPLETED" "$commit_sha"
    log "Beast Mode state updated successfully."
}

# Invoke main if executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
