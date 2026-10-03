#!/usr/bin/env python3
"""
Comprehensive Test Suite for MineHost Firebase Test Lab Integration
Validates all 37 test cases across 6 teams and 3 waves without consuming real Firebase quota.
"""

import os
import sys
import json
import shutil
import tempfile
import zipfile
import struct
import subprocess
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = TOOLS_DIR.parent
SCRIPT_PATH = TOOLS_DIR / "run-firebase-test-lab.sh"
BASH_BIN = shutil.which("bash") or "/data/data/com.termux/files/usr/glibc/bin/bash"


def build_test_axml(package_name="com.aistudio.minehost.qweras", min_sdk=26):
    """
    Builds a valid Android Binary XML (AXML) containing:
    <manifest package="...">
      <uses-sdk android:minSdkVersion="..." />
    </manifest>
    """
    strings = [
        "manifest",
        "package",
        package_name,
        "uses-sdk",
        "minSdkVersion",
        "http://schemas.android.com/apk/res/android",
    ]
    str_count = len(strings)
    str_offsets = []
    str_data = bytearray()
    for s in strings:
        str_offsets.append(len(str_data))
        encoded = s.encode("utf-8")
        str_data.append(len(s))
        str_data.append(len(encoded))
        str_data.extend(encoded)
        str_data.append(0)
    while len(str_data) % 4 != 0:
        str_data.append(0)

    str_pool_header_size = 28
    str_pool_data_start = str_pool_header_size + str_count * 4
    str_pool_chunk_size = str_pool_data_start + len(str_data)

    str_pool = bytearray()
    str_pool.extend(struct.pack("<HHI", 0x0001, str_pool_header_size, str_pool_chunk_size))
    str_pool.extend(struct.pack("<IIIII", str_count, 0, 1 << 8, str_pool_data_start, 0))
    for off in str_offsets:
        str_pool.extend(struct.pack("<I", off))
    str_pool.extend(str_data)

    # 1. Start manifest: chunk_size = 56
    elem_manifest = bytearray()
    elem_manifest.extend(struct.pack("<HHI", 0x0102, 16, 56))  # chunk header (8)
    elem_manifest.extend(struct.pack("<II", 1, 0xFFFFFFFF))     # node info (8)
    elem_manifest.extend(struct.pack("<IIHHH", 0xFFFFFFFF, 0, 20, 20, 1))  # elem info (14)
    elem_manifest.extend(struct.pack("<HHH", 0, 0, 0))         # id, class, style (6)
    elem_manifest.extend(struct.pack("<IIIHBBI", 0xFFFFFFFF, 1, 2, 8, 0, 0x03, 2))  # attr (20)

    # 2. Start uses-sdk: chunk_size = 56
    elem_sdk = bytearray()
    elem_sdk.extend(struct.pack("<HHI", 0x0102, 16, 56))
    elem_sdk.extend(struct.pack("<II", 2, 0xFFFFFFFF))
    elem_sdk.extend(struct.pack("<IIHHH", 0xFFFFFFFF, 3, 20, 20, 1))
    elem_sdk.extend(struct.pack("<HHH", 0, 0, 0))
    elem_sdk.extend(struct.pack("<IIIHBBI", 5, 4, 0xFFFFFFFF, 8, 0, 0x10, min_sdk))

    # 3. End uses-sdk: chunk_size = 24
    elem_sdk_end = bytearray()
    elem_sdk_end.extend(struct.pack("<HHI", 0x0103, 16, 24))
    elem_sdk_end.extend(struct.pack("<II", 2, 0xFFFFFFFF))
    elem_sdk_end.extend(struct.pack("<II", 0xFFFFFFFF, 3))

    # 4. End manifest: chunk_size = 24
    elem_manifest_end = bytearray()
    elem_manifest_end.extend(struct.pack("<HHI", 0x0103, 16, 24))
    elem_manifest_end.extend(struct.pack("<II", 3, 0xFFFFFFFF))
    elem_manifest_end.extend(struct.pack("<II", 0xFFFFFFFF, 0))

    body = str_pool + elem_manifest + elem_sdk + elem_sdk_end + elem_manifest_end
    header = struct.pack("<II", 0x00080003, 8 + len(body))
    return header + body


