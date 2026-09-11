#!/usr/bin/env python3
"""
Beast Mode v3 UserPromptSubmit Hook
Intercepts user messages to enable automatic workflow processing when Beast Mode is ON
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# Configuration
PROJECT_ROOT = Path("/data/data/com.termux/files/home/mine-host-import.lY0iUy/Java-integration-3-main")
STATE_FILE = PROJECT_ROOT / ".claude" / "beastmode_state.json"

def read_state():
    """Read Beast Mode state from JSON file"""
    try:
        if STATE_FILE.exists():
            return json.loads(STATE_FILE.read_text())
    except Exception as e:
        print(f"Error reading state: {e}", file=sys.stderr)

    # Return default state if file doesn't exist or is corrupted
    return {
        "beastModeEnabled": False,
        "currentTask": "",
        "objectives": [],
        "currentObjectiveId": None,
        "gitBranch": "",
        "lastCiRunUrl": "",
        "knownIssues": [],
        "blockers": [],
        "timestamp": "",
        "workflowStatus": "IDLE"
    }

def write_state(state):
    """Write Beast Mode state to JSON file"""
    try:
        state["timestamp"] = subprocess.check_output(
            ["date", "-u", "+%Y-%m-%dT%H:%M:%SZ"],
            text=True
        ).strip()
        STATE_FILE.write_text(json.dumps(state, indent=2))
    except Exception as e:
        print(f"Error writing state: {e}", file=sys.stderr)

def main():
    """Main hook entry point"""
    try:
        # Read hook input from stdin (Claude Code hook format)
        hook_input = json.loads(sys.stdin.read())
        prompt = hook_input.get("prompt", "")

        # Read current Beast Mode state
        state = read_state()

        # Check if this is the toggle command to update state (works in both ON/OFF states)
        stripped_prompt = prompt.strip()
        if stripped_prompt in ["/minehost-autonomous", "/minehost-autonomous "]:
            # Toggle the state
            new_state = state.copy()
            new_state["beastModeEnabled"] = not new_state["beastModeEnabled"]

            # If turning OFF, clear current task
            if not new_state["beastModeEnabled"]:
                new_state["currentTask"] = ""
                new_state["currentObjectiveId"] = None
                new_state["workflowStatus"] = "IDLE"

            write_state(new_state)

            # Return status message to user
            status = "ON" if new_state["beastModeEnabled"] else "OFF"
            hook_input["prompt"] = f"[Beast Mode {status}] Toggled Beast Mode {status}. Use /minehost-autonomous \"work request\" to activate with a task."
            print(json.dumps(hook_input))
            return

        # Check for the activation-with-task form: /minehost-autonomous "work request"
        task_match = re.match(r'^/minehost-autonomous\s+"([^"]*)"\s*$', stripped_prompt)
        if task_match:
            task = task_match.group(1).strip()
            if not task:
                task = "(none)"
            new_state = state.copy()
            new_state["beastModeEnabled"] = True
            new_state["currentTask"] = task
            new_state["workflowStatus"] = "ACTIVE"
            write_state(new_state)

            hook_input["prompt"] = f"[Beast Mode ON] Activated Beast Mode with task: {task}"
            print(json.dumps(hook_input))
            return

        # If Beast Mode is OFF, allow normal processing
        if not state.get("beastModeEnabled", False):
            # Normal processing - exit with code 0 to allow normal flow
            sys.exit(0)

        # Beast Mode is ON - inject context for every request
        current_task = state.get("currentTask", "")
        if not current_task:
            current_task_display = "(none)"
        else:
            current_task_display = current_task

        context_block = f"""[BEAST MODE ACTIVE]

Beast Mode is persistently enabled.

Current Task:
{current_task_display}

"""
        # Prepend the context block to the original prompt
        hook_input["prompt"] = context_block + prompt
        print(json.dumps(hook_input))
        return

    except Exception as e:
        # On error, fail open - allow normal processing
        print(f"Beast Mode hook error: {e}", file=sys.stderr)
        try:
            hook_input = json.loads(sys.stdin.read())
            print(json.dumps(hook_input))
        except:
            # If we can't even read input, exit normally
            sys.exit(0)

if __name__ == "__main__":
    main()