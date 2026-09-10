# Beast Mode v3 - Persistent Autonomous Workflow

This command toggles Beast Mode ON/OFF and handles the autonomous workflow.

## Beast Mode Toggle

When Beast Mode is OFF and this command is invoked:
1. Execute "/effort max" first
2. Show: 
   "🔥 BEAST MODE: ON"
   "⚡ Effort: MAX"
3. If the invocation includes a work request, begin working immediately
4. If no work request exists, remain enabled for future user messages

When Beast Mode is ON and this command is invoked:
1. Disable autonomous behavior
2. Show: "🛑 BEAST MODE: OFF"
3. Return to normal Claude Code behavior

## State Management

Beast Mode state is persisted in `.claude/beastmode_state.json` to survive:
- Context compaction
- Claude restart
- Long sessions

The state includes:
- Beast Mode enabled/disabled
- Current task
- Objectives
- Current objective
- Objective status
- Attempts
- Latest relevant commit
- CI status
- Blockers

## Message Handling

While Beast Mode is ON, each user message is semantically interpreted as:
- QUESTION/EXPLANATION/STATUS/DISCUSSION: Answer normally, no repository changes
- WORK REQUEST: Enter autonomous workflow
- CONTROL REQUEST: Handle pause/stop/resume/cancel/etc.

## Autonomous Workflow

For a WORK REQUEST:
1. Understand the request
2. Break into logical objectives as needed
3. Claude decides implementation strategy
4. Implement current objective
5. Use local verification only when genuinely available/useful
6. Commit meaningful work when appropriate
7. Push using existing gated push mechanism (`./tools/push-gated.sh`)
8. Wait for GitHub Actions for exact pushed commit
9. Inspect CI result
10. If PASS, determine if objective's verification is satisfied
11. Mark objective complete/verified only when justified
12. Continue to next objective
13. If FAIL:
    - Inspect actual GitHub Actions failure logs
    - Classify failure before changing source
    - Diagnose actual cause
    - Fix appropriate problem
    - Commit/push again
    - Run CI again
    - Repeat until objective passes or genuine blocker/escalation

## Claude Remains the Brain

Claude dynamically decides:
- Whether to use "/plan"
- Which Skills to use
- Whether gstack is useful
- Which gstack capability is useful
- Whether MCPs are useful
- Whether specialist agents are useful
- Whether browser/device tools are useful
- Whether research is useful
- Whether review/simplify/security tooling is useful
- How to break task into objectives
- What to implement
- When to batch related edits
- When to commit/push
- What next action should be

## Gstack Treatment

Treat gstack as an ordinary Claude Code Skill - belongs with other Skills:
- gstack
- MineHost skills
- security
- code-review
- other installed skills

Do NOT create separate gstack subsystem or force gstack on every task.

## Repository Rules

Preserve existing MineHost protections:
- No fake functionality/state
- Protected systems stay protected unless concrete evidence requires change
- No PRoot/Termux resurrection in product architecture
- World imports remain fail-closed
- Do not overwrite user work
- Do not force-push
- Do not blindly reset/stash/clean user changes
- Existing Claude Code permissions remain authoritative

Use existing:
- "./tools/push-gated.sh"
- "./tools/ci-watch.sh"
- "./tools/ci-state-update.sh"
Only according to their ACTUAL CURRENT interfaces.

## CI Behavior

GitHub Actions is authoritative external build verification (Termux lacks reliable local Android/JDK/Gradle/device verification).

Per CI verification:
- Verify it corresponds to exact pushed commit
- Inspect actual run
- Distinguish successful CI from merely submitted/running CI
- On failure, inspect real logs
- Do not edit source code for runner/network/infrastructure problem without evidence source is responsible

Existing repository CI should remain intact unless implementation evidence requires relevant change.