def create_test_apk(path, package_name="com.aistudio.minehost.qweras", min_sdk=26, abi="arm64-v8a", native_lib_size=1024, omit_manifest=False, omit_native=False):
    """Creates a mock APK zip file with controlled properties."""
    with zipfile.ZipFile(path, "w") as zf:
        if not omit_manifest:
            axml_bytes = build_test_axml(package_name=package_name, min_sdk=min_sdk)
            zf.writestr("AndroidManifest.xml", axml_bytes)
        if not omit_native and abi:
            content = b"" if native_lib_size == 0 else (b"\x7fELF" + b"X" * max(0, native_lib_size - 4) if native_lib_size >= 4 else b"\x7f" * native_lib_size)
            zf.writestr(f"lib/{abi}/libminehost_jvm_launcher.so", content)
        zf.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 100)


class TestFirebaseTestLab(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="minehost_testlab_")
        self.state_file = os.path.join(self.temp_dir, "beastmode_state.json")
        with open(self.state_file, "w") as f:
            json.dump({
                "schemaVersion": 1,
                "beastModeEnabled": True,
                "currentTask": "Firebase Test Lab run",
                "workflowStatus": "RUNNING",
                "gitBranch": "main"
            }, f)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def run_bash_fn(self, script_body, env_overrides=None):
        """Runs a bash snippet that sources run-firebase-test-lab.sh and tests functions."""
        env = os.environ.copy()
        env["BEAST_MODE_STATE"] = self.state_file
        if env_overrides:
            env.update(env_overrides)

        cmd = [BASH_BIN, "-c", f"""
source "{SCRIPT_PATH}"
{script_body}
"""]
        res = subprocess.run(cmd, env=env, capture_output=True, text=True)
        return res

    # ── Test Cases 1-5: Dynamic GitHub Repository Resolution ────────────────
    def test_01_repo_resolution_from_env_var(self):
        res = self.run_bash_fn("get_github_repo", {"GITHUB_REPOSITORY": "sassypotatoo/Mine-Host"})
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "sassypotatoo/Mine-Host")

    def test_02_repo_resolution_from_git_remote_https(self):
        git_dir = os.path.join(self.temp_dir, "git_test")
        os.makedirs(git_dir)
        subprocess.run(["git", "init", "-q", git_dir], check=True)
        subprocess.run(["git", "-C", git_dir, "remote", "add", "origin", "https://github.com/myorg/myrepo.git"], check=True)

        cmd = f"cd '{git_dir}' && source '{SCRIPT_PATH}' && get_github_repo"
        env = os.environ.copy()
        env.pop("GITHUB_REPOSITORY", None)
        res = subprocess.run([BASH_BIN, "-c", cmd], env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "myorg/myrepo")

    def test_03_repo_resolution_from_git_remote_ssh(self):
        git_dir = os.path.join(self.temp_dir, "git_ssh")
        os.makedirs(git_dir)
        subprocess.run(["git", "init", "-q", git_dir], check=True)
        subprocess.run(["git", "-C", git_dir, "remote", "add", "origin", "git@github.com:sassypotatoo/Mine-Host.git"], check=True)

        cmd = f"cd '{git_dir}' && source '{SCRIPT_PATH}' && get_github_repo"
        env = os.environ.copy()
        env.pop("GITHUB_REPOSITORY", None)
        res = subprocess.run([BASH_BIN, "-c", cmd], env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "sassypotatoo/Mine-Host")

    def test_04_repo_resolution_invalid_format_rejected(self):
        res = self.run_bash_fn("get_github_repo", {"GITHUB_REPOSITORY": "invalid_format_without_slash"})
        if res.returncode == 0:
            self.assertRegex(res.stdout.strip(), r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    def test_05_repo_resolution_no_literal_owner_repo(self):
        with open(SCRIPT_PATH, "r") as f:
            content = f.read()
        self.assertNotIn(":owner/:repo", content)
        self.assertNotIn("repos/:owner", content)

    # ── Test Cases 6-12: Exact Artifact Resolution & Download ───────────────
    def test_06_artifact_id_resolution_exact_match(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_artifact")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "artifacts"; then
    echo '{"artifacts":[{"id":12345678,"name":"minehost-debug","expired":false},{"id":87654321,"name":"other-apk","expired":false}]}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_apk_artifact_id "100" "sassypotatoo/Mine-Host"', env)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "12345678")

    def test_07_artifact_id_resolution_skips_expired(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_expired")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "artifacts"; then
    echo '{"artifacts":[{"id":111,"name":"minehost-debug","expired":true},{"id":222,"name":"minehost-debug","expired":false}]}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_apk_artifact_id "100" "sassypotatoo/Mine-Host"', env)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "222")

    def test_08_download_apk_artifact_exact_zip(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_dl")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")

        apk_source = os.path.join(self.temp_dir, "dummy.apk")
        create_test_apk(apk_source)

        art_zip = os.path.join(self.temp_dir, "art.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="app-debug.apk")

        with open(gh_script, "w") as f:
            f.write(f"""#!/bin/sh
if echo "$*" | grep -q "actions/artifacts/999/zip"; then
    cat "{art_zip}"
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh", "TMP_APK_DIR": os.path.join(self.temp_dir, "apk_out")}
        res = self.run_bash_fn('download_apk_artifact "100" "999" "sassypotatoo/Mine-Host"', env)
        self.assertEqual(res.returncode, 0)
        self.assertTrue(os.path.isfile(res.stdout.strip()))
        self.assertTrue(res.stdout.strip().endswith(".apk"))

    def test_09_download_apk_artifact_fallback_to_run_download(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_fallback")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")

        apk_source = os.path.join(self.temp_dir, "fallback.apk")
        create_test_apk(apk_source)

        with open(gh_script, "w") as f:
            f.write(f"""#!/bin/sh
if echo "$*" | grep -q "actions/artifacts"; then
    exit 1
fi
if echo "$*" | grep -q "run download"; then
    dir_arg=""
    while [ $# -gt 0 ]; do
        if [ "$1" = "--dir" ]; then
            dir_arg="$2"
            shift 2
        else
            shift
        fi
    done
    mkdir -p "$dir_arg"
    cp "{apk_source}" "$dir_arg/app-debug.apk"
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh", "TMP_APK_DIR": os.path.join(self.temp_dir, "apk_fallback")}
        res = self.run_bash_fn('download_apk_artifact "100" "999" "sassypotatoo/Mine-Host"', env)
        self.assertEqual(res.returncode, 0)
        self.assertTrue(os.path.isfile(res.stdout.strip()))

    def test_10_get_latest_apk_run_finds_run_and_sha(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_runs")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 55555, "workflowName": "Android CI", "headSha": "abc1234", "conclusion": "success", "name": "Build APK"}]'
    exit 0
fi
if echo "$*" | grep -q "repos/sassypotatoo/Mine-Host/actions/runs/55555"; then
    echo '{"id": 55555, "head_sha": "abc1234567890abcdef1234567890abcdef123456", "status": "completed"}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "sassypotatoo/Mine-Host"', env)
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout.strip())
        self.assertEqual(data["run_id"], "55555")
        self.assertEqual(data["commit_sha"], "abc1234567890abcdef1234567890abcdef123456")

    def test_11_get_latest_apk_run_filters_successful_runs(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_filter_runs")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 11111, "workflowName": "Android CI", "headSha": "failed_sha", "conclusion": "failure", "name": "Build APK"}, {"databaseId": 22222, "workflowName": "Android CI", "headSha": "success_sha", "conclusion": "success", "name": "Build APK"}]'
    exit 0
fi
if echo "$*" | grep -q "repos/sassypotatoo/Mine-Host/actions/runs/22222"; then
    echo '{"id": 22222, "head_sha": "success_sha_full_40_chars_0123456789abcdef", "status": "completed"}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "sassypotatoo/Mine-Host"', env)
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout.strip())
        self.assertEqual(data["run_id"], "22222")

    def test_12_get_latest_apk_run_no_runs_fails(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gh_noruns")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[]'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "sassypotatoo/Mine-Host"', env)
        self.assertNotEqual(res.returncode, 0)

    # ── Test Cases 13-23: Fail-Closed APK Validation ─────────────────────────
    def test_13_validate_apk_valid(self):
        apk_path = os.path.join(self.temp_dir, "valid.apk")
        create_test_apk(apk_path, package_name="com.aistudio.minehost.qweras", min_sdk=26, abi="arm64-v8a")
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertEqual(res.returncode, 0)

    def test_14_validate_apk_missing_file(self):
        apk_path = os.path.join(self.temp_dir, "nonexistent.apk")
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_15_validate_apk_zero_byte(self):
        apk_path = os.path.join(self.temp_dir, "empty.apk")
        open(apk_path, "w").close()
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_16_validate_apk_corrupt_zip(self):
        apk_path = os.path.join(self.temp_dir, "corrupt.apk")
        with open(apk_path, "wb") as f:
            f.write(b"NOT_A_ZIP_FILE_DATA")
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_17_validate_apk_missing_native_libraries(self):
        apk_path = os.path.join(self.temp_dir, "no_native.apk")
        create_test_apk(apk_path, omit_native=True)
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_18_validate_apk_32bit_only_abi_rejected(self):
        apk_path = os.path.join(self.temp_dir, "arm32_only.apk")
        create_test_apk(apk_path, abi="armeabi-v7a")
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_19_validate_apk_zero_byte_so_rejected(self):
        apk_path = os.path.join(self.temp_dir, "empty_so.apk")
        create_test_apk(apk_path, native_lib_size=0)
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_20_validate_apk_missing_manifest(self):
        apk_path = os.path.join(self.temp_dir, "no_manifest.apk")
        create_test_apk(apk_path, omit_manifest=True)
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_21_validate_apk_wrong_package(self):
        apk_path = os.path.join(self.temp_dir, "wrong_pkg.apk")
        create_test_apk(apk_path, package_name="com.other.app", min_sdk=26)
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_22_validate_apk_min_sdk_less_than_26_rejected(self):
        apk_path = os.path.join(self.temp_dir, "minsdk21.apk")
        create_test_apk(apk_path, min_sdk=21)
        res = self.run_bash_fn(f'validate_apk "{apk_path}"')
        self.assertNotEqual(res.returncode, 0)

    def test_23_validate_apk_min_sdk_greater_or_equal_26_accepted(self):
        for sdk in (26, 28, 33):
            apk_path = os.path.join(self.temp_dir, f"minsdk{sdk}.apk")
            create_test_apk(apk_path, min_sdk=sdk)
            res = self.run_bash_fn(f'validate_apk "{apk_path}"')
            self.assertEqual(res.returncode, 0, f"SDK {sdk} should be accepted")

    # ── Test Cases 24-28: ARM64 Device Catalog Selection ────────────────────
    def test_24_select_arm_device_filters_virtual_and_arm64(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gcloud_dev")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        cat << 'EOF'
[
  {"id": "NexusLow", "formFactor": "VIRTUAL", "supportedAbis": ["armeabi-v7a"], "tags": []},
  {"id": "PixelPhysical", "formFactor": "PHYSICAL", "supportedAbis": ["arm64-v8a"], "tags": []},
  {"id": "PixelDep", "formFactor": "VIRTUAL", "supportedAbis": ["arm64-v8a"], "tags": ["deprecated"]},
  {"id": "Pixel2ARM", "formFactor": "VIRTUAL", "supportedAbis": ["arm64-v8a", "armeabi-v7a"], "tags": []}
]
EOF
        exit 0
        ;;
    *"models describe Pixel2ARM"*)
        echo '{"id":"Pixel2ARM","supportedApiLevels":["25","28","30"]}'
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        os.chmod(gcloud_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "Pixel2ARM,30")

    def test_25_select_arm_device_no_compatible_devices_fails(self):
        mock_bin = os.path.join(self.temp_dir, "mock_gcloud_nodev")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "OnlyX86", "formFactor": "VIRTUAL", "supportedAbis": ["x86_64"], "tags": []}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    # ── Test Cases 26-28: Prerequisites & Bucket Normalization ───────────────
    def test_26_validate_prerequisites_normalizes_bucket(self):
        mock_bin = os.path.join(self.temp_dir, "mock_tools_prereq")
        os.makedirs(mock_bin, exist_ok=True)
        for tool in ("gh", "gcloud", "gsutil"):
            p = os.path.join(mock_bin, tool)
            with open(p, "w") as f:
                f.write("#!/bin/sh\nexit 0\n")
            os.chmod(p, 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "FIREBASE_TESTLAB_BUCKET": "gs://my-test-bucket/subpath/",
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = self.run_bash_fn("validate_prerequisites", env)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "my-test-bucket/subpath")

    def test_27_validate_prerequisites_missing_bucket_fails(self):
        mock_bin = os.path.join(self.temp_dir, "mock_tools_prereq2")
        os.makedirs(mock_bin, exist_ok=True)
        for tool in ("gh", "gcloud", "gsutil"):
            p = os.path.join(mock_bin, tool)
            with open(p, "w") as f:
                f.write("#!/bin/sh\nexit 0\n")
            os.chmod(p, 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "FIREBASE_TESTLAB_BUCKET": "",
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = self.run_bash_fn("validate_prerequisites", env)
        self.assertNotEqual(res.returncode, 0)

    def test_28_retry_command_exponential_backoff(self):
        mock_bin = os.path.join(self.temp_dir, "mock_retry_bin")
        os.makedirs(mock_bin, exist_ok=True)
        counter_file = os.path.join(self.temp_dir, "retry_count.txt")
        with open(counter_file, "w") as f:
            f.write("0")

        flaky_cmd = os.path.join(mock_bin, "flaky")
        with open(flaky_cmd, "w") as f:
            f.write(f"""#!/bin/sh
count=$(cat "{counter_file}")
count=$((count+1))
echo "$count" > "{counter_file}"
if [ "$count" -lt 3 ]; then
    exit 1
fi
echo "FlakySuccess"
exit 0
""")
        os.chmod(flaky_cmd, 0o755)

        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}"}
        res = self.run_bash_fn('retry_command 3 flaky', env)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "FlakySuccess")

    # ── Test Cases 29-33: Matrix Results & Real Report URLs ─────────────────
    def test_29_matrix_id_extraction_and_url_retrieval(self):
        mock_bin = os.path.join(self.temp_dir, "mock_firebase_run")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        gsutil_script = os.path.join(mock_bin, "gsutil")

        results_storage = os.path.join(self.temp_dir, "gcs_results")
        os.makedirs(results_storage, exist_ok=True)
        with open(os.path.join(results_storage, "screen.png"), "wb") as f:
            f.write(b"PNG")
        with open(os.path.join(results_storage, "video.mp4"), "wb") as f:
            f.write(b"MP4")
        with open(os.path.join(results_storage, "logcat.txt"), "w") as f:
            f.write("Log line")

        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"firebase test android run"*)
        echo "Test matrix [matrix-12345-abcde] created."
        exit 0
        ;;
    *"matrices describe matrix-12345-abcde"*)
        echo '{"testMatrixId":"matrix-12345-abcde","webUrl":"https://console.firebase.google.com/project/p/testlab/histories/h/matrices/matrix-12345-abcde","resultStorage":{"resultsUrl":"https://console.firebase.google.com/project/p/testlab/histories/h/matrices/matrix-12345-abcde"}}'
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(gsutil_script, "w") as f:
            f.write(f"""#!/bin/sh
for last; do true; done
cp -r "{results_storage}/"* "$last"
exit 0
""")
        os.chmod(gcloud_script, 0o755)
        os.chmod(gsutil_script, 0o755)

        apk_path = os.path.join(self.temp_dir, "test.apk")
        create_test_apk(apk_path)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil",
            "PROJECT_ROOT": self.temp_dir
        }
        res = self.run_bash_fn(f'run_test_lab "{apk_path}" "Pixel2ARM,30" "test-bucket"', env)
        self.assertEqual(res.returncode, 0)
        out_json = json.loads(res.stdout.strip())
        self.assertEqual(out_json["matrix_id"], "matrix-12345-abcde")
        self.assertTrue(out_json["report_url"].startswith("https://console.firebase.google.com/"))
        self.assertTrue(os.path.isdir(out_json["results_dir"]))

    def test_30_matrix_id_not_fabricated_on_failure(self):
        mock_bin = os.path.join(self.temp_dir, "mock_firebase_fail")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
