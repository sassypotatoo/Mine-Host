# Firebase Test Lab Integration Test Plan

## Goal
Verify that the Firebase Test Lab integration is properly configured and the command structure works.

## Prerequisites
- FIREBASE_TESTLAB_BUCKET environment variable must be set to a valid Google Cloud Storage bucket
- gcloud CLI must be authenticated and configured
- gh CLI must be authenticated
- MineHost repository must have successful GitHub Actions runs with APK artifacts

## Test Cases

### Test 1: Script Help/Usage
**Command:** `tools/run-firebase-test-lab.sh`
**Expected Output:** Usage message indicating `run` subcommand is required
**Success Criteria:** Script returns non-zero exit code and shows usage

### Test 2: Skill Command Structure
**Command:** `./.claude/skills/minehost-beastmode/minehost-beastmode`
**Expected Output:** Beast Mode state information (from bm-state.sh read)
**Success Criteria:** Command executes without error and shows state

### Test 3: Firebase Subcommand Help
**Command:** `./.claude/skills/minehost-beastmode/minehost-beastmode firebase`
**Expected Output:** Error about unknown subcommand or usage
**Success Criteria:** Command shows usage information for firebase run

### Test 4: Firebase Run Command Structure
**Command:** `./.claude/skills/minehost-beastmode/minehost-beastmode firebase run`
**Expected Output:** Either:
- Successful execution (if all prerequisites are met and bucket exists)
- Clear error message about missing prerequisites (bucket, auth, etc.)
**Success Criteria:** Command executes and produces meaningful output (not "command not found")

### Test 5: Environment Variable Check
**Command:** `echo $FIREBASE_TESTLAB_BUCKET`
**Expected Output:** The bucket value we set
**Success Criteria:** Environment variable is properly set

## Manual Verification Steps

1. Verify the skill.json includes the firebase run example
2. Verify the SKILL.md documentation includes firebase run section
3. Verify the minehost-beastmode script in the skill directory correctly delegates to the tool
4. Verify the tool script has proper error handling and logging

## Notes
- This integration test does not require a Firebase Test Lab bucket to exist to pass basic command structure tests
- For full end-to-end testing, a valid Firebase Test Lab bucket with write permissions is required
- The script includes retry logic and exponential backoff for robustness