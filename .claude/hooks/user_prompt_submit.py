#!/usr/bin/env python3
"""
Beast Mode v3 UserPromptSubmit Hook
Intercepts user messages to enable automatic workflow processing when Beast Mode is ON
"""
import json
import os
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

def classify_intent(message):
    """
    Classify user intent into QUESTION, WORK_REQUEST, or CONTROL_REQUEST
    Based on existing logic from beastmode_workflow.sh
    """
    message = message.strip()
    if not message:
        return "QUESTION"

    # Control request patterns
    if message.startswith(("/pause", "/resume", "/cancel", "/stop", "/toggle", "/task-change")):
        return "CONTROL_REQUEST"

    # Work request patterns - action-oriented language
    import re
    work_patterns = [
        r"^/(fix|implement|add|create|remove|delete|update|change|modify)",
        r"(fix|implement|add|create|remove|delete|update|change|modify)"
    ]

    for pattern in work_patterns:
        if re.search(pattern, message, re.IGNORECASE):
            return "WORK_REQUEST"

    # Default to question/discussion
    return "QUESTION"

def main():
    """Main hook entry point"""
    try:
        # Read hook input from stdin (Claude Code hook format)
        hook_input = json.loads(sys.stdin.read())
        prompt = hook_input.get("prompt", "")

        # Read current Beast Mode state
        state = read_state()

        # If Beast Mode is OFF, allow normal processing
        if not state.get("beastModeEnabled", False):
            # Normal processing - exit with code 0 to allow normal flow
            sys.exit(0)

        # Beast Mode is ON - process based on classification
        intent = classify_intent(prompt)

        if intent == "CONTROL_REQUEST":
            # Handle /minehost-autonomous toggle command
            # Check if this is actually the toggle command
            if prompt.strip() in ["/minehost-autonomous", "/minehost-autonomous "]:
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

            # Other control commands (pause, resume, etc.) - let them through for now
            hook_input["prompt"] = f"[Beast Mode ACTIVE] {prompt}"
            print(json.dumps(hook_input))
            return

        elif intent == "WORK_REQUEST" and state.get("currentTask"):
            # Work request while Beast Mode is active and has a current task
            # Check if this is a new task or continuation
            current_task = state.get("currentTask", "")
            new_task = prompt.strip()

            # If this is a substantially different task, update current task
            if new_task and new_task.lower() != current_task.lower():
                # New work request - update state
                new_state = state.copy()
                new_state["currentTask"] = new_task
                # Clear existing objectives for new task (they'll be recreated by workflow)
                new_state["objectives"] = []
                new_state["currentObjectiveId"] = None
                new_state["workflowStatus"] = "IDLE"
                write_state(new_state)

            # Enhance prompt with Beast Mode context
            task_preview = state.get("currentTask", "")[:50]
            hook_input["prompt"] = f"[Beast Mode ACTIVE - Task: {task_preview}...] {prompt}"
            print(json.dumps(hook_input))
            return

        else:
            # QUESTION or no current task - enhance with context if available
            if state.get("currentTask"):
                task_preview = state.get("currentTask", "")[:50]
                hook_input["prompt"] = f"[Beast Mode ACTIVE - Task: {task_preview}...] {prompt}"
            else:
                hook_input["prompt"] = f"[Beast Mode ACTIVE - No task set] {prompt}"
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