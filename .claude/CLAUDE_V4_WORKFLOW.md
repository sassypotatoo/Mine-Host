# Claude Code v4 Workflow — When Beast Mode is ON

## Entry Point
Every prompt: Hook injects state block + original message.

## Step 1: Read & Understand Context (Always)
Explain:
- Look at injected state block at top of prompt
- Extract: current task, objectives, attempt counts, last CI result, last commit SHA
- Understand: Where are we in the workflow?

## Step 2: Classify User Intent (Always)
Three categories:
1. QUESTION / CHAT — "What's status? Tell me about X? How does Y work?"
   → Answer normally, don't start Beast Mode workflow
2. WORK REQUEST — "Fix authentication flow", "Implement dark mode", "Add Supabase integration"
   → Go to Step 3
3. CONTROL REQUEST — "/minehost-beastmode", "pause task", "stop and save", "switch tasks"
   → Handle control (use bm-state.sh commands)

## Step 3: Decompose Work into Objectives (For Work Requests)
1. Read request carefully
2. Understand the goal
3. Break into concrete, verifiable objectives:
   - Each 1-2 sentences
   - Independently verifiable via CI
   - Meaningful checkpoint
4. **Dynamic capability discovery**: Before planning implementation, run real session introspection to determine what is actually available right now:
   - Plugins: `claude plugin list --json` (+ optional per-plugin detail reads)
   - MCP servers: `claude mcp list` (+ plugin cache filesystem / installed_plugins.json as fallback only when CLI output is unavailable)
   - Session skills: inspect project-level `.claude/skills/` and any cache-discoverable installed skills for this session
   - Newly installed capabilities must automatically appear; removed/unavailable ones must automatically disappear
   - Track conceptual capability states for each candidate:
     - AVAILABLE = present in session right now
     - DISCOVERED = surfaced by introspection
     - RELEVANT = potentially useful for this objective
     - SELECTED = chosen for this objective
     - INVOKED = actually used during implementation
     - UNAVAILABLE = absent from current session
     - FAILED = attempted and failed
     - SKIPPED = considered and intentionally not used
   - Never conflate AVAILABLE != USED, DOCUMENTED != AVAILABLE, RELEVANT != REQUIRED, or UNAVAILABLE != WORKFLOW FAILURE
   - If a discovery command is unavailable or not permitted, continue with visible session surfaces and skip gracefully
   - Never repeatedly retry nonexistent commands, and never report usage of a command or capability that was not actually executed
   - Note which capabilities to use per objective, if any; if none apply, say so explicitly
   - Consult SKILL.md "Capability Selection Guidance"
5. Call: tools/bm-state.sh task-start "<branch>" "<task-description>"
6. For each objective: tools/bm-state.sh objective-add "<description>"
   - Returns objective ID (capture for later)
7. Read back state: tools/bm-state.sh status
8. Now structured task with objectives ready

## Step 4: For Each Objective — Implement → Commit → Verify Loop
While there are PENDING objectives:

a) Get current objective from state
   - Read: tools/bm-state.sh status
   - Find objective ID and description
   - Extract attempt count

b) Plan the approach
   - Read MineHost context (CLAUDE.md, master context docs)
   - Understand what files need to change
   - Review capabilities identified in Step 3 for this objective
   - Decide: Skill? MCP? Sub-agent? None? (or proceed with what was planned)

⚠️ PRE-IMPLEMENTATION GATE — Before writing ANY code, answer:
   "Which capabilities (if any) would materially improve this task?"
   Use dynamic capability discovery from the current session, not a hardcoded list.
   If none apply, say so explicitly.
   If you skip this check, you MUST go back and answer it before proceeding.

c) Implement the objective
   - Make code changes
   - Run local tests if available
   - Self-review work
   - Report: "Implementation complete, ready for CI verification"

d) Commit when ready
   - Stage: git add -u (tracked) + git add . (new files)
   - Commit: git commit -m "Implement objective: <description>"
   - Get SHA: git rev-parse HEAD
   - Push: ./tools/push-gated.sh
   - If fails: diagnose and fix

