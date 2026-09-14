---
name: minehost-autonomous
description: Activate Beast Mode v4 autonomous workflow (toggle ON/OFF or set task)
group: Beast Mode
---

# Minehost-autonomous Command

Toggle Beast Mode v4 workflow orchestration system.

## Usage

```
/minehost-autonomous [work request]
```

## Description

This command toggles the Beast Mode v4 workflow orchestration system on and off. When Beast Mode is ON, it manages autonomous workflow processing through the UserPromptSubmit hook, with Claude Code as the brain making all tactical decisions.

- **Without arguments**: Toggles Beast Mode state (ON ↔ OFF)
- **With work request**: Activates Beast Mode ON and sets the current task

## Examples

```
# Toggle Beast Mode ON/OFF
/minehost-autonomous

# Activate Beast Mode with a work request
/minehost-autonomous "Fix typo in README.md"

# Check current status (returns state information)
/minehost-autonomous status
```

## Behavior

When Beast Mode is turned ON:
- Sets `beastModeEnabled` to true in persistent state
- If a work request is provided, sets `currentTask` to that request
- The UserSubmit hook injects Beast Mode state into context
- Claude Code classifies incoming messages as WORK REQUEST, QUESTION, or CONTROL REQUEST
- WORK requests are processed through the Beast Mode workflow
- QUESTION messages receive Beast Mode context
- CONTROL requests (like `/minehost-autonomous` again) toggle the state

When Beast Mode is turned OFF:
- Sets `beastModeEnabled` to false
- Clears `currentTask` and workflow status
- Returns to normal Claude Code processing

## Integration

This command works through the Beast Mode UserPromptSubmit hook which:
- Injects Beast Mode state block into context when ON
- Provides state data (task, objectives, branch, CI status) for Claude Code to use
- Never classifies, routes, or makes decisions — Claude Code is the brain

## State Management

All state is persisted in `.claude/beastmode_state.json` and includes:
- Beast Mode enabled/disabled status
- Current task description
- Objectives list with tracking
- Verification status and retry counts
- Git commit and CI integration data