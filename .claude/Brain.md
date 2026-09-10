# Beast Mode v3 Architecture Documentation

## Purpose
Beast Mode v3 is a workflow orchestration layer that manages autonomous work cycles within the MineHost repository. It serves as a state machine that tracks work requests, breaks them into objectives, manages retry logic, and coordinates verification through real Git and CI systems. Crucially, Beast Mode is NOT an autonomous AI framework - it does not make implementation decisions or execute code. Instead, it provides structured workflow management while leaving all reasoning and implementation to Claude Code.

## Components

### Core Files
1. **beastmode_workflow.sh** - Main workflow execution script
2. **beastmode_utils.sh** - Utility functions for state management, git operations, and CI integration
3. **beastmode_state.json** - Persistent state file tracking objectives, attempts, commit SHAs, and CI status
4. **minehost-auto.sh** - Toggle script to enable/disable Beast Mode
5. **minehost-auto.md** - Documentation for the toggle mechanism

### State Management
- **beastmode_state.json**: JSON file stored in `.claude/` directory
- Tracks: beastModeEnabled, currentTask, objectives array, currentObjectiveId, gitBranch, lastCiRunUrl, knownIssues, blockers, timestamp
- Each objective contains: id, description, status (PENDING/IN_PROGRESS/VERIFIED/FAILED/FAILED_EXCEEDED), attempts, lastCommit, ciStatus, verificationNotes
- State is updated at each phase transition via python3 JSON manipulation

### Objective Lifecycle
1. **Creation**: Work request decomposed into objectives via `decompose_work_request()`
2. **Deduplication**: Checks if objectives already exist before adding new ones
3. **Processing**: Objectives processed one at a time via `get_next_pending_objective()`
4. **Attempt Tracking**: Each attempt increments the objective's attempt counter
5. **Retry Limits**: Max 5 attempts per objective (`MAX_ATTEMPTS_PER_OBJECTIVE=5`)
6. **Total Iteration Limit**: Max 15 total workflow iterations (`MAX_TOTAL_ITERATIONS=15`)
7. **Completion**: Objective marked VERIFIED when CI passes
8. **Failure Handling**: On CI failure, state updated with failure info, loop continues for retry
9. **Escalation**: Objectives exceeding retry limits marked as FAILED_EXCEEDED
10. **Workflow Completion**: All objectives VERIFIED → work request completed

## Responsibilities

### Beast Mode Responsibilities
- **Workflow Orchestration**: Manages the turn-based execution loop
- **State Persistence**: Reads/writes beastmode_state.json
- **Objective Management**: Creates, tracks, deduplicates, and updates objectives
- **Retry Logic**: Enforces per-objective and total iteration limits
- **Verification Coordination**: Calls real Git/CI tools and processes results
- **Reporting**: Outputs structured terminal messages for workflow progress
- **Error Handling**: Detects and reports failures in Git/CI operations
- **Escalation**: Marks objectives as FAILED_EXCEEDED when limits exceeded

### Beast Mode Does NOT Do
- Make implementation decisions
- Write or modify code
- Select skills, MCP servers, plugins, or sub-agents
- Execute Agent() calls or simulate Claude Code behavior
- Decide technical approaches or implementations
- Perform actual verification (only orchestrates verification tools)

### Claude Code Responsibilities
- **Reasoning Engine**: Interprets Beast Mode guidance and decides implementation approach
- **Implementation Engine**: Writes actual code changes
- **Technical Decision Maker**: Chooses when to use skills, MCP servers, plugins, sub-agents
- **Tool Selector**: Decides which capabilities to employ for each objective
- **Code Reviewer**: Self-reviews implementation before committing
- **Problem Solver**: Diagnoses and fixes CI failures when guided by Beast Mode

### Skill/MCP/Plugin/Agent Separation
- **Beast Mode**: Zero direct invocation of skills/MCP/plugins/agents
- **Guidance Only**: Provides `GUIDANCE_NEEDED: implement the following objective:` or `GUIDANCE_PROVIDED: Objective requires Claude Code implementation`
- **Claude Code Autonomy**: Free to use any available capability:
  - Skills: superpowers:brainstorming, feature-dev:*, plugin-dev:*, etc.
  - MCP Servers: context7, supabase, github, etc.
  - Plugins: code-reviewer, code-architect, frontend-design, etc.
  - Sub-agents: Explore, code-explorer, code-architect, code-reviewer
