# Beast Mode v4 Transition Guide

**Last Updated:** 2026-09-13
**Status:** Production Ready
**Migration Path:** v3 archived → v4 active

---

## 1. WHAT CHANGED — v3 vs v4 Architecture Comparison

### v3: Event-Driven Workflow (Archived)

**Architecture:**
- Central workflow orchestrator managed state machine
- Multiple decision points across scripts
- Objectives tracked in separate state file
- CI monitoring as optional step
- Claude received context but not full decision authority

**Flow:**
```
User Input → Hook → Orchestrator → State Update → Tool Execution
                ↓
        Orchestrator loops (owns control flow)
```

**Pain Points:**
- Multiple systems making decisions (orchestrator + Claude)
- State synchronization complexity
- CI results not always surfaced reliably
- Retry logic spread across multiple scripts
- Verification gate unclear in workflow

---

### v4: Claude-Centric Autonomous Workflow (Active)

**Architecture:**
- Claude is THE BRAIN; makes all decisions
- Hook only detects activation + injects context
- State is atomic read/write via single tool
- CI monitoring is mandatory verification gate
- Tools provide guardrails (not decisions)

**Flow:**
```
User Input → Hook (detects + injects) → Claude (reads state)
                                           ↓
                                    Claude decides
                                    Claude implements
                                    Claude commits
                                    Claude watches CI
                                    Claude verifies
                                    Claude loops
```

**Improvements:**
- Single decision maker (Claude)
- Clear responsibility: tools execute safely, Claude decides
- State always consistent (atomic operations)
- CI results drive verification (not optional)
- Retry logic centralized in Claude's loop
- Verification gate is mandatory before "done"

---

### Side-by-Side Comparison

| Aspect | v3 | v4 |
|--------|-----|-----|
| **Decision Maker** | Orchestrator + Claude | Claude only |
| **State Management** | Multiple files | Single atomic file |
| **Activation** | Hook injects state | Hook injects state |
| **Control Flow** | Orchestrator loops | Claude loops |
| **CI Verification** | Optional gate | Mandatory gate |
| **Retry Logic** | Script-driven | Claude-driven |
| **Commit Strategy** | Automated | Claude-directed |
| **Tool Responsibility** | Decide + execute | Execute only (guardrails) |
| **Error Recovery** | Orchestrator decides | Claude decides |

---

## 2. FOR CLAUDE CODE — Before (v3) vs After (v4)

### v3 Workflow (What Claude Did)

1. **Receive prompt** with work request
2. **Read state** to understand context
3. **Decompose work** into tasks
4. **Wait for orchestrator** to manage loop
5. **Respond to orchestrator** with implementation
6. **Trust orchestrator** to call CI tools
7. **Report results** when orchestrator says to

**Claude's Role:** Responder to orchestrator decisions

---

### v4 Workflow (What Claude Does Now)

1. **Receive prompt** with state block injected at top
2. **Read state** to understand full context (bm-state.sh)
3. **Classify intent:** Question / Work / Control
4. **For work requests:**
   - Parse acceptance criteria and scope
   - Decompose into concrete objectives
   - Create task via bm-state.sh
   - Add objectives via bm-state.sh
5. **For each objective:**
   - Read existing code patterns
   - Design solution (match project conventions)
   - Implement code changes
   - Run local tests if available
   - Stage and commit changes atomically
   - Record decision in state
   - **Call push-gated.sh** (safe push tool)
   - **Call ci-watch.sh** (real CI monitoring)
   - Parse CI results
   - If PASS: mark objective complete
   - If FAIL: read logs, diagnose, fix, retry (max 5 attempts)
6. **Loop until:**
   - All objectives verified via CI, OR
   - Hit retry limit → escalate to user
7. **Mark task complete** via bm-state.sh
8. **Report results** with evidence

**Claude's Role:** Complete autonomous brain making all decisions and driving the loop

---

### Key Mindset Shift

**v3:** "Here's what I would do; please execute it."
**v4:** "Here's what I'm doing; here's the proof it works."

---

## 3. FOR USERS — How to Use Beast Mode v4

### Activation

```bash
/minehost-beastmode Build authentication service with tests, database schema, and verification via CI
```

**What happens:**
1. Hook detects command
2. Injects full Beast Mode state into prompt
3. Claude receives context and begins autonomous work loop
4. Work continues until verified or blocked
5. Results reported with CI evidence

---

### During Execution

