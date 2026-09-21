#!/usr/bin/env python3
"""
Beast Mode v4 — UserPromptSubmit Hook

Responsibilities (lightweight — this hook is NOT the workflow brain):
  1. Detect /minehost-beastmode toggle and update state.
  2. When Beast Mode is ON, inject the full current state block into
     every prompt so Claude Code can make informed decisions.
  3. Nothing else. No --advance. No --intake. No classification.
     No workflow driving. Claude Code is the brain.
"""
import json
import os
import re
from datetime import datetime, timezone
import sys
import tempfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
STATE_FILE = PROJECT_ROOT / ".claude" / "beastmode_state.json"

DEFAULT_STATE = {
    "schemaVersion": 1,
    "beastModeEnabled": False,
    "currentTask": "",
    "taskBranch": "",
    "objectives": [],
    "currentObjectiveId": None,
    "phase": "IDLE",
    "workflowStatus": "IDLE",
    "gitBranch": "",
    "lastCiRunUrl": "",
    "lastCommitSha": "",
    "knownIssues": [],
    "blockers": [],
    "timestamp": "",
}


def read_state() -> dict:
    """Read Beast Mode state. Returns default if missing or corrupt."""
    try:
        if STATE_FILE.exists():
            text = STATE_FILE.read_text().strip()
            if text:
                return json.loads(text)
    except Exception as e:
        print(f"[bm-hook] state read error: {e}", file=sys.stderr)
    return DEFAULT_STATE.copy()


def write_state(state: dict) -> None:
    """Atomically write state via unique temp file + replace."""
    try:
        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        state["timestamp"] = ts
        fd, tmp_path = tempfile.mkstemp(
            dir=STATE_FILE.parent, prefix=STATE_FILE.name + ".", suffix=".tmp"
        )
        try:
            with os.fdopen(fd, "w") as f:
                f.write(json.dumps(state, indent=2) + "\n")
            Path(tmp_path).replace(STATE_FILE)
        except Exception:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise
    except Exception as e:
        print(f"[bm-hook] state write error: {e}", file=sys.stderr)


