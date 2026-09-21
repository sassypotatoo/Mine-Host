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

This command toggles Beast Mode ON or OFF. The UserPromptSubmit hook handles ALL state changes automatically before Claude ever sees this command.

**Claude must never run bm-state.sh, read beastmode_state.json, or modify any state for this command.** The hook has already done it. Claude's only job is to display what the hook tells it to display.

## State

All state is in `.claude/beastmode_state.json`. Claude Code is the brain — Beast Mode provides environment only.
