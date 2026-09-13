# MineHost Beast Mode Skill

## Overview

Beast Mode v4 is a lightweight environment layer that provides state management and safe-push tooling for autonomous tasks in the MineHost repository. Claude Code is the brain—it reads context, decides intent, plans work, implements changes, commits, and verifies results. Beast Mode provides only: activation detection, atomic state read/write, and safe commit/CI tools.

## When to Use

Use for any autonomous implementation task in the MineHost repo where you want Claude Code to drive the complete workflow loop with Beast Mode providing environment support. This skill provides the `/minehost-autonomous` command and the Hook that injects Beast Mode state into context.

## v4 Workflow Integration

This skill provides the ENVIRONMENT. Claude Code provides the BRAIN.

When you see `BEAST MODE ACTIVE` in your context block:

1. **Read state** via `bm-state read` — understand current work queue, completed items, previous decisions
2. **Classify intent** — is this a question, work continuation, or control request?
3. **Plan approach** — break work into concrete implementation steps
4. **Implement changes** — write code, modify configs, create tests
5. **Commit and push** — use `push-gated.sh` to safely commit work
6. **Watch CI** — use `ci-watch.sh` to verify results (never fake CI outcomes)
7. **Loop or report** — update state, continue to next item or report completion

Key principle: Never claim verification without real evidence. Always use actual Git and CI results.

## v4 Workflow Documents

When Beast Mode is ON and a work request arrives:

1. **Claude's Workflow**: Read `.claude/CLAUDE_V4_WORKFLOW.md`
   - Explicit 7-step decision loop
   - What to do at each phase
   - How to handle failures
   - When to escalate

2. **State Commands**: Reference `.claude/BM_STATE_CHEAT_SHEET.md`
   - Quick command reference
   - Typical workflow sequence
   - State file location

3. **Capability Guidance**: Use this skill's "Capability Selection Guidance" section
   - Categories: Large codebase, Android dev, Security, Supabase, UI/Browser, Simple tasks
   - Decision: Use skill? MCP? Sub-agent? None?
   - Principle: Only what's genuinely useful

Follow the decision loop in CLAUDE_V4_WORKFLOW.md, use tools from BM_STATE_CHEAT_SHEET.md, and employ capabilities from Capability Selection Guidance based on the specific task.

## Capability Selection Guidance

This section provides decision criteria for Claude Code when selecting tools, skills, MCP servers, and sub-agents during v4 workflow execution. Beast Mode provides only state and safe-push tools—Claude Code decides all capability usage.

### Core Decision Rule

**Use only what's genuinely useful, not everything available.**

For each work item or sub-task:
1. What is the nature of the change? (Android, security, database, UI, simple edit, etc.)
2. Does it require deep codebase exploration, or do you already know the scope?
3. Are there security or compliance implications?
4. Is there a risk of breaking protected systems?
5. What evidence will verify the work is correct?

Then decide: skill? MCP? sub-agent? Just use basic tools? None of the above?

**Avoid premature capability invocation.** Many tasks complete with simple file edits and tests. Load heavy capabilities only when they solve a concrete problem, not because they're available.

### Decision Points

Beast Mode provides no guidance signals. Claude Code decides autonomously based on:
- Scope clarity (fuzzy → explore first, clear → implement directly)
- Complexity (trivial → direct edit, complex → consider sub-agent or skill)
- Risk level (low → proceed, high → read and plan first)
- Available evidence (missing → investigate, present → decide)

### Available Capabilities in This Environment

These capabilities are available in this environment. Decide autonomously which (if any) to use:

- **Skills**: superpowers, code-review, remember, code-simplifier, frontend-design, gstack
- **MCP Servers**: context7, supabase, playwright, chrome-devtools-mcp
- **Security**: claude-security, security-guidance
- **This skill**: minehost-beastmode (for workflow knowledge)

Never reference or invoke capabilities not on this list. Use tools deliberately based on actual need, not inventory completeness.

### Capability Categories & When to Consider Them