def build_context_block(state: dict) -> str:
    """Build the Beast Mode context block injected into every prompt."""
    task = state.get("currentTask") or "(none set)"
    phase = state.get("phase", "IDLE")
    status = state.get("workflowStatus", "IDLE")
    branch = state.get("taskBranch") or state.get("gitBranch") or "(none)"
    last_sha = state.get("lastCommitSha") or "(none)"
    last_ci = state.get("lastCiRunUrl") or "(none)"

    # Current objective summary
    obj_id = state.get("currentObjectiveId")
    obj_summary = "(none)"
    if obj_id:
        for obj in state.get("objectives", []):
            if obj.get("id") == obj_id:
                desc = obj.get("description", "?")
                obj_status = obj.get("status", "?")
                attempts = obj.get("attempts", 0)
                ci_status = obj.get("ciStatus") or "none"
                obj_summary = (
                    f"{obj_id} — {desc}\n"
                    f"  Status: {obj_status} | Attempts: {attempts}/5 | CI: {ci_status}"
                )
                break

    # All objectives summary
    objectives = state.get("objectives", [])
    if objectives:
        obj_lines = []
        for o in objectives:
            marker = "→" if o.get("id") == obj_id else " "
            obj_lines.append(
                f"  {marker} [{o.get('status','?'):12s}] {o.get('id','?')} — {o.get('description','?')}"
            )
        obj_list = "\n".join(obj_lines)
    else:
        obj_list = "  (no objectives yet)"

    lines = [
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
        "🔥 BEAST MODE CONTEXT (read this, then act on the request above) — supporting info, not the primary thing to respond to.",
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
        f"Task    : {task}",
        f"Phase   : {phase}  |  Status: {status}",
        f"Branch  : {branch}",
        f"Last SHA: {last_sha}",
        f"Last CI : {last_ci}",
        "",
        "Current Objective:",
        f"  {obj_summary}",
        "",
        "All Objectives:",
        f"{obj_list}",
        "",
        "State commands (call via Bash tool):",
        "  ./tools/bm-state.sh status",
        "  ./tools/bm-state.sh task-start \"<branch>\" \"<task>\"",
        "  ./tools/bm-state.sh objective-add \"<description>\"",
        "  ./tools/bm-state.sh objective-start <id>",
        "  ./tools/bm-state.sh objective-complete <id> \"<sha>\" \"<ci-url>\"",
        "  ./tools/bm-state.sh objective-fail <id> \"<reason>\"",
        "  ./tools/bm-state.sh ci-update <id> <PASS|FAIL> \"<sha>\" \"<url>\"",
        "  ./tools/bm-state.sh task-done",
        "  ./tools/bm-state.sh task-pause",
        "  ./tools/bm-state.sh task-resume",
        "  ./tools/bm-state.sh task-cancel",
        "",
        "STRICT MODE (skills/plugins/MCP): If a suitable Skill/Plugin/MCP exists for the action you're about to take, invoke it first.",
        "Prefer superpowers:subagent-driven-development for implementations and superpowers:systematic-debugging for debugging.",
        "Only fall back to direct Bash/Read/Edit/Write when no Skill/MCP applies; then explain why briefly.",
        "About MCP: use only MCP that already exists in this session; otherwise do not attempt to add one mid-task.",
        "Follow v4 workflow docs: .claude/CLAUDE_V4_WORKFLOW.md and .claude/BM_STATE_CHEAT_SHEET.md.",
        "You are the brain. Classify this message, decide what to do, act.",
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    try:
        hook_input = json.loads(sys.stdin.read())
        prompt = hook_input.get("prompt", "")
        stripped = prompt.strip()

        state = read_state()

        # ── Deterministic: Beast Mode persistent toggle ─────────────────────
        # `/minehost-beastmode` is the master ON/OFF switch.
        # `/minehost-autonomous` is a legacy alias for the same persistent toggle.
        # No task-start semantics are allowed here; normal user messages are the tasks.
        toggle_cmds = {
            "/minehost-beastmode",
            "/minehost-autonomous",
            "/minehost-beastmode:minehost-beastmode",
            "/minehost-autonomous:minehost-autonomous",
        }
        toggle_match = stripped in toggle_cmds
        if toggle_match:
            was_on = state.get("beastModeEnabled", False)

            new_state = state.copy()
            if was_on:
                # Turn OFF
                new_state["beastModeEnabled"] = False
                new_state["currentTask"] = ""
                new_state["taskBranch"] = ""
                new_state["currentObjectiveId"] = None
                new_state["workflowStatus"] = "IDLE"
                new_state["phase"] = "IDLE"
                write_state(new_state)

                hook_input["prompt"] = "🛑 BEAST MODE: OFF"
                print(json.dumps(hook_input))
                return

            # Turn ON
            new_state["beastModeEnabled"] = True
            new_state["currentTask"] = ""
            new_state["taskBranch"] = ""
            new_state["currentObjectiveId"] = None
            new_state["workflowStatus"] = "IDLE"
            new_state["phase"] = "IDLE"
            write_state(new_state)

            hook_input["prompt"] = (
                "🔥 BEAST MODE: Effort: MAX\n\n"
                "Beast Mode now active. Every subsequent message include full project state make informed decisions.\n\n"
                "What now:\n"
                "1. task set state, run:./tools/bm-state.sh status\n"
                "2. task set, wait user's next message task.\n"
                "3. NOT say 'Beast Mode ON' again. Beast Mode environment. You brain. Act whatever comes next.\n\n"
                "Ready. Waiting first task."
            )
            print(json.dumps(hook_input))
            return

        # ── Beast Mode OFF — pass through untouched ─────────────────────────
        if not state.get("beastModeEnabled", False):
            sys.exit(0)

        # ── Beast Mode ON — inject full context, Claude decides everything ──
        # Task first so Claude responds to the user's request, not the injected header.
        # Move capability instruction immediately after user's task
        capability_block = (
            "⚡ CAPABILITY CHECK REQUIRED BEFORE CODING: If a suitable Skill/Plugin/MCP exists for the action you're about to take, invoke it first. "
            "Prefer superpowers:subagent-driven-development for implementations and superpowers:systematic-debugging for debugging. "
            "Only fall back to direct Bash/Read/Edit/Write when no Skill/MCP applies; then explain why briefly. "
            "About MCP: use only MCP that already exists in this session; otherwise do not attempt to add one mid-task. "
            "Follow v4 workflow docs: .claude/CLAUDE_V4_WORKFLOW.md and .claude/BM_STATE_CHEAT_SHEET.md. "
            "You are the brain. Classify this message, decide what to do, act."
        )

        if state.get("workflowStatus") == "COMPLETE":
            # Inject STOP block instead of context when workflow is complete
            stop_block = (
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                "🛑 BEAST MODE STOP: Workflow complete. No further action required.\n"
                "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
            )
            hook_input["prompt"] = (
                prompt
                + "\n\n"
                + capability_block
                + "\n\n"
                + stop_block
            )
        else:
            context = build_context_block(state)
            hook_input["prompt"] = (
                prompt
                + "\n\n"
                + capability_block
                + "\n\n"
                + "[BEAST MODE CONTEXT — read and then act on the request above]"
                + "\n\n"
                + context
            )
        print(json.dumps(hook_input))
        return


    except Exception as e:
        print(f"[bm-hook] fatal error: {e}", file=sys.stderr)
        sys.exit(0)  # fail-open: never block Claude Code


if __name__ == "__main__":
    main()