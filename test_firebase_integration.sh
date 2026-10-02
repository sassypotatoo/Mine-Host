#!/data/data/com.termux/files/usr/glibc/bin/bash
# Simple test to verify Firebase Test Lab integration command structure

set -euo pipefail

echo "=== Firebase Test Lab Integration Command Structure Test ==="
echo

# Test 1: Check that the skill script exists and is executable
echo "1. Checking skill script..."
if [[ -x "/data/data/com.termux/files/home/Mine-Host/.claude/skills/minehost-beastmode/minehost-beastmode" ]]; then
  echo "   ✓ Skill script exists and is executable"
else
  echo "   ✗ Skill script missing or not executable"
  exit 1
fi

# Test 2: Check that the tool script exists and is executable
echo "2. Checking tool script..."
if [[ -x "/data/data/com.termux/files/home/Mine-Host/tools/run-firebase-test-lab.sh" ]]; then
  echo "   ✓ Tool script exists and is executable"
else
  echo "   ✗ Tool script missing or not executable"
  exit 1
fi

# Test 3: Check that the skill documentation was updated
echo "3. Checking skill documentation..."
if grep -q "firebase run" "/data/data/com.termux/files/home/Mine-Host/.claude/skills/minehost-beastmode/SKILL.md"; then
  echo "   ✓ Skill documentation mentions firebase run"
else
  echo "   ✗ Skill documentation does not mention firebase run"
  exit 1
fi

# Test 4: Check that the skill.json was updated
echo "4. Checking skill metadata..."
if grep -q "firebase run" "/data/data/com.termux/files/home/Mine-Host/.claude/skills/minehost-beastmode/skill.json"; then
  echo "   ✓ Skill metadata mentions firebase run"
else
  echo "   ✗ Skill metadata does not mention firebase run"
  exit 1
fi

# Test 5: Test basic command structure (without requiring bucket)
echo "5. Testing basic command structure..."
OUTPUT=$(/data/data/com.termux/files/home/Mine-Host/.claude/skills/minehost-beastmode/minehost-beastmode firebase run 2>&1 || true)
if [[ $OUTPUT == *"Usage:"* ]] || [[ $OUTPUT == *"Environment variable FIREBASE_TESTLAB_BUCKET is not set"* ]]; then
  echo "   ✓ Command structure is correct (shows usage or bucket error as expected)"
else
  echo "   ✗ Unexpected output: $OUTPUT"
  exit 1
fi

echo
echo "=== All tests passed! ==="
echo "The Firebase Test Lab integration has been successfully configured."
echo "To run a full test, set FIREBASE_TESTLAB_BUCKET to a valid Google Cloud Storage bucket"
echo "and ensure you have authenticated gcloud and gh CLIs."