#### 1. Large Codebase Analysis & Exploration
**When**: Understanding complex codebases, tracing execution paths, mapping architecture.
**Capabilities**: superpowers (brainstorming, systematic-debugging, subagent-driven-development), gstack
**Example decision**: "This Android app modifies the JVM launcher—I need to understand the full call chain before touching it" → superpowers/systematic-debugging
**Avoid when**: You already know exactly what needs changing, or the change is localized to one file.

#### 2. Android Development & Build Systems
**When**: Gradle/Kotlin/Java changes, build config, resource management, Android-specific problems.
**Capabilities**: context7 (Android/Gradle/Kotlin docs), code-simplifier
**Example decision**: "Gradle build is failing on a custom task—I need current documentation" → context7
**Avoid when**: Non-Android changes, or pure configuration edits you already understand.

#### 3. Security Review & Analysis
**When**: Downloads, binaries, command execution, JNI/native loading, networking, auth, permissions, crypto, user input handling.
**Capabilities**: claude-security (scan-changes, scan-codebase), security-guidance, code-review with security focus
**Example decision**: "Adding file extraction and native library loading—this is security-sensitive" → claude-security scan
**Avoid when**: Pure UI changes or non-security-relevant edits.

#### 4. Supabase Development
**When**: Database schema changes, RLS policies, Supabase client usage, authentication flows.
**Capabilities**: supabase MCP (schema migration, function execution), context7 (Supabase docs)
**Example decision**: "Need to update RLS policies to match new auth flow" → supabase MCP
**Avoid when**: Non-database changes, frontend-only updates.

#### 5. UI/Browser Testing & Automation
**When**: Frontend changes, user interaction flows, visual testing, cross-browser compatibility.
**Capabilities**: playwright, chrome-devtools-mcp, frontend-design
**Example decision**: "Need to verify login flow works on mobile and desktop" → playwright
**Avoid when**: Backend-only changes, API modifications.

#### 6. Simple Tasks & Local Edits
**When**: Trivial file modifications, documentation, simple bug fixes, configuration changes.
**Capabilities**: Basic file tools (Read/Edit/Write), code-simplifier for clarity
**Example decision**: "Update a config file and run tests locally" → just use file tools
**Avoid when**: You're unsure about scope or impact.

### Integration with v4 Workflow

When Beast Mode is active, you receive:
- **Full state context** via hook injection (work queue, completed items, current branch, prior decisions)
- **Permission to loop** until work is verified
- **Safe-push and CI tools** ready to use

For each work item in the queue:
1. **Understand scope** — read the description, check what's already done
2. **Make a decision** — "Do I need to explore first, or can I implement directly?"
3. **Consult this guidance** — does the nature of the work suggest a specific capability?
4. **Implement** — use only the capabilities you decided are necessary
5. **Verify** — run tests, check CI, use real evidence (never fake outcomes)
6. **Record progress** — update state via `bm-state write` when work is complete
7. **Loop** — continue to next item or report completion

**No guidance signals, no "GUIDANCE_NEEDED" tags.** You drive the complete loop autonomously, consulting this guide when deciding which capabilities to employ.

## Core Loop (v4)

1. **Activation** — User sends `/minehost-autonomous <work description>` or task is already active
2. **State Read** — Call `bm-state read` to get current work queue and completed items
3. **Intent Classification** — Understand if this is work continuation, a new request, or a control command
4. **Work Planning** — Break the current item into concrete implementation steps
5. **Implementation** — Execute the work using appropriate capabilities (or none)
6. **Verification** — Run tests, commit changes, watch CI for real results
7. **State Update** — Call `bm-state write` to record what was done and outcomes
8. **Loop Decision** — More items in queue? Continue. All done? Report completion.

Never simulate, never fake CI outcomes, never skip verification.

## State Management (v4)

Beast Mode state is persisted in `.claude/beastmode_state.json` and accessed via `bm-state` tool. State schema:

