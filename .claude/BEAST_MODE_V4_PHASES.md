# Beast Mode v4 — 4-Phase Implementation Plan

**Date:** 2026-09-13  
**Goal:** Complete transition from v3 (shell-orchestrated) to v4 (Claude-as-brain)  
**Total Effort:** ~2-3 hours spread across 4 focused phases  

---

## Phase Overview

| Phase | Title | Scope | Time | Deliverable |
|-------|-------|-------|------|-------------|
| 1 | Cleanup & Architecture | Remove v3 ambiguity | 30 min | Clean codebase, v3 archived |
| 2 | Claude's Workflow | Document decision loop | 45 min | Complete workflow spec |
| 3 | CI Failure Loop | Explicit diagnosis process | 45 min | Failure handling guide |
| 4 | Integration & Test | Validation & verification | 30 min | End-to-end test scenario |

**Total:** ~2.5 hours

---

## Phase 1: Cleanup & Architecture (30 min)

### Goal
Remove architectural ambiguity. Make it crystal clear that v4 is Claude-as-brain, not shell-as-orchestrator.

### Deliverables

#### 1.1 Archive v3 Scripts
**Action:** Move old workflow scripts to archive

```bash
mkdir -p .claude/scripts-v3-archive
mv .claude/scripts/beastmode_workflow.sh .claude/scripts-v3-archive/
mv .claude/scripts/beastmode_utils.sh .claude/scripts-v3-archive/
# Leave .claude/scripts/ as empty or remove it if empty
```

**Why:** v4 hook doesn't call these. They only cause confusion. Archive them as reference.

#### 1.2 Create Explicit v4 Architecture Doc
**File to create:** `.claude/BEAST_MODE_V4_ARCHITECTURE.md`

**Content outline:**
```markdown
# Beast Mode v4 Architecture

## Core Principle
Claude Code is THE BRAIN 🧠
Beast Mode provides the ENVIRONMENT 🔧

## Layers

### Layer 1: Activation (Hook)
- File: .claude/hooks/user_prompt_submit.py
- Does: Detects /minehost-autonomous, injects state, never drives workflow
- Claude: Receives full context on every prompt

### Layer 2: State Management (Tools)
- File: tools/bm-state.sh
- Does: Atomic read/write of Beast Mode state
- Claude: Calls this to record decisions

### Layer 3: Verification (Tools)
- Files: tools/push-gated.sh, tools/ci-watch.sh
- Does: Safe push, CI monitoring
- Claude: Calls these to commit/verify

### Layer 4: Claude's Brain
- Does: Everything else
- Reads state → decides intent → plans → implements → commits → verifies → loops

## No More Layers
- No v3 workflow scripts
- No "GUIDANCE_NEEDED" signals (Claude has full context)
- No shell-driven orchestration
- No turn-based state machine in shell
```

