# MineHost World Adapter — Loop 4 Status

## Trigger

A real Nukkit-MOT Build 1361 device run reached readiness, but loading a player-visible chunk emitted:

`Can not find legacyId! No runtime2legacy mapping for 11893`

Loop 3 stopped untracked imported worlds from bypassing preflight. Loop 4 implements the next layer: safe repair of Nukkit-MOT persistent-runtime-ID and protocol-palette drift when the source and target represent the same canonical typed Bedrock block state.

## What Loop 4 implements

### Canonical state matching

MineHost no longer treats a numeric runtime ID as a permanent block identity. It decodes and compares:

- block name;
- every property name;
- each property's NBT type and value;
- block-state schema version.

An exact canonical match is preferred. A version-only match is accepted only when the block name and every typed property are identical. Name-only guesses, property coercion, ambiguous candidates, and fabricated fallback mappings are rejected.

### Derived Nukkit-MOT launch artifact

For a protected Nukkit-MOT world, MineHost now:

1. verifies the original engine JAR against the catalog SHA-256;
2. reads `leveldb_palette.nbt` to obtain persistent runtime IDs;
3. reads each pinned `runtime_block_states_<protocol>.dat` resource;
4. aligns protocol entries to persistent IDs by canonical typed state;
5. writes a deterministic derived launch JAR only when realignment is required;
6. re-inspects the derived JAR and validates every world-used mapping;
7. verifies that unrelated JAR entries remain byte-equivalent;
8. caches the derived artifact by base hash and patch key.

The original downloaded JAR is never modified. The immutable original world is never modified. The derived JAR is used only for the protected working-copy launch.

### Fail-closed boundaries

Repair is refused when:

- the engine JAR hash does not match the catalog;
- the JAR contains signature metadata that would be invalidated;
- a world state is absent from the persistent palette;
- only a name-level or property-mismatched candidate exists;
- a version-only match is ambiguous;
- repaired entries collide at one runtime ID;
- the derived artifact fails its second inspection;
- unrelated JAR resources change.

### Runtime and diagnostic integration

- `PreparedLaunch` can now select a derived engine artifact.
- `BaseJavaEngine` launches that artifact only for the protected Nukkit-MOT run.
- Console output reports the base and derived hashes, cache reuse, total remapped entries, and world-used mapping repairs.
- The Nukkit-MOT adapter accepts exact or version-only typed-state matches and continues to reject semantic mismatches.
- `SporeBlossom`, `TrialSpawner`, and `Vault` block entities remain provisional and protected by runtime proof. Any matching corruption or unknown-block-entity signal still stops the engine and restores the clean working-copy flow.

## New source components

- `BedrockNbtWriter.kt`
- `NukkitMotArtifactRepairer.kt`

Updated core components include:

- `CanonicalBlockState.kt`
- `EngineArtifactInspection.kt`
- `NukkitMotArtifactInspector.kt`
- `NukkitMotWorldAdapter.kt`
- `WorldCompatibilityCore.kt`
- `BaseJavaEngine.kt`

## Test result

The final pure world-core suite passed:

`ALL_PURE_WORLD_TESTS_PASSED count=51`

Coverage includes:

- exact persistent/protocol runtime drift repair;
- version-only match with identical typed properties;
- version-only world-state resolution against the persistent palette;
- property-mismatch refusal;
- ambiguous mapping refusal;
- runtime collision refusal;
- signed-JAR refusal;
- wrong-hash refusal;
- deterministic NBT write/read round-trip;
- derived-artifact cache reuse;
- byte preservation of unrelated JAR entries;
- protected launch, ownership, crash-state, rename, and delete lifecycles.

The complete transcript is stored in `loop4_pure_test_output.txt`.

## Supplied-world validation

The untouched supplied world passed final read-only inspection:

- world: `Negi Land (1)`
- SHA-256: `e4fa29b046dd1888c2ed6bf8fe52c63d6426d773a1d8a172bd86b699d394370a`
- files: `27`
- bytes: `25,300,013`
- current LevelDB records: `79,448`
- persistent palette entries: `494,882`
- unique canonical states: `1,038`
- serializer version: `42`
- dimensions found: Overworld and Nether
- palette parse errors: none
- LevelDB table errors: none

The detailed transcript is stored in `loop4_real_world_inspection.txt`.

## Exact Build 1361 limitation

The Jenkins Build 1361 JAR could not be downloaded inside the implementation environment. Therefore this package cannot honestly claim in advance that persistent runtime ID `11893` has a lossless canonical counterpart in protocol `1001`.

On the user's device, MineHost now performs that proof against the exact downloaded, SHA-verified artifact:

- If `11893` is palette drift and an exact or version-only typed-state match exists, MineHost builds and launches the verified derived JAR.
- If no lossless match exists, MineHost blocks before launch instead of mapping the block to an unrelated fallback.

A successful repair should produce console lines beginning with:

`[Compatibility] Mapping repaired:`

A refusal means Nukkit-MOT genuinely lacks a safe representation under the current palette and requires either real engine block support or a separately designed transactional world conversion.

## Build limitation

A complete Android Gradle build was not run because the uploaded `gradle/wrapper/gradle-wrapper.jar` is corrupt and has no readable ZIP central directory. The unrelated wrapper was preserved rather than silently replaced.