**What to expect:**
- Claude reads state and understands context
- Breaks work into objectives
- Commits and pushes regularly
- Monitors CI results (GitHub Actions)
- Fixes failures autonomously (up to 5 attempts)
- Escalates if stuck
- Reports when done

**What NOT to expect:**
- Fake/simulated CI results
- Force-push to remote
- Skipped verification
- Manual "confirm this step" prompts (standing instruction)
- Work without CI proof

---

### Ending the Loop

**Successful completion:**
- All objectives verified via CI
- Work pushed to feature branch
- Summary provided with CI links
- Control returns to user

**Hit retry limit:**
- Claude explains what failed
- Provides diagnostics and logs
- Asks for guidance
- Pauses until user decides next step

**User wants to stop:**
- Say: "stop this task" or "pause"
- Work saved in state
- Branch backed up
- Control returns to user

---

### Resuming Work

If task was paused or blocked:

```bash
/minehost-beastmode resume
```

Claude will:
1. Read saved state
2. Check where it left off
3. Continue with next objective
4. Resume CI verification loop

---

## 4. BACKWARD COMPATIBILITY — v3 Archived, Not Active

### v3 Status

**v3 is NOT deleted; it's archived:**
- Old scripts in: `.claude/scripts-v3-archive/`
- Old documentation remains for reference
- v3 activation hook NOT in place
- v3 tools NOT loaded by default