**Create file with:**
- Clear layer diagram
- Responsibility matrix (what each layer does, what it doesn't)
- Example flow (work request → Claude decides → implements → commits → CI → loops)
- Non-negotiables (never force-push, never fake CI results, etc.)

#### 1.3 Update Brain.md with v4 Clarifications
**File:** `.claude/Brain.md`

**Changes:**
- Remove/update v3-specific sections (workflow phases, --intake, --advance)
- Add explicit note: "v3 scripts archived; v4 uses Claude as decision engine"
- Update "Claude Capability Selection Architecture" to emphasize: "Claude decides ALL tactics"

#### 1.4 Verify Hook is Active
**File:** `.claude/hooks/user_prompt_submit.py`

**Checklist:**
- ✅ Detects `/minehost-autonomous` (bare toggle)
- ✅ Detects `/minehost-autonomous "task"` (activation with task)
- ✅ Injects full state block when Beast Mode ON
- ✅ No other logic — no --intake, no --advance, no classification
- ✅ Fail-open on errors

**Action:** Quick syntax check + review (should pass as-is)

### Phase 1 Verification
```bash
# After phase 1:
✅ v3 scripts archived (not deleted, not called)
✅ Architecture doc created
✅ Brain.md updated with v4 clarifications
✅ Hook verified as v4-compliant
✅ Clear separation: Hook injects → Claude decides → Tools execute
```

### Phase 1 Success Criteria
- [ ] v3 scripts moved to archive/
- [ ] BEAST_MODE_V4_ARCHITECTURE.md created
- [ ] Brain.md updated with v4 notes
- [ ] Hook logic verified (no changes needed)
- [ ] Codebase reads as v4 (no ambiguity)

---

## Phase 2: Claude's Workflow (45 min)

### Goal
Document the **explicit workflow Claude should follow** when Beast Mode is ON and work requests arrive.

### Deliverables

#### 2.1 Create Claude's Decision Loop Doc
**File to create:** `.claude/CLAUDE_V4_WORKFLOW.md`

**Content: Step-by-step workflow**

```markdown
# Claude Code v4 Workflow — When Beast Mode is ON

## Entry Point
User sends a message. Hook injects state block + original message.

## Step 1: Read & Understand Context (Always)
```
Every prompt:
1. Look at the injected state block at the top of the prompt
2. Extract:
   - Current task (if any)
   - Current objectives (if any)
   - Attempt count for current objective
   - Last CI result (PASS/FAIL/none)
   - Last commit SHA
   - Which objectives are PENDING/IN_PROGRESS/VERIFIED/FAILED
3. Understand: Where are we in the workflow?
```

## Step 2: Classify User Intent (Always)
```
Every message, determine: What does the user want?

QUESTION / CHAT:
  "What's the status of X?"
  "Tell me about Y"
  "How does Z work?"
  → Answer normally. Don't start Beast Mode workflow.
  
WORK REQUEST:
  "Fix the authentication flow"
  "Implement dark mode support"
  "Add integration with Supabase"
  → Go to Step 3

CONTROL REQUEST:
  "/minehost-autonomous" (toggle)
  "pause the current task"
  "stop and save progress"
  "switch to a different task"
  → Handle control (use bm-state.sh to pause/resume/cancel)
```

## Step 3: Decompose Work into Objectives (For Work Requests)
```
If this is a WORK REQUEST:

1. Read the request carefully
2. Understand the goal
3. Break it into concrete, verifiable objectives
   - Each objective should be:
     * Describable in 1-2 sentences
     * Independently verifiable via CI
     * Meaningful checkpoint
4. Call: tools/bm-state.sh task-start "<branch>" "<task-description>"
5. For each objective, call: tools/bm-state.sh objective-add "<objective-description>"
   - This returns an objective ID
   - Store it for later reference
6. Read back the state: tools/bm-state.sh status
7. Now you have a structured task with objectives
```

## Step 4: For Each Objective — Implement → Commit → Verify Loop
```
While there are PENDING objectives:

a) Get current objective from state
   - Read: tools/bm-state.sh status
   - Find the current objective ID
   - Extract description, attempt count

b) Plan the approach
   - Read the MineHost context (CLAUDE.md, master context)
   - Understand what files need to change
   - Decide: Do I need a Skill? MCP? Sub-agent? None?
   - Consult SKILL.md "Capability Selection Guidance" for categories
   - Example decision process:
     * "This is a security-sensitive change" → consider claude-security
     * "This is a large codebase analysis" → consider superpowers
     * "This is a small file edit" → no extra tools needed
   - Important: Don't force tools. Use only what's genuinely useful.

c) Implement the objective
   - Make the code changes
   - Run any available local tests/verification
   - Review your own work
   - Report: "Implementation complete, ready for CI verification"

d) Commit when ready
   - Stage changes: git add -u (tracked) + git add . (new files)
   - Commit: git commit -m "Implement objective: <description>"
   - Get SHA: git rev-parse HEAD
   - Push: ./tools/push-gated.sh
   - If push fails: diagnose (branch behind? uncommitted changes?) and fix

e) Mark objective as IN_PROGRESS in state
   - Call: tools/bm-state.sh objective-start <objective-id>
   - This increments the attempt counter

f) Watch CI
   - Call: ./tools/ci-watch.sh --sha <commit-sha>
   - This monitors GitHub Actions for this commit
   - Exit code: 0=PASS, 1=FAIL, 2=inconclusive

g) Handle result
   - If PASS (exit 0):
     * Call: tools/bm-state.sh objective-complete <id> "<sha>" "<ci-url>"
     * Go to next objective (back to Step 4a)
   
   - If FAIL (exit 1):
     * Go to Step 5 (Diagnose Failure)
   
   - If inconclusive (exit 2):
     * Report to user: "CI result unclear. Verify manually and call objective-complete or objective-fail"
```

## Step 5: Diagnose & Fix CI Failures
```
When a CI run fails (exit 1 from ci-watch.sh):

a) Read actual CI logs
   - ci-watch.sh prints failed-step logs to stdout
   - Capture and read them carefully
   - Classify the failure:
     * COMPILE ERROR: Syntax/type errors in code
     * TEST FAILURE: Test assertions failed
     * BUILD SYSTEM: gradle/cmake/build config issue
     * INFRA: Network, auth, GitHub rate limit, device issue
     * UNKNOWN: Unclear from logs

b) Diagnose root cause
   - Example: "Build failed at 'Set up Gradle' step"
     * This is INFRA (gradle-wrapper.jar validation)
     * Not caused by my code change
     * Likely: environment/cache issue
   - Example: "Test SettingsActivityTest failed"
     * This is TEST FAILURE
     * My code change probably broke the test
     * Need to fix the implementation or test

