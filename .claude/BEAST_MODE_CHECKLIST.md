# Beast Mode Checklist — Two Hard Gates

Claude Code must pass BOTH gates at the stated points. No exceptions, no shortcuts.

---

## Gate 1: PRE-IMPLEMENTATION — Capability Consideration

**When:** Before writing ANY code for a Beast Mode objective.

**Mandatory check:**
```
"Which capabilities (if any) would materially improve this task?"
```

Available options: code-review, code-simplifier, claude-security, context7, sub-agent, superpowers, none.

**Rules:**
- If a capability applies → use it
- If none apply → state "Capability check: none needed" explicitly
- If you skip this check → go back and answer it before proceeding
- Do NOT default to basic file tools without first asking "would something else help?"

---

## Gate 2: POST-PUSH — CI Verification

**When:** After every push via `./tools/push-gated.sh`, before marking any objective complete.

**Mandatory check:**
```
./tools/ci-watch.sh <commit-sha>
```

**Rules:**
- MUST run `ci-watch.sh` — never skip
- Exit 0 → PASS, mark objective complete
- Exit 1 → FAIL, go to Step 5 (diagnose)
- Exit 2 → inconclusive, report "CI result unclear"
- If you skip this step → report UNVERIFIED (never fake CI results)
- Do NOT claim "should work" without running it

---

## Enforcement

These gates are non-negotiable and cannot be waived:
- They are documented in `CLAUDE.md` under Non-negotiables
- They are embedded in `.claude/CLAUDE_V4_WORKFLOW.md` at Steps 4b and 4d
- Violating either gate requires returning to the check point before proceeding

**Why these two gates matter:**
1. Capability selection: I defaulted to basic tools when skills/MCPs/sub-agents would have caught issues or improved quality
2. CI monitoring: I pushed code without verifying CI results, risking silent failures going undetected