**Why archive instead of delete:**
- Historical reference if needed
- Can audit v3 decisions
- Can understand what changed
- Provides safety net (can't accidentally activate v3)

---

### Migration Path

**When you moved to v4:**

1. ✅ v3 orchestrator removed from hook chain
2. ✅ v4 activation hook installed (detects `/minehost-beastmode`)
3. ✅ Layer 2 (bm-state.sh) deployed
4. ✅ Layer 3 (push-gated.sh, ci-watch.sh) deployed
5. ✅ v4 documentation created
6. ✅ v3 scripts archived (not deleted)

**What this means:**
- v3 will NOT auto-activate (no trigger)
- v4 IS the default (explicit activation only)
- Can't accidentally run v3 workflows
- Old state won't interfere with new state

---

### If You Need v3 Reference

Check: `.claude/scripts-v3-archive/`

```
.claude/scripts-v3-archive/
├── orchestrator.sh
├── state-v3.json
├── phase-manager.sh
├── ci-gate.sh
└── [other v3 tools]
```

These are READ-ONLY reference copies. Don't modify or execute them; they're here for audit trail only.

---

## 5. KNOWN CONSTRAINTS — Same as CLAUDE.md

All v3 constraints carry forward to v4 (inherited):

### Infrastructure Constraints

1. **No Local JDK/Gradle/adb on Arm64-Android**
   - This Termux device has no JDK, Gradle, or adb
   - Local compile/test/device gates are UNAVAILABLE
   - All verification relies on CI (GitHub Actions)
   - **Implication:** CI is the only verification gate
   - **v4 approach:** ci-watch.sh is mandatory before "verified"

2. **Known Baseline CI Failure: Gradle-Wrapper Validation**
   - Expected failure at "Set up Gradle" step
   - Root cause known and documented in CI_FAILURE_DIAGNOSIS.md
   - Autonomous repair authorized once CI evidence confirms
   - **Implication:** First CI run may fail predictably
   - **v4 approach:** Auto-retry with diagnostics (up to 5 attempts)

3. **Git Safety Non-Negotiables**
   - Never force-push (enforced by push-gated.sh)
   - Never overwrite remote work
   - Must use push-gated.sh exclusively
   - ci-watch.sh is mandatory before declaring work complete
   - **Implication:** Push tool refuses unsafe operations
   - **v4 approach:** All pushes go through push-gated.sh (no override)

4. **No ripgrep on Arm64-Android**
   - Glob/Grep tools error on this system
   - Must use Bash grep, find, or Explore agents
   - **Implication:** Some search operations unavailable
   - **v4 approach:** Use alternative search tools documented

5. **No jq on System**
   - Missing jq utility
   - Use `gh ... --jq` or python3 instead
   - **Implication:** JSON parsing needs alternative
   - **v4 approach:** Use gh CLI or python3 for JSON

---

### System Constraints

- **Termux environment:** Not production; development only
- **Single branch:** Keep feature work on branch; main stays clean
- **CI feedback loop:** Expect 2-5 minute delays per CI run
- **Network:** GitHub API rate limits apply
- **Protected systems:** Don't modify auth, database schemas, engine launcher without documented evidence

---

### What v4 Adds (New Constraints)

1. **Mandatory CI Verification**
   - v3: CI was optional gate
   - v4: CI is REQUIRED before "done"
   - **Implication:** Work without CI proof won't be marked verified

2. **Atomic State Management**
   - v3: State could split across files
   - v4: State is single file, atomic operations
   - **Implication:** No partial state corruption possible

3. **Stateful Retry Loop**
   - v3: Retry logic in orchestrator
   - v4: Retry logic in Claude (with max 5 attempts)
   - **Implication:** Failures are investigated, not looped forever

4. **Single Decision Maker**
   - v3: Orchestrator + Claude shared decisions
   - v4: Claude makes all decisions
   - **Implication:** Clear accountability and reasoning

---

## 6. VALIDATION — How to Verify v4 Works

### Pre-Flight Checks

Before running Beast Mode v4, verify:

```bash
# 1. Check hook is installed
ls -la .claude/hooks/user_prompt_submit.py
# Should exist and be readable

# 2. Check Layer 2 tool exists
ls -la tools/bm-state.sh
# Should exist and be executable

# 3. Check Layer 3 tools exist
ls -la tools/push-gated.sh
ls -la tools/ci-watch.sh
# Both should exist and be executable

# 4. Check state file writable
touch .claude/beastmode_state.json
# Should not error

# 5. Check git is clean
git status
# Should show clean or only expected changes
```

---

### Test Activation

```bash
# Simulate activation (don't run full work)
/minehost-beastmode test activation

# Claude should:
# 1. Receive state injection
# 2. Recognize "test" in request
# 3. NOT enter autonomous loop
# 4. Report: "Beast Mode activated, ready for work request"
```

---

### Test State Management

```bash
# Test state read
./tools/bm-state.sh status
# Should output current state (likely IDLE if no task running)

# Test state write (safe operation)
./tools/bm-state.sh task-start "feature/test" "Test task"
# Should create task in state

# Test state read again
./tools/bm-state.sh status
# Should show task as ACTIVE

# Cleanup
./tools/bm-state.sh task-cancel
# Should reset to IDLE
```

---

### Test Safe Push Tool

```bash
# Create a test commit (if git is dirty)
git add .
git commit -m "Test commit for push-gated verification"

# Test safe push (no force attempt)
./tools/push-gated.sh push
# Should push successfully OR report why it can't

# Verify remote received commit
git log origin/HEAD -1
# Should show your test commit (or close to it)
```

---

### Test CI Monitoring

```bash
# After a successful push, get the commit SHA
SHA=$(git rev-parse HEAD)

# Test CI status check
./tools/ci-watch.sh --sha $SHA
# Should poll GitHub Actions and report status
# Exit 0: PASS, Exit 1: FAIL, Exit 2: inconclusive

# If you want to watch live
./tools/ci-watch.sh --follow
# Should stream CI logs in real-time
```

---

### Full Integration Test

**Scenario:** Small, low-risk feature (e.g., "Add a debug log line")

```bash
/minehost-beastmode Add a debug log statement to MainActivity to verify v4 workflow
```

**Claude will:**
1. Read state (should be IDLE)
2. Create task
3. Add objective
4. Implement change (one line)
5. Commit and push
6. Watch CI
7. Verify tests pass
8. Mark done and report

**You verify:**
- [ ] State went from IDLE → ACTIVE → COMPLETE
- [ ] Commit appeared on remote branch
- [ ] CI ran (GitHub Actions shows result)
- [ ] Tests passed (or failed with clear reason)
- [ ] Summary includes CI link as proof

---

### Validation Checklist

- [x] Hook detects activation command
- [x] State injection happens automatically
- [x] bm-state.sh reads state correctly
- [x] bm-state.sh writes state atomically
- [x] push-gated.sh refuses `--force`
- [x] push-gated.sh allows safe push
- [x] ci-watch.sh surfaces real CI results (not simulated)
- [x] ci-watch.sh correctly interprets pass/fail
- [x] Claude decision loop stays autonomous
- [x] Retry logic works (fails gracefully after max attempts)
- [x] Escalation message is clear
- [x] Completion summary has CI evidence

---

## 7. QUICK REFERENCE — Where to Find Answers

### Architecture Questions

**"How does Beast Mode v4 work?"**
→ Read: `.claude/BEAST_MODE_V4_ARCHITECTURE.md`
- 4-layer architecture explained
- Responsibility matrix
- Example workflows
- Non-negotiables

**"What changed from v3 to v4?"**
→ Read: This document (Section 1: WHAT CHANGED)
- Side-by-side comparison
- v3 vs v4 architecture
- Migration path

---

### Claude Code Usage

**"What should Claude Code do in v4?"**
→ Read: `.claude/CLAUDE_V4_WORKFLOW.md`
- Step-by-step workflow
- When to classify intent
- How to loop until done
- When to escalate

**"How do I use state management?"**
→ Read: `.claude/BM_STATE_CHEAT_SHEET.md`
- All state commands
- Typical workflow sequence
- Common patterns

---

### User-Facing Instructions

**"How do I activate Beast Mode?"**
→ Use: `/minehost-beastmode <work description>`
→ See Section 3 of this document

**"What does Beast Mode do during work?"**
→ See Section 3: FOR USERS

**"How do I stop or pause?"**
→ Say: "stop this task" or "pause"
→ Work saves to state automatically

---

### Tools & Execution

**"How do I safely push changes?"**
→ Use: `./tools/push-gated.sh`
→ Refuses force-push
→ Refuses unverified pushes

**"How do I monitor CI results?"**
→ Use: `./tools/ci-watch.sh --sha <commit-sha>`
→ Returns: 0 (pass), 1 (fail), 2 (unclear)

**"How do I read/write state?"**
→ Use: `./tools/bm-state.sh <command>`
→ Read: BM_STATE_CHEAT_SHEET.md for all commands

---

### Troubleshooting

**"CI failed; what do I do?"**
→ Read: `.claude/CI_FAILURE_DIAGNOSIS.md`
- Common failures documented
- Known Gradle-wrapper issue
- Diagnostic procedures

**"Beast Mode is stuck; how do I debug?"**
→ Read: `.claude/BEAST_MODE_V4_AUDIT.md`
- Audit trail format
- How to trace decisions
- How to find where it stuck

**"State file corrupted; what now?"**
→ Contact user; don't modify directly
→ Can reset via: `./tools/bm-state.sh task-cancel`

---

### Verification & Testing

**"How do I know v4 is working?"**
→ Follow: Section 6 of this document
- Pre-flight checks
- Test activation
- Full integration test
- Validation checklist

**"How do I audit what Beast Mode did?"**
→ Read: `.claude/BEAST_MODE_V4_AUDIT.md`
- Audit trail schema
- How decisions are recorded
- How to verify compliance

---

### Documentation Index

| Document | Purpose |
|----------|---------|
| BEAST_MODE_V4_ARCHITECTURE.md | 4-layer design, responsibility matrix, non-negotiables |
| CLAUDE_V4_WORKFLOW.md | Step-by-step Claude workflow, intent classification, loop logic |
| BM_STATE_CHEAT_SHEET.md | State commands, typical sequences, JSON schema |
| BEAST_MODE_V4_AUDIT.md | Audit trail format, compliance verification |
| CI_FAILURE_DIAGNOSIS.md | Known CI failures, diagnostics, recovery |
| BEAST_MODE_V4_TEST.md | Test procedures for all 4 layers |
| BEAST_MODE_V4_READY.md | Readiness checklist, go/no-go criteria |
| BEAST_MODE_V4_TRANSITION.md | This file; v3 vs v4 migration guide |
| Brain.md | Project context and session orientation |
| CLAUDE.md | MineHost constraints, non-negotiables (upstream) |

---

## Summary

**v3 → v4 Migration Complete:**

1. ✅ **Architecture:** Event-driven orchestrator → Claude-centric brain
2. ✅ **Decision Maker:** Shared (v3) → Single (Claude in v4)
3. ✅ **State:** Multiple files → Single atomic file
4. ✅ **Verification:** Optional → Mandatory CI gate
5. ✅ **Tools:** Decision makers → Safe execution only
6. ✅ **Activation:** Hook is passive; Claude drives loop
7. ✅ **Constraints:** All v3 inherited; new ones documented
8. ✅ **Safety:** Force-push blocked, CI results real, verification mandatory

**v4 is now the active system.**
**v3 is archived for reference only.**
**Ready for production use.**

---

**Questions? Start here:**
- Architecture: See BEAST_MODE_V4_ARCHITECTURE.md
- Claude workflow: See CLAUDE_V4_WORKFLOW.md
- Commands: See BM_STATE_CHEAT_SHEET.md
- Troubleshooting: See CI_FAILURE_DIAGNOSIS.md
- Testing: See BEAST_MODE_V4_TEST.md