- **Decision Boundary**: Beast Mode says "what" needs to be done, Claude Code decides "how"

## Git/CI Verification Flow

### Commit Process (`commit_and_push()`)
1. Change to PROJECT_ROOT directory
2. Execute `git add -u` (update tracked files) with error checking
3. Execute `git add.` (add new files) - non-fatal if fails
4. Execute `git commit -m "$commit_message"` with error checking
5. Retrieve commit SHA via `git rev-parse HEAD` with validation
6. Execute `./tools/push-gated.sh` with error checking
7. Return actual commit SHA on success

### CI Verification Process (`verify_via_ci()`)
1. Execute `./tools/ci-watch.sh --sha "$commit_sha" --update-state` with error checking
2. This command triggers MineHost's CI monitoring system
3. ci-watch.sh internally calls `./tools/ci-state-update.sh` to update beastmode_state.json
4. Beast Mode reads updated state to extract ciStatus for the specific commit
5. Returns 0 for PASS, 1 for FAIL based on actual CI result

### CI Failure Handling (`handle_ci_failure()`)
1. Retrieves actual CI logs via `./tools/ci-watch.sh --sha "$commit_sha" --get-logs`
2. Creates temporary analysis file with:
   - Commit SHA
   - Objective description
   - Attempt number
   - CI logs
   - Analysis request for Claude Code
3. Signals Claude Code via `CLAUDE_CODE_ANALYZE_FAILURE: $obj_id:$attempt_num`
4. Returns fix analysis suggestion for Claude Code to implement
5. Updates state with failure information (ciStatus=FAIL, verificationNotes)

## Future Modification Rules

### Allowed Modifications
- Enhancing error handling in Git/CI operations
- Improving state management robustness
- Adding better logging or diagnostics
- Refining objective decomposition logic
- Updating retry limit constants (with caution)
- Improving guidance messaging clarity

### Prohibited Modifications
- Adding simulated/fake Agent() calls or Claude Code simulation
- Making Beast Mode invoke skills/MCP/plugins/agents directly
- Adding implementation decision logic to Beast Mode
- Modifying MineHost app architecture, Android code, or runtime systems
- Bypassing real Git/CI verification with mocks or placeholders
- Changing the fundamental turn-based workflow architecture
- Removing the separation between workflow orchestration and implementation

### Modification Principles
1. **Preserve the Illusion**: Beast Mode must remain a workflow layer, not an AI framework
2. **Maintain Separation of Concerns**: Workflow management vs implementation must stay distinct
3. **Keep it Real**: All Git/CI operations must use actual tools, never simulations
4. **Respect MineHost Non-negotiables**: Never modify protected systems without documented evidence
5. **Error Transparency**: Failures must be reported honestly, never hidden or simulated

## Integration with MineHost Systems

### Tool Dependencies
- **Git**: Standard git operations for version control
- **push-gated.sh**: MineHost's gated push mechanism (must succeed for workflow continuation)
- **ci-watch.sh**: MineHost's CI monitoring and state update system
- **ci-state-update.sh**: Called by ci-watch.sh to update beastmode_state.json
- **gh**: GitHub CLI for retrieving CI logs during failure handling

### Assumptions
- Workflow executes from MineHost repository root
- Standard git remote configured (origin pointing to GitHub)
- MineHost CI system properly configured and accessible
- Required tools (git, gh, python3) available in execution environment
- beastmode_utils.sh provides required helper functions

## Security and Safety Considerations

### Protected Systems Compliance
- Beast Mode never attempts to modify MineHost's protected systems
- All file operations confined to .claude/ directory and workspace
- No access to or modification of: native JVM launcher, runtime extraction/validation, CMake config, engine launch commands, engine download system

### Error Handling
- All external command executions checked for failure
- Clear error messages printed to stderr on failure
- Workflow continues or escalates appropriately based on error type
- No silent failures - all issues reported via terminal output

### Data Integrity
- State file updates use atomic-ish pattern (read-modify-write via python3)
- Timestamp updated on every state change for auditability
- Objective IDs generated via timestamp nanoseconds for uniqueness
- Commit SHAs verified as non-empty before use