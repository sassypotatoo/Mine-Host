#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"

echo "=== Verifying Native Launcher APK ==="
echo "APK Path: $APK"

# 1. Check APK exists
if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK file does not exist: $APK" >&2
  exit 1
fi

APK_SIZE=$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK" 2>/dev/null)
echo "APK Size: $APK_SIZE bytes"

# Calculate SHA256
if command -v sha256sum >/dev/null 2>&1; then
  APK_SHA256=$(sha256sum "$APK" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  APK_SHA256=$(shasum -a 256 "$APK" | awk '{print $1}')
else
  APK_SHA256="unknown"
fi
echo "APK SHA-256: $APK_SHA256"

# 2. Check launcher entry in zip
LAUNCHER_ENTRY="lib/arm64-v8a/libminehost_jvm_launcher.so"
MATCH_COUNT=$(
  unzip -Z1 "$APK" |
    awk -v target="$LAUNCHER_ENTRY" '$0 == target { count++ } END { print count + 0 }'
)

if [[ "$MATCH_COUNT" -eq 0 ]]; then
  echo "ERROR: Native launcher entry '$LAUNCHER_ENTRY' is missing from APK." >&2
  exit 1
fi

if [[ "$MATCH_COUNT" -ne 1 ]]; then
  echo "ERROR: Native launcher entry count is $MATCH_COUNT (expected exactly 1)." >&2
  exit 1
fi

echo "Native launcher entry count: $MATCH_COUNT (exact match)"

# 3. Extract and check launcher entry size and ELF architecture
TMP_LAUNCHER=$(mktemp /tmp/libminehost_launcher_XXXXXX.so)
trap 'rm -f "$TMP_LAUNCHER"' EXIT

unzip -p "$APK" "$LAUNCHER_ENTRY" > "$TMP_LAUNCHER"

ENTRY_SIZE=$(stat -c%s "$TMP_LAUNCHER" 2>/dev/null || stat -f%z "$TMP_LAUNCHER" 2>/dev/null)
echo "Native launcher entry size: $ENTRY_SIZE bytes"

if [[ "$ENTRY_SIZE" -le 0 ]]; then
  echo "ERROR: Native launcher entry in APK is empty (0 bytes)." >&2
  exit 1
fi

FILE_TYPE=$(file "$TMP_LAUNCHER")
echo "Native launcher file type: $FILE_TYPE"

if ! echo "$FILE_TYPE" | grep -E "ELF 64-bit.*(aarch64|ARM aarch64)" >/dev/null 2>&1; then
  echo "ERROR: Native launcher is not an AArch64 64-bit ELF binary." >&2
  echo "File type found: $FILE_TYPE" >&2
  exit 1
fi

echo "Verified: Native launcher is valid AArch64 64-bit ELF binary."

# 4. Verify package ID match using aapt or aapt2 if available
EXPECTED_PKG="com.aistudio.minehost.qweras"
if command -v aapt >/dev/null 2>&1; then
  PKG_NAME=$(
    aapt dump badging "$APK" |
      awk -F"'" '/^package: name=/{ print $2; exit }'
  )
  if [[ -n "$PKG_NAME" && "$PKG_NAME" != "$EXPECTED_PKG" ]]; then
    echo "ERROR: APK package ID '$PKG_NAME' does not match expected '$EXPECTED_PKG'." >&2
    exit 1
  fi
  echo "Package ID verified: ${PKG_NAME:-$EXPECTED_PKG}"
fi

# 5. Verify signing certificate if apksigner is available
if command -v apksigner >/dev/null 2>&1; then
  echo "Verifying APK signing..."
  apksigner verify "$APK" || {
    echo "ERROR: APK signing verification failed." >&2
    exit 1
  }
  echo "APK signature verified successfully."
elif [[ -f "$ROOT/tools/verify_stable_debug_apk.sh" ]]; then
  "$ROOT/tools/verify_stable_debug_apk.sh" "$APK" || {
    echo "ERROR: APK signing verification failed." >&2
    exit 1
  }
fi

echo "SUCCESS: MineHost native launcher APK verification passed."
