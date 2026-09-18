# World Adapter Disable Verification Results

## Investigation Summary

Based on tracing the worldAdapterEnabled flag from UI to engine runtime:

### Flow Verification (Task 1)
1. **UI → ViewModel**: ServerSettingsScreen SwitchRow binds to `draft.worldAdapterEnabled` → MainViewModel saves to ServerProfile via `saveServerSettings()`
2. **ViewModel → Repository**: MainViewModel updates ServerProfile which is persisted by ServerProfileRepository 
3. **Repository → ServerManager**: ServerManager reads `profile.worldAdapterEnabled` and passes to EngineServerConfig constructor
4. **ServerManager → Engine**: EngineServerConfig.worldAdapterEnabled = profile.worldAdapterEnabled
5. **Engine Runtime**: BedrockJavaEngineBase.onPrepareWorldAndLaunchJar() checks `!serverConfig.worldAdapterEnabled` and returns serverJar immediately with log "[WorldAdapter] World adapter disabled — skipping world inspection, launching raw jar."

### Adapter Invocation Points (Task 2)
All world adapter system invocation points are gated by the early return in onPrepareWorldAndLaunchJar():
- `WorldCompatibilityCore.prepareProtectedLaunch()` (line 295) - SKIPPED
- `EngineWorldAdapterRegistry.forEngine()` calls within prepareProtectedLaunch() - SKIPPED
- `NukkitMotArtifactRepairer.prepare()` - SKIPPED
- `adapter.prepareWorkingCopy()`, `adapter.validatePreparedWorld()`, `adapter.inspectEngineArtifact()`, `adapter.checkWorldCompatibility()` - ALL SKIPPED
- `WorldLaunchOwnershipPolicy.classify()` - NOT CALLED (due to early return before line 231)

### Safety Systems Verification
- **World Generation Marker Verification**: ACTIVE in onPreflightCheck() - runs regardless of worldAdapterEnabled setting (separate safety system)
- **Incomplete Transaction Recovery**: ACTIVE in onPreflightCheck() - runs regardless
- **Engine Version Mismatch Check**: ACTIVE in onPreflightCheck() - runs regardless

## Behavior Analysis

### Current Behavior when worldAdapterEnabled=false:
- ✅ Protected world copy system: COMPLETELY BYPASSED (early return at line 229)
- ⚠️ World generation marker verification: ACTIVE (separate safety check in onPreflightCheck)
- ⚠️ Ownership classification: NOT CALLED (due to early return)
- ⚠️ All adapter methods: NOT INVOKED

### Requirement Analysis
Item 1 states: "COMPLETELY TURN OFF WORLD ADAPTER (safely; ensure disabled adapter cannot be invoked)"

UI Subtitle states: "Bypass protected world copy for direct editing"

**Conclusion**: The current behavior satisfies the requirement because:
1. The world adapter system (protected world copy mechanism) is completely bypassed
2. No world adapter code is invoked when disabled
3. The UI subtitle specifically mentions bypassing "protected world copy" - which is achieved
4. World generation marker verification remains as a separate safety system for world integrity, not part of the "world adapter" feature per se

## Decision
No code changes needed. The existing implementation already provides a complete and safe bypass of the world adapter system when worldAdapterEnabled=false.

## Evidence
- Early return in BedrockJavaEngineBase.onPrepareWorldAndLaunchJar() at lines 226-229
- All adapter invocation paths are within the skipped code block
- Verified via code inspection and call chain tracing