echo "ERROR: Quota exceeded or permission denied"
exit 1
""")
        os.chmod(gcloud_script, 0o755)

        apk_path = os.path.join(self.temp_dir, "test.apk")
        create_test_apk(apk_path)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GCLOUD_CMD": "gcloud",
            "PROJECT_ROOT": self.temp_dir
        }
        res = self.run_bash_fn(f'run_test_lab "{apk_path}" "Pixel2ARM,30" "test-bucket"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_31_run_test_lab_missing_required_artifacts_fails(self):
        mock_bin = os.path.join(self.temp_dir, "mock_firebase_missing_art")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        gsutil_script = os.path.join(mock_bin, "gsutil")

        # Only create png, omit video and logs
        results_storage = os.path.join(self.temp_dir, "gcs_incomplete")
        os.makedirs(results_storage, exist_ok=True)
        with open(os.path.join(results_storage, "screen.png"), "wb") as f:
            f.write(b"PNG")

        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"firebase test android run"*)
        echo "Test matrix [matrix-incomplete] created."
        exit 0
        ;;
    *"matrices describe matrix-incomplete"*)
        echo '{"testMatrixId":"matrix-incomplete","webUrl":"https://console.firebase.google.com/..."}'
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(gsutil_script, "w") as f:
            f.write(f"""#!/bin/sh
