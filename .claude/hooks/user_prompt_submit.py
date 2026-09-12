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
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
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

def run_workflow_intake(work_request):
    """Run the workflow script with --intake to process a work request"""
    try:
        workflow_script = PROJECT_ROOT / ".claude" / "scripts" / "beastmode_workflow.sh"
        result = subprocess.run(
            [str(workflow_script), "--intake", work_request],
            capture_output=True,
            text=True,
            cwd=str(PROJECT_ROOT)
        )
        if result.returncode != 0:
            print(f"Workflow intake failed: {result.stderr}", file=sys.stderr)
        return result.stdout
    except Exception as e:
        print(f"Error running workflow intake: {e}", file=sys.stderr)
        return ""

def run_workflow_advance():
    """Run the workflow script with --advance to advance the workflow"""
    try:
        workflow_script = PROJECT_ROOT / ".claude" / "scripts" / "beastmode_workflow.sh"
        result = subprocess.run(
            [str(workflow_script), "--advance"],
            capture_output=True,
            text=True,
            cwd=str(PROJECT_ROOT)
        )
        if result.returncode != 0:
            print(f"Workflow advance failed: {result.stderr}", file=sys.stderr)
        return result.stdout
    except Exception as e:
        print(f"Error running workflow advance: {e}", file=sys.stderr)
        return ""

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

        # Check for the activation-with-task form: /minehost-autonomous "work request" or /minehost-autonomous task
        # First try quoted form
        task_match = re.match(r'^/minehost-autonomous\s+"([^"]*)"\s*$', stripped_prompt)
        if task_match:
            task = task_match.group(1).strip()
            if not task:
                task = "(none)"
        else:
            # Try unquoted form: everything after the command
            task_match = re.match(r'^/minehost-autonomous\s+(.*)$', stripped_prompt)
            if task_match:
                task = task_match.group(1).strip()
                if not task:
                    task = "(none)"
            else:
                task_match = None

        if task_match:
            new_state = state.copy()
            new_state["beastModeEnabled"] = True
            new_state["currentTask"] = task
            new_state["workflowStatus"] = "ACTIVE"
            write_state(new_state)

            # Process the work request through the workflow intake
            intake_output = run_workflow_intake(task)

            hook_input["prompt"] = f"[Beast Mode ON] Activated Beast Mode with task: {task}\n{intake_output}"
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
        sys.exit(0)

if __name__ == "__main__":
    main()