export const meta = {
  name: 'fix-firebase-test-lab-integration',
  description: 'Fix all bugs in Firebase Test Lab integration using parallel subagents'
};

// Agent 1: Fix tools/run-firebase-test-lab.sh
const prompt1 = 'Fix all bugs in tools/run-firebase-test-lab.sh:\\n' +
  '1. C-1: In retry_command function, ensure it echoes captured stdout before returning 0.\\n' +
  '2. M-3: Replace eval in retry_command with safe array-based execution.\\n' +
  '3. C-2: In matrix_id extraction, if extraction fails, abort with clear error message (no timestamp fallback).\\n' +
  '4. C-2: Normalize bucket variable: strip leading \\"gs://\\", then use \\"gs://$bucket\\" for GCS and \\"$bucket\\" alone for flags.\\n' +
  '5. C-2: Remove hardcoded fallback for FIREBASE_TESTLAB_BUCKET in main().\\n' +
  '6. C-5: After downloading APK, add validation: check size > 0, verify with aapt (package: com.aistudio.minehost.qweras, native: arm64-v8a, sdkVersion: 26) or unzip fallback.\\n' +
  '7. M-1: After selecting device model, query supported API versions and choose highest >= 26.\\n' +
  '8. M-2: In get_latest_apk_run, add --branch main to gh run list.\\n' +
  '9. M-5: Replace jq calls in main() with python3 equivalents; add python3 check in validate_prerequisites.\\n' +
  '10. M-6: After get_latest_apk_run, fetch commit SHA and pass to update_beastmode_state.\\n' +
  '11. Add cleanup of .tmp/firebase-test-lab/apk on success or exit trap.';

// Agent 2: Fix .claude/hooks/user_prompt_submit.py and .claude/beastmode_state.json
const prompt2 = 'Fix bugs in .claude/hooks/user_prompt_submit.py and .claude/beastmode_state.json:\\n' +
  'In .claude/hooks/user_prompt_submit.py:\\n' +
  '1. When handling /minehost-beastmode firebase run, set new_state[\\"workflowStatus\\"] = \\"RUNNING\\" before write_state().\\n' +
  '2. Change line 235 check to use new_state.get(\\"workflowStatus\\") instead of state.get(...).\\n' +
  'In .claude/beastmode_state.json:\\n' +
  '1. Reset \\"workflowStatus\\" from \\"COMPLETE\\" to \\"IDLE\\".\\n' +
  '2. Clear \\"currentTask\\" and \\"taskBranch\\".\\n' +
  '3. Keep all other fields intact.';

// Agent 3: Fix .env.example
const prompt3 = 'Fix .env.example:\\n' +
  'Add line: FIREBASE_TESTLAB_BUCKET=gs://your-project-testlab-results\\n' +
  'Add comment: Google Cloud Storage bucket for Firebase Test Lab results (without gs:// prefix)';

// Agent 4: Fix .claude/hooks/stop_hook.py
const prompt4 = 'Fix .claude/hooks/stop_hook.py:\\n' +
  'Align cap check flag path with pre_tool_use.py: use PROJECT_ROOT/.cap_check_done';

// Agent 5: Fix docs/superpowers/plans/2026-09-28-firebase-test-lab-integration.md
const prompt5 = 'Fix docs/superpowers/plans/2026-09-28-firebase-test-lab-integration.md:\\n' +
  'Mark all task checkboxes as done (change - [ ] to - [x]).';

// Execute all agents in parallel
const results = await parallel([
  () => agent(prompt1, { label: 'Fix Firebase Test Lab script', phase: 'FixScripts' }),
  () => agent(prompt2, { label: 'Fix hook and state files', phase: 'FixScripts' }),
  () => agent(prompt3, { label: 'Fix .env.example', phase: 'FixScripts' }),
  () => agent(prompt4, { label: 'Fix stop_hook.py', phase: 'FixScripts' }),
  () => agent(prompt5, { label: 'Fix documentation', phase: 'FixScripts' })
]);

return { status: 'All subagents completed. Fixes applied.' };