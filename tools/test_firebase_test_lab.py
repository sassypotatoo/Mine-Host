#!/usr/bin/env python3
"""
Comprehensive Test Suite for MineHost Firebase Test Lab Integration
Validates all test cases across 6 teams and 3 waves without consuming real Firebase quota.
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
                "beastModeEnabled": False,
                "currentTask": "",
                "taskBranch": "",
                "objectives": [],
                "currentObjectiveId": None,
                "phase": "IDLE",
                "workflowStatus": "IDLE",
                "gitBranch": "",
                "lastCiRunUrl": "",
                "lastCommitSha": "",
                "knownIssues": [],
                "blockers": [],
                "firebaseTestLab": {
                    "status": "READY"
                },
                "timestamp": ""
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

    # ── Repo Resolution Tests ────────────────────────────────────────────────
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

    # ── Wave 1 / Team 3: Authoritative Workflow Selection Tests ──────────────
    def test_wf_01_authoritative_android_succeeds_with_artifact(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_01")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 1001, "workflowName": "Android CI", "headSha": "sha1001_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Build APK"}]'
    exit 0
fi
if echo "$*" | grep -q "actions/runs/1001"; then
    echo '{"id": 1001, "head_sha": "sha1001_40chars_0123456789abcdef01234567", "status": "completed"}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout.strip())
        self.assertEqual(data["run_id"], "1001")
        self.assertEqual(data["commit_sha"], "sha1001_40chars_0123456789abcdef01234567")

    def test_wf_02_unrelated_website_workflow_succeeds_first(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_02")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[
      {"databaseId": 9999, "workflowName": "Website Build", "headSha": "sha_web", "conclusion": "success", "name": "Deploy Docs"},
      {"databaseId": 1002, "workflowName": "Android CI", "headSha": "sha1002_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Android Build"}
    ]'
    exit 0
fi
if echo "$*" | grep -q "actions/runs/1002"; then
    echo '{"id": 1002, "head_sha": "sha1002_40chars_0123456789abcdef01234567", "status": "completed"}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertEqual(res.returncode, 0)
        data = json.loads(res.stdout.strip())
        self.assertEqual(data["run_id"], "1002")

    def test_wf_03_unrelated_workflow_name_contains_build(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_03")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 9991, "workflowName": "Documentation Build", "headSha": "sha_doc", "conclusion": "success", "name": "Documentation Build"}]'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_wf_04_unrelated_workflow_name_contains_apk(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_04")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 9992, "workflowName": "Third-Party APK Build", "headSha": "sha_third", "conclusion": "success", "name": "Build APK"}]'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_wf_05_android_fails_while_unrelated_succeeds(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_05")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[
      {"databaseId": 9993, "workflowName": "Website Deploy", "headSha": "sha_web", "conclusion": "success", "name": "Website Deploy"},
      {"databaseId": 1005, "workflowName": "Android CI", "headSha": "sha_fail", "conclusion": "failure", "name": "Android CI"}
    ]'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_wf_06_correct_workflow_succeeds_without_artifact(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_06")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "artifacts"; then
    echo '{"artifacts":[]}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_apk_artifact_id "1006" "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_wf_07_multiple_artifacts_exist_exact_selected(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_07")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "artifacts"; then
    echo '{"artifacts":[
        {"id":1111,"name":"website.zip","expired":false},
        {"id":7777,"name":"minehost-debug","expired":false},
        {"id":9999,"name":"other.apk","expired":false}
    ]}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_apk_artifact_id "1007" "testowner/testrepo"', env)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "7777")

    def test_wf_08_required_artifact_expired(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_08")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "artifacts"; then
    echo '{"artifacts":[{"id":7778,"name":"minehost-debug","expired":true}]}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_apk_artifact_id "1008" "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_wf_09_github_returns_malformed_workflow_metadata(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_09")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo 'NOT_VALID_JSON{{"status":'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    def test_wf_10_commit_sha_missing_or_invalid(self):
        mock_bin = os.path.join(self.temp_dir, "mock_wf_10")
        os.makedirs(mock_bin, exist_ok=True)
        gh_script = os.path.join(mock_bin, "gh")
        with open(gh_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 1010, "workflowName": "Android CI", "headSha": "", "conclusion": "success", "name": "Build APK"}]'
    exit 0
fi
if echo "$*" | grep -q "actions/runs/1010"; then
    echo '{"id": 1010, "head_sha": "", "status": "completed"}'
    exit 0
fi
exit 1
""")
        os.chmod(gh_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GH_CMD": "gh"}
        res = self.run_bash_fn('get_latest_apk_run "testowner/testrepo"', env)
        self.assertNotEqual(res.returncode, 0)

    # ── Wave 1 / Team 3: Device Catalog Selection & Filtering Tests ─────────
    def test_dev_01_valid_virtual_arm64_candidate(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_01")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelVirtualARM64", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": [], "perVersionInfo": [{"versionId": "v30", "apiLevel": 30, "deviceCapacity": "DEVICE_CAPACITY_HIGH"}]}]'
        exit 0
        ;;
    *"models describe PixelVirtualARM64"*)
        echo '{"id": "PixelVirtualARM64", "supportedVersionIds": ["v28", "v30"], "perVersionInfo": [{"versionId": "v28", "apiLevel": 28}, {"versionId": "v30", "apiLevel": 30}]}'
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
        self.assertEqual(res.stdout.strip(), "PixelVirtualARM64,30")

    def test_dev_02_physical_arm64_device(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_02")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "PixelPhysicalARM64", "formFactor": "PHYSICAL", "supportedAbis": ["arm64-v8a"], "tags": []}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_03_virtual_x86_only_device(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_03")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "PixelVirtualX86", "formFactor": "VIRTUAL", "supportedAbis": ["x86_64"], "tags": []}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_04_virtual_armeabi_v7a_only_device(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_04")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "PixelVirtualArm32", "formFactor": "VIRTUAL", "supportedAbis": ["armeabi-v7a"], "tags": []}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_05_deprecated_device(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_05")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "PixelDepARM64", "formFactor": "VIRTUAL", "supportedAbis": ["arm64-v8a"], "tags": ["deprecated"]}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_06_explicitly_disabled_device(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_06")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "PixelDisabledARM64", "formFactor": "VIRTUAL", "supportedAbis": ["arm64-v8a"], "enabled": false, "tags": []}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_07_missing_eligibility_field(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_07")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "PixelMissingAbis", "formFactor": "VIRTUAL", "tags": []}]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_08_malformed_catalog_response(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_08")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo 'MALFORMED_OUTPUT'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_09_unsupported_api_level(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_09")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelOldApi", "formFactor": "VIRTUAL", "supportedAbis": ["arm64-v8a"], "tags": []}]'
        exit 0
        ;;
    *"models describe PixelOldApi"*)
        echo '{"id": "PixelOldApi", "supportedApiLevels": ["21", "24", "25"]}'
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
        self.assertNotEqual(res.returncode, 0)

    def test_dev_10_valid_api_level(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_10")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelValidApi", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": [], "perVersionInfo": [{"versionId": "30", "apiLevel": 30, "deviceCapacity": "DEVICE_CAPACITY_HIGH"}]}]'
        exit 0
        ;;
    *"models describe PixelValidApi"*)
        echo '{"id": "PixelValidApi", "supportedVersionIds": ["v24", "v28", "v30"], "perVersionInfo": [{"versionId": "v24", "apiLevel": 24}, {"versionId": "v28", "apiLevel": 28}, {"versionId": "v30", "apiLevel": 30}]}'
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
        self.assertEqual(res.stdout.strip(), "PixelValidApi,30")

    def test_dev_11_no_eligible_device(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_11")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[]'
    exit 0
fi
exit 1
""")
        os.chmod(gcloud_script, 0o755)
        env = {"PATH": f"{mock_bin}:{os.environ.get('PATH', '')}", "GCLOUD_CMD": "gcloud"}
        res = self.run_bash_fn("select_arm_device", env)
        self.assertNotEqual(res.returncode, 0)

    def test_dev_12_multiple_eligible_devices_deterministic_selection(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_12")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[
          {"id": "Pixel3ARM", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": []},
          {"id": "Pixel2ARM", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": []}
        ]'
        exit 0
        ;;
    *"models describe Pixel2ARM"*)
        echo '{"id": "Pixel2ARM", "supportedVersionIds": ["v28", "v30"], "perVersionInfo": [{"versionId": "v28", "apiLevel": 28}, {"versionId": "v30", "apiLevel": 30}]}'
        exit 0
        ;;
    *"models describe Pixel3ARM"*)
        echo '{"id": "Pixel3ARM", "supportedVersionIds": ["v28", "v30"], "perVersionInfo": [{"versionId": "v28", "apiLevel": 28}, {"versionId": "v30", "apiLevel": 30}]}'
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
        # Sorted deterministically: Pixel2ARM comes before Pixel3ARM
        self.assertEqual(res.stdout.strip(), "Pixel2ARM,30")

    # ── Wave 2 / Team 4: State Lifecycle & Neutrality Tests ─────────────────
    def test_state_01_clean_baseline_initialization(self):
        with open(self.state_file, "r") as f:
            state = json.load(f)
        self.assertFalse(state.get("beastModeEnabled"))
        self.assertEqual(state.get("currentTask"), "")
        self.assertEqual(state.get("workflowStatus"), "IDLE")
        self.assertEqual(state.get("phase"), "IDLE")
        self.assertEqual(state.get("firebaseTestLab", {}).get("status"), "READY")

    def test_state_02_explicit_firebase_command_enters_running(self):
        hook_path = PROJECT_ROOT / ".claude" / "hooks" / "user_prompt_submit.py"
        input_data = json.dumps({"prompt": "/minehost-beastmode firebase run"})
        env = os.environ.copy()
        env["BEAST_MODE_STATE"] = self.state_file
        res = subprocess.run([sys.executable, str(hook_path)], input=input_data, capture_output=True, text=True, env=env)
        self.assertEqual(res.returncode, 0)
        out = json.loads(res.stdout)
        self.assertIn("Task    : Firebase Test Lab run", out["prompt"])

    def test_state_03_successful_execution_enters_complete(self):
        init_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "Firebase Test Lab run",
            "workflowStatus": "RUNNING",
            "phase": "EXECUTE"
        }
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(init_state)}\' "101" "201" "Pixel2ARM,30" "matrix-301" "https://report.url" "/tmp/res" "COMPLETED" "sha-301"')
        self.assertEqual(res.returncode, 0)
        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["workflowStatus"], "COMPLETE")
        self.assertEqual(saved["phase"], "IDLE")
        self.assertEqual(saved["currentTask"], "")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["status"], "COMPLETED")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-301")

    def test_state_04_failed_execution_enters_failed(self):
        init_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "Firebase Test Lab run",
            "workflowStatus": "RUNNING",
            "phase": "EXECUTE"
        }
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(init_state)}\' "102" "202" "Pixel2ARM,30" "matrix-302" "" "" "FAILED" "sha-302"')
        self.assertEqual(res.returncode, 0)
        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["workflowStatus"], "FAILED")
        self.assertEqual(saved["phase"], "IDLE")
        self.assertEqual(saved["currentTask"], "")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["status"], "FAILED")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-302")

    def test_state_05_matrix_survives_later_failure(self):
        init_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "Firebase Test Lab run",
            "workflowStatus": "RUNNING"
        }
        # Matrix was generated (matrix-survivor), but result download failed -> status FAILED
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(init_state)}\' "103" "203" "Pixel2ARM,30" "matrix-survivor" "" "" "FAILED" "sha-303"')
        self.assertEqual(res.returncode, 0)
        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-survivor")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["status"], "FAILED")

    def test_state_06_state_updates_preserve_unrelated_fields(self):
        complex_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "Firebase Test Lab run",
            "workflowStatus": "RUNNING",
            "objectives": [{"id": "obj-1", "title": "Audit", "status": "COMPLETED"}],
            "knownIssues": ["issue-1"],
            "gitBranch": "main"
        }
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(complex_state)}\' "104" "204" "Pixel2ARM,30" "matrix-304" "https://report.url" "/tmp/res" "COMPLETED" "sha-304"')
        self.assertEqual(res.returncode, 0)
        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["objectives"][0]["id"], "obj-1")
        self.assertEqual(saved["knownIssues"], ["issue-1"])
        self.assertEqual(saved["gitBranch"], "main")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-304")

    def test_state_07_interrupted_failed_executions_do_not_report_success(self):
        init_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "Firebase Test Lab run",
            "workflowStatus": "RUNNING"
        }
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(init_state)}\' "105" "205" "Pixel2ARM,30" "" "" "" "FAILED" "sha-305"')
        self.assertEqual(res.returncode, 0)
        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["workflowStatus"], "FAILED")
        self.assertNotEqual(saved["workflowStatus"], "COMPLETE")

    def test_state_08_normal_beastmode_does_not_launch_firebase(self):
        hook_path = PROJECT_ROOT / ".claude" / "hooks" / "user_prompt_submit.py"
        input_data = json.dumps({"prompt": "/minehost-beastmode status"})
        env = os.environ.copy()
        env["BEAST_MODE_STATE"] = self.state_file
        res = subprocess.run([sys.executable, str(hook_path)], input=input_data, capture_output=True, text=True, env=env)
        self.assertEqual(res.returncode, 0)
        out = json.loads(res.stdout) if res.stdout.strip() else {}
        self.assertNotIn("Firebase Test Lab run", out.get("prompt", ""))

    def test_state_09_firebase_completion_leaves_physical_verification_unverified(self):
        init_state = {
            "schemaVersion": 1,
            "beastModeEnabled": True,
            "currentTask": "Firebase Test Lab run",
            "workflowStatus": "RUNNING",
            "physicalVerification": "VERIFIED"  # Simulate attempting to claim physical device verification
        }
        res = self.run_bash_fn(f'update_beastmode_state \'{json.dumps(init_state)}\' "106" "206" "Pixel2ARM,30" "matrix-306" "https://report.url" "/tmp/res" "COMPLETED" "sha-306"')
        self.assertEqual(res.returncode, 0)
        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved.get("physicalVerification"), "UNVERIFIED")

    # ── Wave 3 / Team 5: E2E Failure Injection & Isolation Scenarios ────────
    def test_e2e_scenario_01_unrelated_workflow_failure_injection(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_01")
        os.makedirs(mock_bin, exist_ok=True)
        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "run list"; then
    echo '[{"databaseId": 999, "workflowName": "Website Deploy", "headSha": "sha999", "conclusion": "success", "name": "Deploy Website"}]'
    exit 0
fi
exit 1
""")
        for tool in ("gcloud", "gsutil"):
            with open(os.path.join(mock_bin, tool), "w") as f:
                f.write("#!/bin/sh\nexit 0\n")
            os.chmod(os.path.join(mock_bin, tool), 0o755)
        os.chmod(os.path.join(mock_bin, "gh"), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)

    def test_e2e_scenario_02_wrong_device_architecture_failure_injection(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_02")
        os.makedirs(mock_bin, exist_ok=True)

        apk_source = os.path.join(self.temp_dir, "scen2.apk")
        create_test_apk(apk_source)
        art_zip = os.path.join(self.temp_dir, "scen2.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="minehost-debug.apk")

        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write(f"""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{{"databaseId": 801, "workflowName": "Android CI", "headSha": "sha801_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Build APK"}}]'
        exit 0
        ;;
    *"actions/runs/801/artifacts"*)
        echo '{{"artifacts":[{{"id":901,"name":"minehost-debug","expired":false}}]}}'
        exit 0
        ;;
    *"actions/runs/801"*)
        echo '{{"id": 801, "head_sha": "sha801_40chars_0123456789abcdef01234567", "status": "completed"}}'
        exit 0
        ;;
    *"actions/artifacts/901/zip"*)
        cat "{art_zip}"
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "X86Device", "formFactor": "VIRTUAL", "supportedAbis": ["x86_64"], "tags": []}]'
    exit 0
fi
exit 1
""")
        with open(os.path.join(mock_bin, "gsutil"), "w") as f:
            f.write("#!/bin/sh\nexit 0\n")

        for tool in ("gh", "gcloud", "gsutil"):
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)

    def test_e2e_scenario_03_incomplete_catalog_response_failure_injection(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_03")
        os.makedirs(mock_bin, exist_ok=True)

        apk_source = os.path.join(self.temp_dir, "scen3.apk")
        create_test_apk(apk_source)
        art_zip = os.path.join(self.temp_dir, "scen3.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="minehost-debug.apk")

        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write(f"""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{{"databaseId": 802, "workflowName": "Android CI", "headSha": "sha802_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Build APK"}}]'
        exit 0
        ;;
    *"actions/runs/802/artifacts"*)
        echo '{{"artifacts":[{{"id":902,"name":"minehost-debug","expired":false}}]}}'
        exit 0
        ;;
    *"actions/runs/802"*)
        echo '{{"id": 802, "head_sha": "sha802_40chars_0123456789abcdef01234567", "status": "completed"}}'
        exit 0
        ;;
    *"actions/artifacts/902/zip"*)
        cat "{art_zip}"
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write("""#!/bin/sh
if echo "$*" | grep -q "models list"; then
    echo '[{"id": "CorruptedDevice", "formFactor": "VIRTUAL"}]'
    exit 0
fi
exit 1
""")
        with open(os.path.join(mock_bin, "gsutil"), "w") as f:
            f.write("#!/bin/sh\nexit 0\n")

        for tool in ("gh", "gcloud", "gsutil"):
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)

    def test_e2e_scenario_04_state_failure_on_error_injection(self):
        # Inject state that had currentTask set, then verify running script sets state to FAILED
        with open(self.state_file, "w") as f:
            json.dump({
                "schemaVersion": 1,
                "beastModeEnabled": True,
                "currentTask": "Firebase Test Lab run",
                "workflowStatus": "RUNNING",
                "phase": "EXECUTE"
            }, f)

        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_04")
        os.makedirs(mock_bin, exist_ok=True)
        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write("""#!/bin/sh
exit 1
""")
        for tool in ("gcloud", "gsutil"):
            with open(os.path.join(mock_bin, tool), "w") as f:
                f.write("#!/bin/sh\nexit 0\n")
            os.chmod(os.path.join(mock_bin, tool), 0o755)
        os.chmod(os.path.join(mock_bin, "gh"), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)

    def test_e2e_scenario_05_matrix_preservation_on_download_failure(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_05")
        os.makedirs(mock_bin, exist_ok=True)

        apk_source = os.path.join(self.temp_dir, "scen5.apk")
        create_test_apk(apk_source)
        art_zip = os.path.join(self.temp_dir, "scen5.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="minehost-debug.apk")

        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write(f"""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{{"databaseId": 805, "workflowName": "Android CI", "headSha": "sha805_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Build APK"}}]'
        exit 0
        ;;
    *"actions/runs/805/artifacts"*)
        echo '{{"artifacts":[{{"id":905,"name":"minehost-debug","expired":false}}]}}'
        exit 0
        ;;
    *"actions/runs/805"*)
        echo '{{"id": 805, "head_sha": "sha805_40chars_0123456789abcdef01234567", "status": "completed"}}'
        exit 0
        ;;
    *"actions/artifacts/905/zip"*)
        cat "{art_zip}"
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "Pixel5ARM", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": [], "perVersionInfo": [{"versionId": "30", "deviceCapacity": "DEVICE_CAPACITY_HIGH"}]}]'
        exit 0
        ;;
    *"models describe Pixel5ARM"*)
        echo '{"id": "Pixel5ARM", "supportedVersionIds": ["30"], "perVersionInfo": [{"versionId": "30", "apiLevel": 30}]}'
        exit 0
        ;;
    *"firebase test android run"*)
        echo "Test matrix [matrix-preserved-1234] created."
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gsutil"), "w") as f:
            f.write("""#!/bin/sh
if [ "$1" = "ls" ]; then
    exit 0
fi
# Simulate GCS download failure
exit 1
""")

        for tool in ("gh", "gcloud", "gsutil"):
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        with open(self.state_file, "w") as f:
            json.dump({
                "schemaVersion": 1,
                "beastModeEnabled": True,
                "currentTask": "Firebase Test Lab run",
                "workflowStatus": "RUNNING",
                "phase": "EXECUTE"
            }, f)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        self.assertNotEqual(res.returncode, 0)

        with open(self.state_file, "r") as f:
            saved = json.load(f)
        print("DEBUG: state after script run:", saved)
        self.assertEqual(saved["workflowStatus"], "FAILED")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-preserved-1234")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["status"], "FAILED")

    def test_e2e_scenario_06_successful_local_mock(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_06")
        os.makedirs(mock_bin, exist_ok=True)

        apk_source = os.path.join(self.temp_dir, "scen6.apk")
        create_test_apk(apk_source)
        art_zip = os.path.join(self.temp_dir, "scen6.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="minehost-debug.apk")

        results_storage = os.path.join(self.temp_dir, "gcs_scen6_results")
        os.makedirs(results_storage, exist_ok=True)
        with open(os.path.join(results_storage, "screen.png"), "wb") as f:
            f.write(b"PNG")
        with open(os.path.join(results_storage, "video.mp4"), "wb") as f:
            f.write(b"MP4")
        with open(os.path.join(results_storage, "logcat.txt"), "w") as f:
            f.write("Server started on port 25565")

        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write(f"""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{{"databaseId": 806, "workflowName": "Android CI", "headSha": "sha806_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Build APK"}}]'
        exit 0
        ;;
    *"actions/runs/806/artifacts"*)
        echo '{{"artifacts":[{{"id":906,"name":"minehost-debug","expired":false}}]}}'
        exit 0
        ;;
    *"actions/runs/806"*)
        echo '{{"id": 806, "head_sha": "sha806_40chars_0123456789abcdef01234567", "status": "completed"}}'
        exit 0
        ;;
    *"actions/artifacts/906/zip"*)
        cat "{art_zip}"
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "Pixel6ARM", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": [], "perVersionInfo": [{"versionId": "30", "deviceCapacity": "DEVICE_CAPACITY_HIGH"}]}]'
        exit 0
        ;;
    *"models describe Pixel6ARM"*)
        echo '{"id": "Pixel6ARM", "supportedVersionIds": ["30"], "perVersionInfo": [{"versionId": "30", "apiLevel": 30}]}'
        exit 0
        ;;
    *"firebase test android run"*)
        echo "Test matrix [matrix-success-806] created."
        exit 0
        ;;
    *"matrices describe matrix-success-806"*)
        echo '{"testMatrixId":"matrix-success-806","webUrl":"https://console.firebase.google.com/project/p/testlab/histories/h/matrices/matrix-success-806","resultStorage":{"resultsUrl":"https://console.firebase.google.com/project/p/testlab/histories/h/matrices/matrix-success-806"}}'
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
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

        with open(self.state_file, "w") as f:
            json.dump({
                "schemaVersion": 1,
                "beastModeEnabled": True,
                "currentTask": "Firebase Test Lab run",
                "workflowStatus": "RUNNING",
                "phase": "EXECUTE"
            }, f)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"STDOUT:\n{res.stdout}\nSTDERR:\n{res.stderr}")

        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["workflowStatus"], "COMPLETE")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-success-806")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["status"], "COMPLETED")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["commitSha"], "sha806_40chars_0123456789abcdef01234567")

    def test_dev_13_authoritative_versions_list_with_string_version_ids(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_13")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelAuthoritative", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": []}]'
        exit 0
        ;;
    *"models describe PixelAuthoritative"*)
        echo '{"id": "PixelAuthoritative", "supportedVersionIds": ["Tiramisu", "UpsideDownCake"]}'
        exit 0
        ;;
    *"versions list"*)
        echo '[
          {"id": "Tiramisu", "apiLevel": 33, "tags": ["deprecated"]},
          {"id": "UpsideDownCake", "apiLevel": 34, "tags": ["default"]}
        ]'
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
        self.assertEqual(res.stdout.strip(), "PixelAuthoritative,34")

    def test_dev_14_conflicting_version_metadata_fails_closed(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_14")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelConflict", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": []}]'
        exit 0
        ;;
    *"models describe PixelConflict"*)
        echo '{"id": "PixelConflict", "supportedVersionIds": ["v30"], "perVersionInfo": [{"versionId": "v30", "apiLevel": 28}]}'
        exit 0
        ;;
    *"versions list"*)
        echo '[{"id": "v30", "apiLevel": 30}]'
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
        self.assertNotEqual(res.returncode, 0)

    def test_dev_15_version_id_not_treatable_as_api_level_fails_closed(self):
        mock_bin = os.path.join(self.temp_dir, "mock_dev_15")
        os.makedirs(mock_bin, exist_ok=True)
        gcloud_script = os.path.join(mock_bin, "gcloud")
        with open(gcloud_script, "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "PixelNoMeta", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": []}]'
        exit 0
        ;;
    *"models describe PixelNoMeta"*)
        echo '{"id": "PixelNoMeta", "supportedVersionIds": ["30"]}'
        exit 0
        ;;
    *"versions list"*)
        echo '[]'
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
        self.assertNotEqual(res.returncode, 0)

    def test_e2e_scenario_07_early_failure_resets_running_to_failed(self):
        with open(self.state_file, "w") as f:
            json.dump({
                "schemaVersion": 1,
                "beastModeEnabled": True,
                "currentTask": "Firebase Test Lab run",
                "workflowStatus": "RUNNING",
                "phase": "EXECUTE",
                "physicalVerification": "VERIFIED"
            }, f)

        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_07")
        os.makedirs(mock_bin, exist_ok=True)
        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write("#!/bin/sh\nexit 1\n")
        os.chmod(os.path.join(mock_bin, "gh"), 0o755)

        for tool in ("gcloud", "gsutil"):
            with open(os.path.join(mock_bin, tool), "w") as f:
                f.write("#!/bin/sh\nexit 0\n")
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)

        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["workflowStatus"], "FAILED")
        self.assertEqual(saved["phase"], "IDLE")
        self.assertEqual(saved["currentTask"], "")
        self.assertEqual(saved.get("physicalVerification"), "UNVERIFIED")

    def test_e2e_scenario_08_missing_test_artifacts_fails_and_preserves_matrix(self):
        mock_bin = os.path.join(self.temp_dir, "mock_e2e_scen_08")
        os.makedirs(mock_bin, exist_ok=True)

        apk_source = os.path.join(self.temp_dir, "scen8.apk")
        create_test_apk(apk_source)
        art_zip = os.path.join(self.temp_dir, "scen8.zip")
        with zipfile.ZipFile(art_zip, "w") as zf:
            zf.write(apk_source, arcname="minehost-debug.apk")

        empty_results = os.path.join(self.temp_dir, "empty_results")
        os.makedirs(empty_results, exist_ok=True)

        with open(os.path.join(mock_bin, "gh"), "w") as f:
            f.write(f"""#!/bin/sh
case "$*" in
    *"run list"*)
        echo '[{{"databaseId": 808, "workflowName": "Android CI", "headSha": "sha808_40chars_0123456789abcdef01234567", "conclusion": "success", "name": "Build APK"}}]'
        exit 0
        ;;
    *"actions/runs/808/artifacts"*)
        echo '{{"artifacts":[{{"id":908,"name":"minehost-debug","expired":false}}]}}'
        exit 0
        ;;
    *"actions/runs/808"*)
        echo '{{"id": 808, "head_sha": "sha808_40chars_0123456789abcdef01234567", "status": "completed"}}'
        exit 0
        ;;
    *"actions/artifacts/908/zip"*)
        cat "{art_zip}"
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gcloud"), "w") as f:
            f.write(r"""#!/bin/sh
case "$*" in
    *"models list"*)
        echo '[{"id": "Pixel8ARM", "form": "VIRTUAL", "formFactor": "PHONE", "supportedAbis": ["arm64-v8a"], "tags": []}]'
        exit 0
        ;;
    *"models describe Pixel8ARM"*)
        echo '{"id": "Pixel8ARM", "supportedVersionIds": ["30"], "perVersionInfo": [{"versionId": "30", "apiLevel": 30}]}'
        exit 0
        ;;
    *"firebase test android run"*)
        echo "Test matrix [matrix-incomplete-808] created."
        exit 0
        ;;
    *)
        exit 1
        ;;
esac
""")
        with open(os.path.join(mock_bin, "gsutil"), "w") as f:
            f.write(f"""#!/bin/sh
if [ "$1" = "ls" ]; then
    exit 0
fi
# Empty results copied -> missing images, video, logs
exit 0
""")

        for tool in ("gh", "gcloud", "gsutil"):
            os.chmod(os.path.join(mock_bin, tool), 0o755)

        with open(self.state_file, "w") as f:
            json.dump({
                "schemaVersion": 1,
                "beastModeEnabled": True,
                "currentTask": "Firebase Test Lab run",
                "workflowStatus": "RUNNING",
                "phase": "EXECUTE"
            }, f)

        env = {
            "PATH": f"{mock_bin}:{os.environ.get('PATH', '')}",
            "GITHUB_REPOSITORY": "testowner/testrepo",
            "FIREBASE_TESTLAB_BUCKET": "gs://test-bucket",
            "BEAST_MODE_STATE": self.state_file,
            "PROJECT_ROOT": self.temp_dir,
            "GH_CMD": "gh",
            "GCLOUD_CMD": "gcloud",
            "GSUTIL_CMD": "gsutil"
        }
        res = subprocess.run([BASH_BIN, str(SCRIPT_PATH), "run"], env=env, capture_output=True, text=True)
        self.assertNotEqual(res.returncode, 0)

        with open(self.state_file, "r") as f:
            saved = json.load(f)
        self.assertEqual(saved["workflowStatus"], "FAILED")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["matrixId"], "matrix-incomplete-808")
        self.assertEqual(saved["firebaseTestLab"]["lastRun"]["status"], "FAILED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
