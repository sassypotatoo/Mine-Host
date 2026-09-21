#!/usr/bin/env python3
"""
Beast Mode v4 — Stop Hook for Capability Gating Reset

Deletes the .cap_check_done flag to reset capability check requirement.
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

        # Delete the capability check flag if it exists
        if CAP_CHECK_DONE.exists():
            try:
                CAP_CHECK_DONE.unlink()
                print(f"[stop-hook] Capability check flag removed: {CAP_CHECK_DONE}", file=sys.stderr)
            except Exception as e:
                print(f"[stop-hook] error removing flag: {e}", file=sys.stderr)

        # Allow the Stop tool to proceed normally
        print(json.dumps(hook_input))
        return

    except Exception as e:
        print(f"[stop-hook] error: {e}", file=sys.stderr)
        # Fail-open: allow the Stop tool to proceed
        hook_input = json.loads(sys.stdin.read())
        print(json.dumps(hook_input))
        return

if __name__ == "__main__":
    main()