---
description: Compatibility alias for /minehost-autonomous. Kept so older invocations and any external links do not break. New code should use /minehost-autonomous.
argument-hint: <task description>
---

# /minehost-auto — compatibility alias

Task: $ARGUMENTS

This is a compatibility shim. The canonical entry point is
`/minehost-autonomous` (MineHost Autonomous v2.1). Invoke that instead in
new work; this command forwards identically so nothing in flight breaks.

Execute under the v2.1 loop — see
`.claude/commands/minehost-autonomous.md` for the full phase spec and
`.claude/skills/minehost-autonomous/SKILL.md` for the governing invariants
(retry limits, protected systems, mandatory report format).
