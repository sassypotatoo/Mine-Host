# MineHost Beast Mode Skill

Beast Mode v3 workflow orchestration system for MineHost autonomous development.

## Installation

This skill is installed automatically as part of the MineHost autonomous workflow setup.

## Usage

Toggle Beast Mode ON/OFF:
```
/minehost-autonomous
```

Activate Beast Mode with a work request:
```
/minehost-autonomous "Fix typo in README.md"
```

## How It Works

When Beast Mode is ON:
- Intercepts all user messages through a UserPromptSubmit hook
- Classifies messages as QUESTION, WORK_REQUEST, or CONTROL_REQUEST
- Enhances prompts with Beast Mode context for better assistance
- Manages workflow state in `.claude/beastmode_state.json`

Beast Mode never makes implementation decisions; it only manages state and workflow orchestration.

## State File

The Beast Mode state is stored in `.claude/beastmode_state.json` with the following structure:
- `beastModeEnabled`: Boolean indicating if Beast Mode is active
- `currentTask`: Current work request/task description
- `objectives`: List of objectives for the current task
- `currentObjectiveId`: ID of the current objective being worked on
- `gitBranch`: Current git branch
- `lastCiRunUrl`: URL of the last CI run
- `knownIssues`: List of known issues
- `blockers`: List of blockers
- `timestamp`: Last update timestamp
- `workflowStatus`: Current workflow status (IDLE, PLANNING, IMPLEMENTING, etc.)

## Requirements

- Python 3 (for the UserPromptSubmit hook)

## License

MIT