⚠️ POST-PUSH CHECKLIST — After EVERY push, before moving on:
   1. Run: ./tools/ci-watch.sh <sha> (MUST verify CI result — never skip)
   2. If FAIL → diagnose and fix (go to Step 5)
   3. If PASS → mark objective complete
   Skipping ci-watch.sh means you CANNOT claim verification. Report UNVERIFIED if you didn't run it.

e) Mark objective IN_PROGRESS
   - Call: tools/bm-state.sh objective-start <objective-id>
   - Auto-increments attempt counter

f) Watch CI
   - Call: ./tools/ci-watch.sh --sha <commit-sha>
   - Monitors GitHub Actions for this commit
   - Exit codes: 0=PASS, 1=FAIL, 2=inconclusive

g) Handle result
   - If PASS (exit 0):
     * Call: tools/bm-state.sh objective-complete <id> "<sha>" "<ci-url>"
     * Go to next objective (back to Step 4a)
   - If FAIL (exit 1):
     * Go to Step 5 (Diagnose Failure)
   - If inconclusive (exit 2):
     * Report: "CI result unclear. Verify manually."

## Step 5: Diagnose & Fix CI Failures
When CI fails (exit 1):

a) Read actual CI logs
   - ci-watch.sh prints failed-step logs
   - Capture and read them carefully
   - Classify failure:
     * COMPILE ERROR: Syntax/type errors
     * TEST FAILURE: Test assertions failed
     * BUILD SYSTEM: gradle/cmake config issue
     * INFRA: Network, auth, GitHub rate limit
     * UNKNOWN: Unclear from logs

b) Diagnose root cause
   - Example: "Set up Gradle" failure → INFRA (gradle-wrapper)
   - Example: "Test SettingsActivityTest failed" → TEST FAILURE (my code broke it)

c) Decide: Is this my responsibility?
   - Did MY CODE cause? → Fix the code
   - Known MineHost CI issue? → Check CLAUDE.md
   - Infrastructure? → Investigate, don't randomly modify app code
   - Unsure? → Ask for guidance, mark objective-fail with reason

d) Implement fix
   - Go back to Step 4c (implement)
   - Make focused fix (not random changes)
   - Commit, push

e) Loop back
   - Retry from Step 4e (mark IN_PROGRESS)
   - ci-watch.sh monitors new commit
   - Repeat until CI passes

f) Attempt limit
   - Max 5 attempts per objective
   - If attempt 5 fails:
     * Call: tools/bm-state.sh objective-fail <id> "<reason>"
     * Mark as FAILED_EXCEEDED
     * Report to user

## Step 6: Multi-Objective Sequencing
When current objective VERIFIED (CI passed):

1. Current objective → VERIFIED
2. Read state: tools/bm-state.sh status
3. Check: Any PENDING objectives?
   - YES → Go to Step 4a (implement next)
   - NO → Go to Step 7

Objectives sequential:
  Obj 1: IMPLEMENT → CI FAIL → RETRY → CI PASS → VERIFIED
  Obj 2: IMPLEMENT → CI PASS → VERIFIED
  Obj 3: ...
  All VERIFIED → Complete

## Step 7: Complete Task
When all objectives VERIFIED:

1. Call: tools/bm-state.sh task-done
2. Report summary:
   - Original task?
   - How many objectives?
   - How many CI iterations?
   - Any learnings?
3. Beast Mode stays ON (user can start new task)

## Critical: Claude Never Simulates
❌ DO NOT:
- Pretend CI passed without running it
- Skip ci-watch.sh monitoring
- Assume "should work" without verification
- Report "VERIFIED" without real GitHub Actions confirmation
- Fake test results
- Make up commit SHAs

✅ DO:
- Always call real tools
- Always read real CI logs
- Always verify before marking complete
- Report what actually happened
- If uncertain, ask for clarification

## Retry & Escalation
- Per objective: max 5 attempts
- If attempt 5 fails → FAILED_EXCEEDED
- User can manually investigate or request different approach
