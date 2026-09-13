# Beast Mode V4 Architecture

## Core Principle

**Claude Code is THE BRAIN**
- Reads all context
- Decides all intents
- Plans all work
- Implements all changes
- Commits and verifies all results
- Loops until complete

**Beast Mode provides the ENVIRONMENT**
- Detects activation signals
- Manages state atomically
- Provides safe-push tooling
- Monitors CI results
- Never drives workflow
- Never makes decisions

---

## 4-Layer Architecture

### Layer 1: Activation (Hook)

**File:** `.claude/hooks/user_prompt_submit.py`

**Responsibility:**
- Detects `/minehost-autonomous` command in user input
- Injects Beast Mode state into context
- Passes control to Claude immediately
- Never drives workflow logic

**What Claude receives:**
- Full Beast Mode state on every prompt
- Current work context
- Previous decisions and their outcomes
- Ready to decide next action

---

### Layer 2: State Management (Tools)

**File:** `tools/bm-state.sh`

**Responsibility:**
- Atomic read of Beast Mode state
- Atomic write of Beast Mode state
- Prevents concurrent modifications
- Single source of truth for work queue and decisions

**What Claude uses:**
- `bm-state read` - Get current state (work queue, context, outcomes)
- `bm-state write` - Record decisions (intent, next steps, outcomes)
- `bm-state append` - Add work items to queue
- State is always consistent; Claude decides what to record

---

### Layer 3: Verification (Tools)

**File:** `tools/push-gated.sh`, `tools/ci-watch.sh`

**Responsibility:**
- `push-gated.sh` - Safe commit/push with guard rails
  - Refuses force-push
  - Refuses without real changes
  - Refuses without CI verification config
  - Allows Claude to commit real work safely

- `ci-watch.sh` - CI result monitoring
  - Polls CI status
  - Surfaces real results to Claude
  - Never fakes outcomes
  - Claude decides if work is verified

**What Claude uses:**
- `push-gated.sh commit` - Commit changes with message
- `push-gated.sh push` - Push branch with safety checks
- `ci-watch.sh` - Monitor and report CI status
- Claude interprets results and decides next step

---

### Layer 4: Claude's Brain

**Responsibility:**
- Everything else
- Read state on every turn
- Decide: is this a question? work? control flow?
- Plan the approach
- Implement the solution
- Record decisions in state
- Commit and push work
- Watch CI results
- Verify completion
- Loop until task is done

**Claude drives:**
- Context interpretation
- Intent detection
- Architecture decisions
- Implementation details
- Verification criteria
- Loop termination

---

## Responsibility Matrix

| Aspect | Layer 1 (Hook) | Layer 2 (State) | Layer 3 (Verify) | Layer 4 (Brain) |
|--------|---|---|---|---|
| **Detects activation** | ✓ | | | |
| **Injects context** | ✓ | | | |
| **Reads state** | | ✓ | | Claude calls it |
| **Writes state** | | ✓ | | Claude calls it |
| **Decides what to do** | | | | ✓ |
| **Implements code** | | | | ✓ |
| **Plans approach** | | | | ✓ |
| **Commits work** | | | ✓ | Claude calls it |
| **Checks CI** | | | ✓ | Claude calls it |
| **Interprets results** | | | | ✓ |
| **Decides if done** | | | | ✓ |
| **Drives loop** | | | | ✓ |
| **Forces push** | Never | Never | Never | Never |
| **Fakes CI** | Never | Never | Never | Never |
| **Skips verification** | Never | Never | Never | Never |

---

## Example Flow

### Request: "Build auth system with Beast Mode"

**Claude receives:**
- `/minehost-autonomous` detection
- Current state (empty or prior work)
- Full conversation context

**Claude decides:** This is work. Break it into: schema design, implementation, tests, verification.

**Claude reads state:** Checks what's already done (first run = nothing).

**Claude implements:**
- Design database schema
- Write auth service code
- Write tests
- Create branch and commit

**Claude calls `push-gated.sh`:**
- Commits with real message
- Pushes to feature branch
- Gets confirmation (no force-push allowed)

**Claude calls `ci-watch.sh`:**
- Polls GitHub Actions / CI system
- Receives real test results
- Surfaces failures or passes

**Claude verifies:**
- Tests pass: continue to next item
- Tests fail: read logs, fix code, re-commit, re-watch

**Claude records state:**
- What was done
- What passed verification
- What's next in queue

**Claude loops:**
- If more work items exist: repeat
- If done: report completion
- If blocked: escalate to user

---

## Non-Negotiables (V4 Spec)

### Never Force-Push
- `push-gated.sh` refuses `--force`
- Protects branch history
- Forces clean, atomic commits
- If Claude needs to amend: create new commit, don't force-rewrite

### Never Fake CI Results
- `ci-watch.sh` reports real status only
- No simulation, no mocking outcomes
- Claude sees actual pass/fail signals
- If CI not configured: Claude does not push

### Never Skip Verification
- Work must pass tests before marking done
- Manual verification required for non-testable changes
- Claude documents what was verified
- State records verification checkpoint

### Never Modify Protected Systems Without Evidence
- Protected: auth, database schemas, production configs, permissions
- Requires: prior approval, documented rationale, test verification
- Claude reads existing code before changing
- Changes are logged in commit messages

### Always Use Real Tools
- No shell tricks, no workarounds
- Layer 2 for state (not environment variables)
- Layer 3 for safe push/CI check (not manual git commands)
- Layer 4 logic stays in Claude (no hidden scripts)
- Tools are transparent, auditable, reversible

---

## Activation Command

```
/minehost-autonomous <work description>
```

User provides:
- Clear work request
- Any context needed
- Acceptance criteria

Claude receives:
- Full Beast Mode context
- All prior decisions
- All prior outcomes
- Permission to loop until verified

---

## State Schema

```json
{
  "status": "active|paused|complete",
  "work_queue": [
    {
      "id": "work-1",
      "description": "Implement auth service",
      "status": "done|in_progress|pending",
      "branch": "feature/auth-v1",
      "verification": "tests_passed|manual_reviewed|pending",
      "created_at": "2026-09-13T17:30:00Z",
      "completed_at": "2026-09-13T17:45:00Z"
    }
  ],
  "current_branch": "feature/auth-v1",
  "last_decision": {
    "intent": "implement_auth",
    "action": "commit_and_push",
    "timestamp": "2026-09-13T17:45:00Z",
    "result": "success"
  },
  "context": {
    "user_request": "Build auth system",
    "acceptance_criteria": "Tests pass, no auth bypass",
    "started_at": "2026-09-13T17:30:00Z"
  }
}
```

---

## Summary

Beast Mode V4 is a **4-layer system where Claude is always the brain**:

1. **Activation** detects and injects context
2. **State Management** provides atomic read/write
3. **Verification** provides safe commit/CI tools
4. **Claude** does everything else: decides, plans, implements, verifies, loops

No layer drives workflow. No layer fakes results. Claude controls the loop, makes all decisions, and uses tools to execute safely.
