# MineHost Beast Mode Skill

## Overview

Beast Mode v3 is a workflow orchestration layer that manages state, objectives, and verification for autonomous tasks in the MineHost repository. It does not make implementation decisions but rather orchestrates the workflow through state management and message classification.

## When to Use

Use for any autonomous implementation task in the MineHost repo where you want to leverage the Beast Mode v3 workflow orchestration system. This skill provides the `/minehost-autonomous` command and the UserPromptSubmit hook to enable Beast Mode.

## Capability Selection Guidance

This section provides guidance to Claude Code on when to employ various capabilities (skills, MCP servers, plugins) during Beast Mode workflow execution. Beast Mode never invokes these capabilities directly - it only manages workflow state and provides `GUIDANCE_NEEDED` signals. Claude Code retains full autonomy to decide which capabilities to use based on this guidance and the specific requirements of each objective.

### Rule

- Beast Mode only provides guidance. It manages state, objectives, and verification tracking.
- Claude Code decides whether to use any capability, and which one.
- No automatic plugin/MCP/skill/sub-agent invocation ever occurs.

### Available Capabilities in This Environment

Only these capabilities are actually available in this environment. Do not reference or invoke any capability not on this list:

`superpowers`, `code-review`, `context7`, `supabase`, `playwright`, `chrome-devtools-mcp`, `security-guidance`, `claude-security`, `remember`, `code-simplifier`, `frontend-design`, `gstack`, `minehost-beastmode`

### Capability Categories

#### 1. Large Codebase Analysis & Exploration

**When to Use**: Understanding complex codebases, tracing execution paths, mapping architecture layers, finding specific implementations, understanding dependencies.

**Capabilities**:
- `superpowers`: process skills (brainstorming, systematic debugging, dispatching-parallel-agents, subagent-driven development)
- `gstack`: router for the gstack skill suite (planning, review, QA, shipping, debugging, docs, security, design)
**Avoid When**: Simple file edits, trivial changes, or when you already know exactly what needs to be changed.

#### 2. Android Development & Build Systems

**When to Use**: Gradle/Kotlin/Java changes, Android app modifications, build system configuration, resource management.

**Capabilities**:
- `context7` (MCP): Up-to-date documentation for Android SDK, Gradle, Kotlin, Jetpack libraries
- `code-simplifier`: Simplifying and refining Android/Kotlin code for clarity
**Avoid When**: Non-Android changes, documentation updates, or configuration changes unrelated to build systems.

#### 3. Security Review & Analysis

**When to Use**: Changes involving downloads, binaries, command execution, JNI/native loading, networking, authentication, tunneling, permissions, file extraction, IPC, crypto, Supabase/RLS, or user-controlled input.

**Capabilities**:
- `claude-security`: Automated security scanning (scan-changes, scan-codebase, suggest-patches)
- `security-guidance`: Expert guidance on secure implementation practices
- `code-review`: Code review including security-focused review
**Avoid When**: Pure UI changes, documentation updates, or changes with no security implications.

#### 4. Supabase Development

**When to Use**: Database schema changes, Supabase function modifications, RLS policies, Supabase client usage, authentication flows.

**Capabilities**:
- `supabase` (MCP): Direct interaction with Supabase for schema migrations, function execution, and database operations
- `context7` (MCP): Supabase-specific documentation and best practices
**Avoid When**: Non-database changes, frontend-only updates, or changes unrelated to Supabase integration.

#### 5. UI/Browser Testing & Automation

**When to Use**: Frontend changes, user interface modifications, user interaction flows, visual regression testing, cross-browser compatibility.

**Capabilities**:
- `playwright`: End-to-end testing of web applications and user interfaces
- `chrome-devtools-mcp`: Browser automation and debugging capabilities
- `frontend-design`: UI/UX design guidance and component library recommendations
**Avoid When**: Backend-only changes, API modifications, or non-visual changes.

#### 6. Simple Tasks & Local Edits

**When to Use**: Trivial file modifications, documentation updates, configuration changes, simple bug fixes, refactoring with clear scope.

