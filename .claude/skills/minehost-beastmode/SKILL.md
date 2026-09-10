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