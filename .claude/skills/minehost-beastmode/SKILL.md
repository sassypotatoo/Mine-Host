# MineHost Beast Mode Skill

## Overview

Beast Mode v3 is a workflow orchestration layer that manages state, objectives, and verification for autonomous tasks in the MineHost repository. It does not make implementation decisions but rather orchestrates the workflow through state management and message classification.

## When to Use

Use for any autonomous implementation task in the MineHost repo where you want to leverage the Beast Mode v3 workflow orchestration system. This skill provides the `/minehost-autonomous` command and the UserPromptSubmit hook to enable Beast Mode.

## Core Loop

1. Read the project context (if any) from the user's message or existing state.
2. Classify the user intent into QUESTION, WORK_REQUEST, or CONTROL_REQUEST.
3. If Beast Mode is ON and the intent is a WORK_REQUEST, update the current task and enhance the prompt with Beast Mode context.
4. If the intent is a CONTROL_REQUEST (specifically `/minehost-autonomous`), toggle the Beast Mode state.
5. Otherwise, allow normal processing.

## State Management

Beast Mode state is persisted in `.claude/beastmode_state.json` and includes:
   - beastModeEnabled: boolean
   - currentTask: string
   - objectives: array
   - currentObjectiveId: string|null
   - gitBranch: string
   - lastCiRunUrl: string
   - knownIssues: array
   - blockers: array
   - timestamp: string
   - workflowStatus: string

## Commands

- `/minehost-autonomous`: Toggles Beast Mode ON/OFF.
- `/minehost-autonomous "work request"`: Activates Beast Mode ON and sets the current task.

## Integration

This skill works through a UserPromptSubmit hook that intercepts all user messages when Beast Mode is ON and classifies them to provide contextual prompting.

## Notes

- Beast Mode never makes implementation decisions; it only manages state and workflow.
- Beast Mode does not invoke skills, MCP servers, plugins, or sub-agents.

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