c) Decide: Is this my responsibility?
   - Did MY CODE cause this? → Fix the code
   - Is this a known MineHost CI issue? → Check CLAUDE.md for known baseline failures
   - Is this infrastructure? → Investigate, don't randomly modify app code
   - Unsure? → Ask for guidance (mark objective-fail with reason, report to user)

d) Implement the fix
   - Go back to Step 4c (implement)
   - The fix might be:
     * Code change (most common)
     * Config change
     * Gradle/build system change
     * Test update
     * Dependency change
   - Make the fix, commit, push

e) Loop back
   - Retry from Step 4e (mark IN_PROGRESS again)
   - ci-watch.sh will monitor the new commit
   - Repeat until CI passes

f) Attempt limit
   - Each objective has max 5 attempts
   - If attempt 5 fails:
     * Call: tools/bm-state.sh objective-fail <id> "<reason>"
     * Mark as FAILED_EXCEEDED
     * Report to user: "Objective exhausted retry limit. Manual investigation needed."
```

## Step 6: Multi-Objective Sequencing
```
When current objective is VERIFIED (CI passed):

1. Current objective status → VERIFIED
2. Call: tools/bm-state.sh status
3. Check: Any PENDING objectives left?
   - YES: Go back to Step 4a (implement next objective)
   - NO: Go to Step 7

Objectives are processed sequentially:
  Obj 1: IMPLEMENT → CI FAIL → RETRY → CI PASS → VERIFIED
  Obj 2: IMPLEMENT → CI PASS → VERIFIED
  Obj 3: IMPLEMENT → CI FAIL → RETRY → CI PASS → VERIFIED
  ...
  All VERIFIED → Task complete
```

## Step 7: Complete Task
```
When all objectives are VERIFIED:

1. Call: tools/bm-state.sh task-done
2. Report summary:
   - What was the original task?
   - How many objectives were completed?
   - How many CI iterations were needed?
   - Any learnings or notes?
3. Beast Mode stays ON (user can start a new task)
```

## Important: Claude Never Simulates
```
❌ DO NOT:
- Pretend CI passed when you didn't actually run it
- Skip ci-watch.sh monitoring
- Assume "this should work" without verification
- Report "VERIFIED" without real GitHub Actions confirmation
- Make up test results
- Fake commit SHAs

✅ DO:
- Always call the real tools
- Always read real CI logs
- Always verify before marking complete
- Report what actually happened
- If uncertain, ask for clarification
```

## Retry & Escalation
```
Attempt Limits:
- Per objective: max 5 attempts
- If attempt 5 fails → FAILED_EXCEEDED
- User can manually investigate or request different approach

Graceful Escalation:
- If diagnosis is unclear → mark objective-fail with reason
- If fix is uncertain → ask user for guidance
- If limits exceeded → report to user
```
```

#### 2.2 Update SKILL.md with v4 Integration
**File:** `.claude/skills/minehost-beastmode/SKILL.md`

**Changes:**
- Add section: "Claude's v4 Workflow Integration"
- Link to CLAUDE_V4_WORKFLOW.md
- Clarify: "This skill provides the environment. Claude provides the brain."
- Example: "When you see 'BEAST MODE ACTIVE' in your context, read CLAUDE_V4_WORKFLOW.md to understand what to do"

#### 2.3 Add State Cheat Sheet
**File to create:** `.claude/BM_STATE_CHEAT_SHEET.md`

**Content:**
```markdown
# Beast Mode State Commands — Quick Reference

## Read State
./tools/bm-state.sh status          # Pretty-print current state
./tools/bm-state.sh get             # Raw JSON

## Task Lifecycle
./tools/bm-state.sh task-start "<branch>" "<task>"   # Start task
./tools/bm-state.sh task-done                        # Mark complete
./tools/bm-state.sh task-pause                       # Pause workflow
./tools/bm-state.sh task-resume                      # Resume
./tools/bm-state.sh task-cancel                      # Cancel

## Objective Lifecycle
./tools/bm-state.sh objective-add "<description>"     # Create objective (returns ID)
./tools/bm-state.sh objective-start <id>             # Mark IN_PROGRESS, increment attempts
./tools/bm-state.sh objective-complete <id> "<sha>" "<url>"  # Mark VERIFIED
./tools/bm-state.sh objective-fail <id> "<reason>"   # Mark FAILED
./tools/bm-state.sh objective-retry <id>             # Reset for retry

## CI Result Tracking
./tools/bm-state.sh ci-update <id> <PASS|FAIL> "<sha>" "<url>"  # Record CI result

## Monitoring
./tools/ci-watch.sh --sha <sha>     # Watch GitHub Actions for commit
./tools/push-gated.sh               # Safe push (no options, no force)

## Typical Flow
1. task-start "feature-branch" "Add dark mode support"
2. objective-add "Update theme provider"
3. objective-add "Update UI components"
4. objective-start <id1>
5. [implement code]
6. git add, commit, push-gated.sh
7. ci-watch.sh --sha <sha>
8. If PASS: objective-complete <id1> "<sha>" "<url>"
9. objective-start <id2>
10. [repeat]
11. task-done when all VERIFIED
```

