import os
import sys
import subprocess
import json
import urllib.request
import gzip
import io
import tarfile
import hashlib
from datetime import datetime

# Usage: python3 build-java.py <MAJOR>
if len(sys.argv) < 2:
    print("Usage: python3 build-java.py <MAJOR>")
    sys.exit(1)

MAJOR = sys.argv[1] # "17", "21", or "25"

def get_deb_url(package_name, packages_text, mirror):
    for block in packages_text.split("\n\n"):
        if block.startswith("Package: " + package_name + "\n"):
            for line in block.split("\n"):
                if line.startswith("Filename: "):
                    return mirror + "/" + line.split(": ")[1]
    return None

print(f"Fetching packages for Java {MAJOR}...")
mirror = "https://packages-cf.termux.dev/apt/termux-main"
req = urllib.request.Request(f"{mirror}/dists/stable/main/binary-aarch64/Packages.gz", headers={'User-Agent': 'Mozilla/5.0'})
with urllib.request.urlopen(req) as response:
    gz = gzip.GzipFile(fileobj=response)
    packages_text = gz.read().decode('utf-8')

packages_to_fetch = [
    f"openjdk-{MAJOR}",
    "libandroid-shmem",
    "libandroid-spawn",
    "libandroid-sysv-semaphore",
    "zlib",
    "libc++",
    "libiconv",
    "libjpeg-turbo",
    "littlecms",
    "alsa-lib",
    "alsa-plugins"
]

out_dir = f"java{MAJOR}-build"
os.system(f"rm -rf {out_dir} && mkdir -p {out_dir}")

def run_cmd(cmd):
    print(f"Running: {cmd}")
    ret = os.system(cmd)
    if ret != 0:
        print(f"Command failed with code {ret}: {cmd}")
        sys.exit(1)

for pkg in packages_to_fetch:
    url = get_deb_url(pkg, packages_text, mirror)
    if not url:
        print(f"Failed to find {pkg}")
        # Some packages might be optional or renamed, but we expect these
        sys.exit(1)
    
    print(f"Downloading {pkg}: {url}")
    filename = url.split("/")[-1]
    # Use curl with retry and follow redirects
    run_cmd(f"curl -L --retry 3 --fail -s '{url}' -o {out_dir}/{filename}")
    run_cmd(f"mv {out_dir}/{filename} {out_dir}/{pkg}.deb")

print("Extracting...")
run_cmd(f"cd {out_dir} && for f in *.deb; do dpkg-deb -x \"$f\" .; done")

print("Packing runtime...")
run_cmd(f"mkdir -p {out_dir}/runtime")

# Try multiple possible paths for the JDK
paths = [
    f"{out_dir}/data/data/com.termux/files/usr/lib/jvm/openjdk-{MAJOR}",
    f"{out_dir}/data/data/com.termux/files/usr/lib/jvm/java-{MAJOR}-openjdk"
]
copied = False
for p in paths:
    if os.path.exists(p):
        print(f"Found JDK at {p}")
        run_cmd(f"cp -r {p}/* {out_dir}/runtime/")
        copied = True
        break

if not copied:
    print(f"ERROR: Could not find JDK directory in {out_dir}")
    # List what we have for debugging
    os.system(f"find {out_dir} -type d -name '*jvm*'")
    sys.exit(1)

# Include native dependencies in a subdirectory
run_cmd(f"mkdir -p {out_dir}/runtime/lib/termux")
libs_to_copy = [
    "libandroid-shmem.so",
    "libandroid-spawn.so",
    "libandroid-sysv-semaphore.so",
    "libiconv.so",
    "libz.so",
    "libz.so.1",
    "libc++_shared.so",
    "libjpeg.so",
    "liblcms2.so",
    "libasound.so"
]

for lib in libs_to_copy:
    # Search for the lib in extracted files
    cmd = f"find {out_dir} -name '{lib}' -exec cp {{}} {out_dir}/runtime/lib/termux/ \;"
    os.system(cmd)

# Ensure executable permissions
run_cmd(f"chmod -R u+w {out_dir}/runtime")
run_cmd(f"find {out_dir}/runtime/bin -type f -exec chmod a+x {{}} \;")

# Pack into tar.gz
tar_name = f"java{MAJOR}-arm64.tar.gz"
print(f"Creating {tar_name}...")
# We use tar -C to change directory and avoid leading ./ if possible, or just be consistent.
# The installer expects files relative to runtime root.
run_cmd(f"tar -C {out_dir}/runtime -czf {tar_name} .")

# Validate GZIP signature (1F 8B)
with open(tar_name, 'rb') as f:
    header = f.read(2)
    if header != b'\x1f\x8b':
        print(f"ERROR: {tar_name} is not a valid GZIP file (Magic: {header.hex()})")
        sys.exit(1)

# Generate SHA-256
with open(tar_name, 'rb') as f:
    sha256 = hashlib.sha256(f.read()).hexdigest()

print(f"Built {tar_name} (SHA256: {sha256})")

# Generate index
index_entry = {
    "javaMajor": int(MAJOR),
    "abi": "arm64-v8a",
    "archiveName": tar_name,
    "archiveSha256": sha256,
    "runtimeFingerprint": f"termux-openjdk{MAJOR}-" + sha256[:8],
    "upstreamSource": mirror,
    "upstreamRevision": "latest",
    "requiredFiles": [
        "lib/server/libjvm.so",
        "lib/jli/libjli.so",
        "lib/modules",
        "bin/java"
    ]
}

index_file = f"runtime-index-{MAJOR}.json"
with open(index_file, "w") as f:
    json.dump(index_entry, f, indent=2)

print(f"Generated {index_file}")

# Clean up build dir
run_cmd(f"rm -rf {out_dir}")

print(f"Successfully completed Java {MAJOR} build.")