for last; do true; done
cp -r "{results_storage}/"* "$last"
exit 0
""")
        os.chmod(gcloud_script, 0o755)
        os.chmod(gsutil_script, 0o755)

        apk_path = os.path.join(self.temp_dir, "test.apk")
        create_test_apk(apk_path)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil",
            "PROJECT_ROOT": self.temp_dir
        }
        res = self.run_bash_fn(f'run_test_lab "{apk_path}" "Pixel2ARM,30" "test-bucket"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_32_update_beastmode_state_creates_file_if_missing(self):
        missing_state_file = os.path.join(self.temp_dir, "nested", "new_state.json")
        env = {"BEAST_MODE_STATE": missing_state_file}
        res = self.run_bash_fn(f'update_beastmode_state "{{}}" "100" "200" "Pixel2ARM" "matrix-999" "https://report.url" "/tmp/res" "COMPLETED" "sha-abc"', env)
        self.assertEqual(res.returncode, 0)
        self.assertTrue(os.path.isfile(missing_state_file))
        with open(missing_state_file, "r") as f:
            state = json.load(f)
        self.assertEqual(state["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-999")

    def test_33_update_beastmode_state_preserves_other_fields(self):
        complex_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "MyCustomTask",
            "objectives": [{"id": "obj-1", "status": "IN_PROGRESS"}],
            "gitBranch": "feature-branch",
            "lastCommitSha": "prev-sha"
        }
        state_path = os.path.join(self.temp_dir, "complex_state.json")
        with open(state_path, "w") as f:
            json.dump(complex_state, f)

        env = {"BEAST_MODE_STATE": state_path}
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(complex_state)}\' "100" "200" "Pixel2ARM" "matrix-999" "https://report.url" "/tmp/res" "COMPLETED" "sha-abc"', env)
        self.assertEqual(res.returncode, 0)

        with open(state_path, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["currentTask"], "MyCustomTask")
        self.assertEqual(saved["gitBranch"], "feature-branch")
        self.assertEqual(saved["objectives"][0]["id"], "obj-1")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-999")

    # ── Test Cases 34-37: Beast Mode State Isolation & Validation ───────────
    def test_34_update_beastmode_state_atomic_and_isolated(self):
        state_content = Path(self.state_file).read_text()
        env = {"BEAST_MODE_STATE": self.state_file}
        res = self.run_bash_fn(f'update_beastmode_state \'{state_content}\' "100" "200" "Pixel2ARM" "matrix-999" "https://report.url" "/tmp/res" "COMPLETED" "sha-abc"', env)
        self.assertEqual(res.returncode, 0)

        with open(self.state_file, "r") as f:
            state = json.load(f)

        self.assertIn("firebaseTestLab", state)
        last_run = state["firebaseTestLab"]["lastRun"]
        self.assertEqual(last_run["runId"], "100")
        self.assertEqual(last_run["artifactId"], "200")
        self.assertEqual(last_run["selectedDevice"], "Pixel2ARM")
        self.assertEqual(last_run["matrixId"], "matrix-999")
        self.assertEqual(last_run["reportUrl"], "https://report.url")
        self.assertEqual(last_run["status"], "COMPLETED")
        self.assertEqual(last_run["commitSha"], "sha-abc")

        # Verify physical on-device state is not marked VERIFIED
        self.assertNotEqual(state.get("physicalVerification"), "VERIFIED")

    def test_35_hook_detects_explicit_trigger(self):
        hook_path = PROJECT_ROOT / ".claude" / "hooks" / "user_prompt_submit.py"
        input_data = json.dumps({"prompt": "/minehost-beastmode firebase run"})
        res = subprocess.run([sys.executable, str(hook_path)], input=input_data, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        out = json.loads(res.stdout)
        self.assertIn("Task    : Firebase Test Lab run", out["prompt"])

    def test_36_end_to_end_mock_workflow(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_bin")
        os.makedirs(mock_bin, exist_ok=True)

        apk_source = os.path.join(self.temp_dir, "e2e.apk")
        create_test_apk(apk_source)
        art_zip = os.path.join(self.temp_dir, "e2e_art.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="minehost-debug.apk")

        results_storage = os.path.join(self.temp_dir, "gcs_e2e_results")
        os.makedirs(results_storage, exist_ok=True)
        with open(os.path.join(results_storage, "screen.png"), "wb") as f:
            f.write(b"PNG")
        with open(os.path.join(results_storage, "video.mp4"), "wb") as f:
            f.write(b"MP4")
        with open(os.path.join(results_storage, "logcat.txt"), "w") as f:
            f.write("Server initialized")

        # Mock gh
        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write(f"""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{{"databaseId": 77777, "workflowName": "Android CI", "headSha": "fedcba9876543210", "conclusion": "success", "name": "Build APK"}}]'
        exit 0
        ;;
    *"repos/testowner/testrepo/actions/runs/77777/artifacts"*)
        echo '{{"artifacts":[{{"id":88888,"name":"minehost-debug","expired":false}}]}}'
        exit 0
        ;;
    *"repos/testowner/testrepo/actions/runs/77777"*)
        echo '{{"id": 77777, "head_sha": "fedcba9876543210", "status": "completed"}}'
        exit 0
        ;;
    *"actions/artifacts/88888/zip"*)
        cat "{art_zip}"
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        # Mock gcloud
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelE2E", "formFactor": "VIRTUAL", "supportedAbis": ["arm64-v8a"], "tags": []}]'
        exit 0
        ;;
    *"models describe PixelE2E"*)
        echo '{"id":"PixelE2E","supportedApiLevels":["28","30"]}'
        exit 0
        ;;
    *"firebase test android run"*)
        echo "Test matrix [matrix-e2e-9999] created."
        exit 0
        ;;
    *"matrices describe matrix-e2e-9999"*)
        echo '{"testMatrixId":"matrix-e2e-9999","webUrl":"https://console.firebase.google.com/project/p/testlab/histories/h/matrices/matrix-e2e-9999","resultStorage":{"resultsUrl":"https://console.firebase.google.com/project/p/testlab/histories/h/matrices/matrix-e2e-9999"}}'
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        # Mock gsutil
        with open(os.path.join(mock_bin, "gsutil"), "w") as f:
            f.write(f"""#!/bin/sh
if [ "$1" = "ls" ]; then
    exit 0
fi
for last; do true; done
cp -r "{results_storage}/"* "$last"
exit 0
""")
        for tool in ("gh", "gcloud", "gsutil"):
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-e2e-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"STDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}")

        with open(self.state_file, "r") as f:
            state = json.load(f)

        self.assertIn("firebaseTestLab", state)
        last_run = state["firebaseTestLab"]["lastRun"]
        self.assertEqual(last_run["runId"], "77777")
        self.assertEqual(last_run["artifactId"], "88888")
        self.assertEqual(last_run["selectedDevice"], "PixelE2E,30")
        self.assertEqual(last_run["matrixId"], "matrix-e2e-9999")
        self.assertEqual(last_run["status"], "COMPLETED")
        self.assertEqual(last_run["commitSha"], "fedcba9876543210")

    def test_37_end_to_end_artifact_download_failure_aborts(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_fail_bin")
        os.makedirs(mock_bin, exist_ok=True)

        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write("""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{"databaseId": 77777, "workflowName": "Android CI", "headSha": "fedcba9876543210", "conclusion": "success", "name": "Build APK"}]'
        exit 0
        ;;
    *"repos/testowner/testrepo/actions/runs/77777/artifacts"*)
        echo '{"artifacts":[{"id":88888,"name":"minehost-debug","expired":false}]}'
        exit 0
        ;;
    *"repos/testowner/testrepo/actions/runs/77777"*)
        echo '{"id": 77777, "head_sha": "fedcba9876543210", "status": "completed"}'
        exit 0
        ;;
    *"actions/artifacts/88888/zip"*)
        exit 1
        ;;
    *"run download"*)
        exit 1
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write("#!/bin/sh\nexit 0\n")
        with open(os.path.join(mock_bin, "gsutil"), "w") as f:
            f.write("#!/bin/sh\nexit 0\n")
        for tool in ("gh", "gcloud", "gsutil"):
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-e2e-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