### Phase 2 Verification
```bash
# After phase 2:
✅ CLAUDE_V4_WORKFLOW.md created (detailed decision loop)
✅ BM_STATE_CHEAT_SHEET.md created (quick reference)
✅ SKILL.md updated with v4 integration
✅ Claude understands: "When Beast Mode ON, follow CLAUDE_V4_WORKFLOW.md"
```

### Phase 2 Success Criteria
- [ ] CLAUDE_V4_WORKFLOW.md fully detailed (7 steps)
- [ ] BM_STATE_CHEAT_SHEET.md created with all commands
- [ ] SKILL.md updated with v4 integration section
- [ ] Clear entry points for "what to do" when work request arrives
- [ ] Non-negotiables emphasized (never fake verification, never force-push)

---

## Phase 3: CI Failure Loop (45 min)

### Goal
Document the **explicit process Claude should follow** when CI fails, so failures are diagnosed properly (not random code changes).

### Deliverables

#### 3.1 Create Failure Diagnosis Guide
**File to create:** `.claude/CI_FAILURE_DIAGNOSIS.md`

**Content outline:**

```markdown
# CI Failure Diagnosis — How to Handle GitHub Actions Failures

## When to Use This
When `./tools/ci-watch.sh --sha <sha>` returns exit code 1 (FAIL), and prints CI logs.

## Step 1: Read the Logs Carefully
```
ci-watch.sh outputs failed-step logs. Read them ALL.
Look for:
- Error message
- Stack trace
- Failed test name
- Build system error
- Network error
```

## Step 2: Classify the Failure
```
COMPILE ERROR:
  Patterns: "error: cannot find symbol", "cannot resolve symbol", "type mismatch"
  Cause: Syntax/type error in code
  Fix: Correct the code

TEST FAILURE:
  Patterns: "AssertionError", "expected true but was false", "FAILED"
  Cause: Test assertion failed
  Fix: Either fix code or fix test

BUILD SYSTEM ERROR:
  Patterns: "gradle failed", "cmake error", ":app:compileDebugKotlin FAILED"
  Cause: Build config issue
  Fix: Check gradle/kotlin/cmake config

INFRA ERROR:
  Patterns: "Set up Gradle", "network timeout", "rate limited", "auth failed"
  Cause: Environment/CI infrastructure issue
  Fix: Investigate, might need manual intervention

DEPENDENCY ERROR:
  Patterns: "dependency not found", "version conflict", "missing package"
  Cause: Dependency issue
  Fix: Update gradle.properties or pom.xml
```

#### 3.2 Diagnostic Decision Tree
```markdown
# Failure Analysis Decision Tree

Read logs from ci-watch.sh
  ↓
What do the logs say?
  ├─ "error: cannot find symbol [classname]"
  │  └─ YOU need to add/fix the class definition
  ├─ "AssertionError in test X"
  │  └─ YOU need to fix code or test
  ├─ "Set up Gradle" step failed
  │  └─ INFRA issue: Check CLAUDE.md for known baseline failure
  ├─ "network timeout / rate limited"
  │  └─ INFRA issue: Wait & retry, don't change app code
  ├─ "dependency not found"
  │  └─ YOU need to update gradle.properties
  └─ ???
     └─ Ask user for guidance
```

#### 3.3 MineHost-Specific Failure Patterns
**Section in CI_FAILURE_DIAGNOSIS.md:**

```markdown
# Known MineHost CI Patterns

From CLAUDE.md:
- "gradle-wrapper.jar validation at 'Set up Gradle'" = known baseline failure
  * Authorization fix may be needed once root cause confirmed
  * Don't randomly modify app code for this
  * Track as separate investigation

From WORLD_ADAPTER_STATUS.md / AUDIT_REPORT.md:
- Check for known device/runtime issues
- If failure matches known pattern → don't retry blindly
- Report to user: "Matches known issue X; manual intervention needed"
```

#### 3.4 Step-by-Step Failure Recovery
**Content:**

```markdown
# Step-by-Step: What to Do When CI Fails

## Failure Occurs
1. ci-watch.sh returns exit 1
2. Logs printed to stdout