```json
{
  "status": "active|paused|complete",
  "work_queue": [
    {
      "id": "work-1",
      "description": "Clear work item description",
      "status": "pending|in_progress|done",
      "branch": "feature/item-name",
      "verification": "pending|tests_passed|manual_reviewed",
      "created_at": "ISO8601 timestamp",
      "completed_at": "ISO8601 timestamp or null"
    }
  ],
  "current_branch": "current feature branch name",
  "last_decision": {
    "intent": "human readable intent",
    "action": "what was done",
    "timestamp": "ISO8601 timestamp",
    "result": "success|failed|pending"
  },
  "context": {
    "user_request": "original work request",
    "acceptance_criteria": "how to verify success",
    "started_at": "ISO8601 timestamp"
  }
}
```

**Usage:**
- `bm-state read` — Get current work queue and state
- `bm-state write <json>` — Record decisions and outcomes
- `bm-state append <json>` — Add new work items to queue

## Commands

- `/minehost-autonomous <work description>` — Activate Beast Mode with a work request. Claude receives full state and loops until complete.
- `bm-state read` — Read current work queue and decision history
- `bm-state write <json>` — Record decisions, progress, and outcomes
- `push-gated.sh commit <message>` — Safely commit changes (refuses force-push, empty commits)
- `push-gated.sh push` — Push to branch with safety checks
- `ci-watch.sh` — Monitor and report real CI results

## Integration

This skill works through:

1. **Hook (Activation)** — `.claude/hooks/user_prompt_submit.py` detects `/minehost-autonomous` command and injects Beast Mode state into context.
2. **State Tool (Layer 2)** — `./tools/bm-state.sh` provides atomic read/write of work queue and decisions.
3. **Safe-Push Tool (Layer 3)** — `./tools/push-gated.sh` commits and pushes with guard rails (no force-push, no empty commits).
4. **CI Tool (Layer 3)** — `./tools/ci-watch.sh` monitors real CI results (never fakes outcomes).
5. **Claude Code (Layer 4)** — You read state, decide intent, plan work, implement, verify, and loop.

Beast Mode provides the environment. You provide all reasoning, decisions, and implementation.

## Notes

- **v4 is Claude-driven**: You read state, decide all intents, plan all work, implement all changes, commit, verify, and loop. Beast Mode is purely environmental.
- **No v3 signals**: v4 has no `GUIDANCE_NEEDED` or `GUIDANCE_PROVIDED` tags. You drive autonomously.
- **Capability autonomy**: You decide which capabilities to use (if any) based on actual need, not inventory.
- **No protected-system modifications without evidence**: Read existing code and document rationale before modifying auth, DB schemas, or native launchers.
- **All v3 references deprecated**: Old workflow files archived in `.claude/scripts-v3-archive/`. Use v4 architecture and tools only.

## Verification Integrity

Beast Mode enforces a hard verification-integrity invariant: never claim that an action occurred unless it was actually executed and concrete evidence exists.

This applies to:
  - implementation
  - builds
  - tests
  - commits
  - pushes
  - GitHub Actions
  - CI results
  - runtime verification
  - Skills
  - plugins
  - MCPs
  - sub-agents
  - tool usage
  - server/runtime behavior

Never infer success from:
  - code merely existing
  - a command being constructed
  - a command being described
  - expected behavior
  - Claude's reasoning
  - a previous successful run
  - an unverified state file
  - an assumed CI result

Never fabricate or simulate:
  - test results
  - CI results
  - commit SHAs
  - push success
  - runtime verification
  - tool/capability usage
  - completion status

If an action was not actually performed, report:
  UNVERIFIED

If evidence is missing, stale, contradictory, or cannot be tied to the relevant action/commit, the state MUST NOT be marked "VERIFIED".

For CI specifically:
  - verify the result belongs to the exact commit SHA being evaluated
  - do not use an unrelated successful workflow run as evidence
  - if CI status cannot be confirmed, remain UNVERIFIED

For Skills/plugins/MCPs/sub-agents:
  - only report a capability as USED if Claude Code actually invoked it
  - never infer usage because a capability was available or appropriate