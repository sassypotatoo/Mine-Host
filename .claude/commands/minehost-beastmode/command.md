---
name: minehost-beastmode
description: Toggle Beast Mode v4 ON/OFF (persistent master toggle)
group: Beast Mode
---

# Minehost-beastmode Command

Persistent master toggle for Beast Mode v4.

## Usage

```
/minehost-beastmode
```

## Behavior

- **OFF → ON**: Sets `beastModeEnabled = true`, shows "🔥 BEAST MODE: ON"
- **ON → OFF**: Sets `beastModeEnabled = false`, shows "🛑 BEAST MODE: OFF"

Normal user messages are the tasks — this command does NOT start a new task.

## How it works

The UserPromptSubmit hook intercepts this command:
1. Reads `.claude/beastmode_state.json`
2. Toggles `beastModeEnabled`
3. Replaces the prompt with the toggle message
4. While ON, injects Beast Mode context block into every subsequent prompt

## State

All state is in `.claude/beastmode_state.json`. Claude Code is the brain — Beast Mode provides environment only.