## Immediate Actions
1. Read logs completely
2. Classify failure type (use diagnostic tree)
3. Determine: Is this MY CODE or INFRA?

## If MY CODE:
1. Understand the specific error
2. Locate the problematic file/line
3. Make the fix (minimal, focused)
4. Commit new fix
5. Push via push-gated.sh
6. Re-run ci-watch.sh for new commit
7. If still fails: repeat from step 2
8. If passes: mark objective complete

## If INFRA:
1. Check CLAUDE.md for known issues
2. If known: report to user, mark objective-fail with reason
3. If unknown: investigate (check GitHub Actions logs directly)
4. If fixable: apply fix, retry
5. If not fixable: escalate to user

## Attempt Tracking
- Each retry increments attempt counter (auto via objective-start)
- Max 5 attempts per objective
- If attempt 5 fails: mark FAILED_EXCEEDED
- User can manually investigate or try different approach

## Never Do This
❌ Change random code hoping it fixes CI
❌ Skip reading logs and guess the fix
❌ Assume build system changes are safe
❌ Revert entire commits without understanding why CI failed
❌ Mark objective complete without real CI PASS
```

#### 3.5 Example Failure Scenarios
**Section with 3-5 realistic examples:**

```markdown
# Example Scenarios

## Scenario 1: Compile Error
Logs show:
  SettingsActivity.kt:42: error: cannot find symbol: class ThemeProvider

Your response:
1. Classification: COMPILE ERROR
2. Root cause: Code references undefined class
3. Action: Find where ThemeProvider should be defined
4. Fix: Add class or import statement
5. Commit, push, re-run CI
6. If passes: mark complete

## Scenario 2: Test Failure
Logs show:
  SettingsActivityTest.testDarkModeToggle FAILED
  expected true but was false at line 18

