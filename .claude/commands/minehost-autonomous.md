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
- The UserPromptSubmit hook will automatically inject context into every message
- WORK requests will be processed through the Beast Mode workflow
- QUESTION messages will receive Beast Mode context
- CONTROL requests (like `/minehost-autonomous` again) will toggle the state

When Beast Mode is turned OFF:
- Sets `beastModeEnabled` to false
- Clears `currentTask` and workflow status
- Returns to normal Claude Code processing

## Integration

This command works through the Beast Mode UserPromptSubmit hook which:
- Intercepts all user messages when Beast Mode is ON
- Injects the current state context so Claude Code can make informed decisions
- Routes WORK requests to the autonomous workflow processing system
- Provides contextual prompting to Claude Code for better assistance
- Never makes implementation decisions - only manages workflow state

## State Management

All state is persisted in `.claude/beastmode_state.json` and includes:
- Beast Mode enabled/disabled status
- Current task description
- Objectives list with tracking
- Verification status and retry counts
- Git commit and CI integration data
