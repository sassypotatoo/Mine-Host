#!/usr/bin/env python3
"""
Beast Mode v4 — PreToolUse Hook for Capability Gating

Prevents Write/Edit tool usage until capability discovery is completed.
Checks for .cap_check_done flag file in session directory.
"""
import json
import os
import sys
from pathlib import Path

# Flag file to indicate capability check completed
SESSION_DIR = Path(os.environ.get("CLAUDE_SESSION_ID", "/tmp"))
CAP_CHECK_DONE = SESSION_DIR / ".cap_check_done"

def main() -> None:
    try:
        hook_input = json.loads(sys.stdin.read())
        tool_name = hook_input.get("tool", "")
        tool_input = hook_input.get("toolInput", {})

        # Only gate Write and Edit tools
        if tool_name in ("Write", "Edit"):
            # Check if capability check has been done
            if not CAP_CHECK_DONE.exists():
                # Block the tool and return instruction
                hook_input["tool"] = "none"  # This prevents the tool from running
                hook_input["toolInput"] = {}

                # Return a message to Claude about the block
                block_message = (
                    "🚫 CAPABILITY CHECK REQUIRED: Before using Write/Edit tools, "
                    "you must first perform capability discovery. "
                    "Run: claude plugin list --json && claude mcp list "
                    "Then touch ~/.claude/.cap_check_done to proceed."
                )

                # Output the blocked hook input
                print(json.dumps(hook_input))
                return

        # For all other tools, or if capability check passed, allow through
        print(json.dumps(hook_input))
        return

    except Exception as e:
        print(f"[pre-tool-use] error: {e}", file=sys.stderr)
        # Fail-open: allow the tool to proceed
        hook_input = json.loads(sys.stdin.read())
        print(json.dumps(hook_input))
        return

if __name__ == "__main__":
    main()