Your response:
1. Classification: TEST FAILURE
2. Root cause: My code broke the test assertion
3. Action: Fix code to make test pass (or fix test if it's wrong)
4. Commit, push, re-run CI
5. If passes: mark complete

## Scenario 3: Build System Error (Known)
Logs show:
  > Configure project :
  [gradle-wrapper] error validating gradle-wrapper.jar

Your response:
1. Classification: INFRA ERROR (known baseline)
2. Root cause: Known gradle-wrapper authentication issue
3. Action: Check CLAUDE.md — is this listed as known failure?
4. If known: Report to user "Matches known gradle-wrapper issue"
5. Mark objective-fail with: "Infrastructure issue, manual intervention needed"
6. Do NOT retry blindly or change app code

## Scenario 4: Network Timeout
Logs show:
  HTTP Error 429 - Too Many Requests
  Downloading: ...

Your response:
1. Classification: INFRA ERROR
2. Root cause: Rate limited, not code issue
3. Action: Wait, don't change code
4. After wait: retry
5. If still fails after 2 retries: escalate to user
```

### Phase 3 Verification
```bash
# After phase 3:
✅ CI_FAILURE_DIAGNOSIS.md created (detailed guide)
✅ Diagnostic decision tree documented
✅ MineHost-specific patterns documented
✅ Example scenarios provided
✅ Clear "never do this" guidance
```

### Phase 3 Success Criteria
- [ ] CI_FAILURE_DIAGNOSIS.md fully detailed
- [ ] Decision tree covers main failure categories
- [ ] MineHost known issues documented
- [ ] 3-5 realistic example scenarios with responses
- [ ] Clear escalation path (when to ask user)
- [ ] Attempt limit and retry logic clear

---

## Phase 4: Integration & Test (30 min)

### Goal
Validate that all v4 pieces work together. Create an end-to-end test scenario.

### Deliverables

#### 4.1 Create Integration Test Scenario
**File to create:** `.claude/BEAST_MODE_V4_TEST.md`

**Content:**

```markdown
# Beast Mode v4 — Integration Test Scenario

## Test Setup
This is a MOCK scenario to validate the entire v4 workflow.
Not an actual code change, just workflow verification.

## Scenario: "Add a simple debug log to MainActivity"

### Pre-Test
1. Verify state file exists and is readable: `./tools/bm-state.sh status`
2. Verify git is clean: `git status` (should show working tree clean)
3. Verify on a development branch (not main)

### Test Steps

#### Step 1: Activate Beast Mode
```bash
# Simulate user input
/minehost-autonomous "Add debug log to MainActivity"
```
Expected:
- State should show: beastModeEnabled=true
- State should show: currentTask="Add debug log to MainActivity"
- state.phase should be "IMPLEMENT"

Verification:
```bash
./tools/bm-state.sh status | grep -E "Beast Mode|Status|Task"
# Should show: ON, ACTIVE, "Add debug log to MainActivity"
```

#### Step 2: Add Objectives
```bash
OBJ1=$(./tools/bm-state.sh objective-add "Add debug log to MainActivity onCreate")
OBJ2=$(./tools/bm-state.sh objective-add "Verify log appears in logcat")
```
Expected:
- Returns 2 objective IDs (timestamps)
- state.objectives should contain both

Verification:
```bash
./tools/bm-state.sh status | grep "Objectives"
# Should show: 2 total
```

#### Step 3: Start First Objective
```bash
./tools/bm-state.sh objective-start "$OBJ1"
```
Expected:
- objective status: IN_PROGRESS
- attempts: 1

Verification:
```bash
./tools/bm-state.sh status | grep "→"
# Should show arrow pointing to OBJ1
```

#### Step 4: Implement (Minimal Change)
```bash
# Make a small, real code change
echo 'Log.d("MainActivity", "DEBUG: onCreate called");' >> app/src/main/java/com/example/minehost/MainActivity.kt

# Stage and commit
git add -u
git commit -m "Add debug log to MainActivity

Objective: $OBJ1
Part of task: Add debug log to MainActivity"

# Get commit SHA
SHA=$(git rev-parse HEAD)
```

Expected:
- Git commit succeeds
- SHA is a valid 40-char hex string

#### Step 5: Push via Gate
```bash
./tools/push-gated.sh
```
Expected:
- push-gated.sh validates working tree clean
- push-gated.sh reports: "LOCAL COMPILE GATE: UNAVAILABLE (on Termux)"
- Push succeeds to origin
- Prints: "[push-gate] monitor CI with: ./tools/ci-watch.sh <sha>"

#### Step 6: Watch CI (Simulation)
```bash
# Real scenario: this would wait for GitHub Actions
# For test, we use --once for single check
./tools/ci-watch.sh --sha "$SHA" --once
```
Expected result depends on actual CI:
- Exit 0: PASS (workflow runs and passes)
- Exit 1: FAIL (workflow ran, has errors)
- Exit 2: INCONCLUSIVE (no run yet, timeout)

#### Step 7a: If CI Passes (exit 0)
```bash
CI_URL=$(git remote get-url origin | sed 's/.git//') # Construct URL
./tools/bm-state.sh objective-complete "$OBJ1" "$SHA" "$CI_URL"
```
Expected:
- OBJ1 status: VERIFIED
- phase: still IMPLEMENT (ready for next objective)

#### Step 7b: If CI Fails (exit 1)
```bash
./tools/bm-state.sh objective-fail "$OBJ1" "Initial CI run failed: see logs"
./tools/bm-state.sh objective-retry "$OBJ1"
```
Expected:
- OBJ1 status: IN_PROGRESS (reset for retry)
- attempts: 2
- [Would go back to Step 4 for fix]

#### Step 8: Complete Task
```bash
./tools/bm-state.sh task-done
```
Expected:
- state.phase: COMPLETE
- state.workflowStatus: COMPLETE
- All objectives should be VERIFIED

#### Step 9: Revert Test Change
```bash
git revert HEAD --no-edit
# or
git reset --hard HEAD~1
```
Expected:
- Codebase back to pre-test state

### Test Success Criteria
- [ ] Activation works (state shows ON)
- [ ] Objectives created and tracked
- [ ] Git operations succeed
- [ ] push-gated.sh enforces safety
- [ ] ci-watch.sh monitors CI
- [ ] State transitions work (IN_PROGRESS → VERIFIED → COMPLETE)
- [ ] Retry logic works (objective-fail → objective-retry)
- [ ] Task completion works
- [ ] Test change reverted successfully

### Test Failure Diagnosis
If test fails at any step:
1. Run: `./tools/bm-state.sh status` (print full state)
2. Check: `git status` (any uncommitted changes?)
3. Check: `.claude/beastmode_state.json` (valid JSON?)
4. Review: Which step failed? (state update? git operation? CI?)
```

#### 4.2 Create v4 Readiness Checklist
**File to create:** `.claude/BEAST_MODE_V4_READY.md`

```markdown
# Beast Mode v4 — Readiness Checklist

## Infrastructure Checklist

### Files & Structure
- [ ] v3 scripts archived (not deleted, not active)
- [ ] `.claude/hooks/user_prompt_submit.py` exists and is correct
- [ ] `tools/bm-state.sh` exists and executable
- [ ] `tools/ci-watch.sh` exists and executable
- [ ] `tools/push-gated.sh` exists and executable
- [ ] `.claude/beastmode_state.json` exists (or will be created on first use)

### Documentation
- [ ] BEAST_MODE_V4_ARCHITECTURE.md created
- [ ] CLAUDE_V4_WORKFLOW.md created (7-step workflow)
- [ ] BM_STATE_CHEAT_SHEET.md created
- [ ] CI_FAILURE_DIAGNOSIS.md created
- [ ] BEAST_MODE_V4_TEST.md created
- [ ] SKILL.md updated with v4 integration section
- [ ] Brain.md updated with v4 clarifications

### Tools Functionality
- [ ] Hook detects `/minehost-autonomous` toggle
- [ ] Hook injects state block when Beast Mode ON
- [ ] `bm-state.sh status` prints readable state
- [ ] `bm-state.sh get` returns valid JSON
- [ ] `bm-state.sh task-start` creates task
- [ ] `bm-state.sh objective-add` creates objective and returns ID
- [ ] `bm-state.sh objective-start` marks IN_PROGRESS and increments attempts
- [ ] `bm-state.sh objective-complete` marks VERIFIED
- [ ] `bm-state.sh objective-fail` marks FAILED
- [ ] `bm-state.sh ci-update` records CI result
- [ ] `push-gated.sh` enforces safety (no --force possible)
- [ ] `ci-watch.sh` monitors GitHub Actions and returns correct exit codes

### Known Constraints Documented
- [ ] CLAUDE.md says: "Local compile/test gates UNAVAILABLE"
- [ ] CI_FAILURE_DIAGNOSIS.md documents known baseline failures
- [ ] Non-negotiables are explicit (never fake verification, never force-push)

## Workflow Checklist

### When Beast Mode Activated
- [ ] User types `/minehost-autonomous` → OFF
- [ ] User types `/minehost-autonomous` again → ON (shows 🔥 BEAST MODE: ON ⚡ Effort: MAX)
- [ ] User types `/minehost-autonomous "task"` → ON + task starts
- [ ] Every prompt after that includes full state context
- [ ] Claude reads context and understands current state
- [ ] Claude interprets: QUESTION vs WORK_REQUEST vs CONTROL_REQUEST

### When Work Request Received
- [ ] Claude decomposes into objectives
- [ ] Claude calls `task-start` and `objective-add`
- [ ] Claude can read objective IDs from state
- [ ] Claude proceeds to implement → commit → verify loop

### When Implementing Objective
- [ ] Claude plans approach (consult capability guidance)
- [ ] Claude makes code changes
- [ ] Claude runs local tests if available
- [ ] Claude self-reviews
- [ ] Claude commits: `git add`, `git commit`, `push-gated.sh`
- [ ] Claude captures commit SHA

### When Verifying via CI
- [ ] Claude calls `ci-watch.sh --sha <sha>`
- [ ] Waits for GitHub Actions result
- [ ] Reads real CI logs if FAIL
- [ ] Classifies failure (compile? test? infra?)
- [ ] Diagnoses root cause (MY CODE? INFRA? UNKNOWN?)
- [ ] If MY CODE: implements fix, commits, retries
- [ ] If INFRA: checks known issues, escalates if needed
- [ ] Updates state with `ci-update` or `objective-complete` or `objective-fail`

### When Failure Occurs
- [ ] Claude has CI_FAILURE_DIAGNOSIS.md guidance
- [ ] Claude reads actual GitHub Actions logs
- [ ] Claude classifies failure type using decision tree
- [ ] Claude determines if fix is in scope
- [ ] Claude makes focused fix (not random code changes)
- [ ] Claude respects attempt limit (max 5 per objective)
- [ ] Claude escalates if limits exceeded

### When Objective Completes
- [ ] Claude calls `objective-complete <id> <sha> <url>`
- [ ] Reads state for next PENDING objective
- [ ] Repeats: plan → implement → commit → verify

### When Task Completes
- [ ] All objectives VERIFIED
- [ ] Claude calls `task-done`
- [ ] Claude reports summary
- [ ] Beast Mode stays ON for next task

## Go/No-Go Decision

**Prerequisites Met:**
- [ ] All files exist and are functional
- [ ] All documentation created
- [ ] Workflow is clear and documented
- [ ] Tools are tested and working
- [ ] Non-negotiables are explicit

**Then: ✅ READY FOR v4 WORKFLOWS**

Status at start: 🟡 READY-ISH (tools work, docs incomplete)
Status after Phase 4: ✅ READY (complete, tested, documented)
```

#### 4.3 Create Transition Guide for Users/Devs
**File to create:** `.claude/BEAST_MODE_V4_TRANSITION.md`

```markdown
# Beast Mode v3 → v4 Transition Guide

## What Changed

### v3 (Old)
- Shell script (`beastmode_workflow.sh`) drives the workflow
- Uses `--intake` and `--advance` subcommands
- Generates `GUIDANCE_NEEDED` signals
- Shell decides when to retry, when to move forward
- Claude implements per guidance, but workflow is orchestrated by shell

### v4 (New)
- Claude Code is the brain 🧠
- Hook injects state on every prompt
- Claude reads state, decides everything
- Claude implements, commits, pushes, watches CI, diagnoses failures
- Shell is the environment (tools only, no orchestration)

## For Claude Code

**Before (v3):**
- Wait for shell to tell you what to do
- Implement when given `GUIDANCE_NEEDED`
- Report back when done

**After (v4):**
- Read injected state block on every prompt
- Interpret user intent: question? work? control?
- Decompose work into objectives
- For each objective: implement → commit → watch CI → verify or retry
- Use tools to record state decisions
- Loop until all objectives verified

**Start here:** Read `.claude/CLAUDE_V4_WORKFLOW.md`

## For Users

**How to use Beast Mode v4:**

```
1. Activate: /minehost-autonomous
   (or /minehost-autonomous "task description")

2. Claude becomes autonomous
   - Understands your task
   - Creates objectives
   - Implements and verifies
   - Handles failures

3. Monitor via state:
   ./tools/bm-state.sh status

4. Control workflow:
   /minehost-autonomous        # Toggle OFF
   OR use: task-pause, task-resume, task-cancel

5. When done:
   Claude marks task complete
   All objectives verified
```

## Backward Compatibility

v3 scripts archived in `.claude/scripts-v3-archive/` for reference only.
They are NOT called by v4. Do not resurrect them without explicit reason.

## Known Issues & Constraints

- Local compile/test gates unavailable on this device (Termux)
- Rely on GitHub Actions as authoritative verification
- Known baseline CI failures documented in CLAUDE.md
- See CI_FAILURE_DIAGNOSIS.md for how to handle failures

## Validation

Run the integration test to verify v4 is working:
```bash
# Manual walkthrough (doesn't execute, just validates structure)
cat .claude/BEAST_MODE_V4_TEST.md
```

If you make test changes:
```bash
# Run actual test
./tools/bm-state.sh status        # Should work
git status                         # Should show clean tree
# (Would run through steps if doing actual test)
```
```

### Phase 4 Verification
```bash
# After phase 4:
✅ BEAST_MODE_V4_TEST.md created (integration test scenario)
✅ BEAST_MODE_V4_READY.md created (readiness checklist)
✅ BEAST_MODE_V4_TRANSITION.md created (v3→v4 guide)
✅ All pieces documented and linked
✅ Clear path for validation
```

### Phase 4 Success Criteria
- [ ] Integration test scenario complete and walkable
- [ ] Readiness checklist comprehensive (infra, docs, workflow, constraints)
- [ ] Transition guide explains v3→v4 clearly
- [ ] All four phases' deliverables documented and linked
- [ ] No ambiguity about what v4 is or how to use it
- [ ] Validation path clear (can run test scenario)

---

## Summary Table

| Phase | Title | Deliverables | Validation |
|-------|-------|--------------|-----------|
| 1 | Cleanup | v3 archived, arch docs updated, hook verified | No v3 ambiguity in codebase |
| 2 | Claude's Workflow | 7-step workflow doc, cheat sheet, SKILL update | Claude has clear instructions |
| 3 | CI Failures | Diagnosis guide, decision tree, examples | Claude handles failures systematically |
| 4 | Integration | Test scenario, readiness checklist, transition guide | All pieces documented and linked |

---

## How to Use This Plan

1. **Start Phase 1** — Takes 30 min. Archive v3, update docs.
2. **Validate Phase 1** — Verify no v3 scripts active, codebase reads as v4.
3. **Start Phase 2** — Takes 45 min. Document Claude's 7-step workflow.
4. **Validate Phase 2** — Can you read the docs and understand what Claude should do?
5. **Start Phase 3** — Takes 45 min. Document CI failure diagnosis.
6. **Validate Phase 3** — Can you read docs and handle all failure types?
7. **Start Phase 4** — Takes 30 min. Create test scenario and checklists.
8. **Validate Phase 4** — Can you walk through test scenario successfully?
9. **Done** — Beast Mode v4 is complete, documented, and ready.

---

## Timeline

**Recommended:** 
- Phase 1 & 2: Today (1.25 hours)
- Phase 3 & 4: Tomorrow (1.25 hours)

**Or compressed:** 2.5 hours straight through

---

## Success Metrics

After all 4 phases:
- ✅ No architectural ambiguity (v4 only, v3 archived)
- ✅ Claude has explicit workflow to follow (7-step documented)
- ✅ Failures are diagnosed systematically (decision tree, examples)
- ✅ Integration is tested and validated (test scenario works)
- ✅ Ready for real Beast Mode autonomous workflows