**Capabilities**:
- Basic text editing (Read/Edit/Write tools)
- `code-simplifier`: For simple code clarity improvements
- `remember`: For persisting context across conversations when needed
- `minehost-beastmode`: This skill itself, as loadable workflow knowledge

### Connection with Beast Mode Workflow

During Beast Mode execution, you will see guidance signals like:
- `GUIDANCE_NEEDED: implement the following objective:`
- `GUIDANCE_PROVIDED: Objective requires Claude Code implementation`

When you see `GUIDANCE_NEEDED`, refer to this guidance section to determine which capabilities would be most effective for implementing the objective. Consider:
1. The nature of the change (Android, security, database, UI, etc.)
2. The complexity and scope of the objective
3. Whether exploration or analysis is needed first
4. Any security or compliance implications

#### What Beast Mode Does NOT Do

- Beast Mode does NOT automatically invoke skills, MCP servers, plugins, or sub-agents
- Beast Mode does NOT force the usage of any specific capability
- Beast Mode does NOT make implementation decisions
- Beast Mode only manages workflow state, objectives, and verification coordination

#### What Claude Code Should Do

- Claude Code should read and understand the `GUIDANCE_NEEDED`/objective description
- Claude Code should consult this Capability Selection Guidance section to determine appropriate capabilities
- Claude Code should freely choose which skills, MCP servers, plugins, or sub-agents (if any) to employ
- Claude Code should provide evidence-based reasoning for capability choices in the workflow reports
- Claude Code should never claim capability usage unless actual invocation occurred

## Core Loop

1. Read the project context (if any) from the user's message or existing state.
2. Classify the user intent into QUESTION, WORK_REQUEST, or CONTROL_REQUEST.
3. If Beast Mode is ON and the intent is a WORK_REQUEST, update the current task and enhance the prompt with Beast Mode context.
4. If the intent is a CONTROL_REQUEST (specifically `/minehost-autonomous`), toggle the Beast Mode state.
5. Otherwise, allow normal processing.

## State Management

Beast Mode state is persisted in `.claude/beastmode_state.json` and includes:
   - beastModeEnabled: boolean
   - currentTask: string
   - objectives: array
   - currentObjectiveId: string|null
   - gitBranch: string
   - lastCiRunUrl: string
   - knownIssues: array
   - blockers: array
   - timestamp: string
   - workflowStatus: string

## Commands

- `/minehost-autonomous`: Toggles Beast Mode ON/OFF.
- `/minehost-autonomous "work request"`: Activates Beast Mode ON and sets the current task.

## Integration

This skill works through a UserPromptSubmit hook that intercepts all user messages when Beast Mode is ON and classifies them to provide contextual prompting.

## Notes

- Beast Mode never makes implementation decisions; it only manages state and workflow.
- Beast Mode does not invoke skills, MCP servers, plugins, or sub-agents.

## Verification Integrity

Beast Mode enforces a hard verification-integrity invariant: never claim that an action occurred unless it was actually executed and concrete evidence exists.

This applies to:
  - implementation
  - builds
  - tests
  - commits
  - pushes
  - GitHub Actions
  - CI results
  - runtime verification
  - Skills
  - plugins
  - MCPs
  - sub-agents
  - tool usage
  - server/runtime behavior

Never infer success from:
  - code merely existing
  - a command being constructed
  - a command being described
  - expected behavior
  - Claude's reasoning
  - a previous successful run
  - an unverified state file
  - an assumed CI result

Never fabricate or simulate:
  - test results
  - CI results
  - commit SHAs
  - push success
  - runtime verification
  - tool/capability usage
  - completion status

If an action was not actually performed, report:
  UNVERIFIED

If evidence is missing, stale, contradictory, or cannot be tied to the relevant action/commit, the state MUST NOT be marked "VERIFIED".

For CI specifically:
  - verify the result belongs to the exact commit SHA being evaluated
  - do not use an unrelated successful workflow run as evidence
  - if CI status cannot be confirmed, remain UNVERIFIED

For Skills/plugins/MCPs/sub-agents:
  - only report a capability as USED if Claude Code actually invoked it
  - never infer usage because a capability was available or appropriate