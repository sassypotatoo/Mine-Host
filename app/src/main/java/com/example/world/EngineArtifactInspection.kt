package com.example.world

import java.io.File

data class EngineArtifactContext(
    val engineVersionId: String,
    val artifact: File,
    val expectedSha256: String?,
    val protocolVersions: List<Int>,
    val provenance: EngineArtifactProvenance? = null,
)


data class EnginePaletteBlockState(
    val protocol: Int,
    val runtimeId: Int,
    val legacyBlockId: Int,
    val legacyData: Int,
    val stateOverload: Boolean,
    val canonicalIdentity: String,
    val blockName: String,
    val blockStateVersion: Int?,
)

data class BlockEntityCapabilityEvidence(
    val identifier: String,
    val registryClasses: Set<String>,
    val implementationClasses: Set<String>,
    val evidence: List<String>,
)

data class EngineArtifactProvenance(
    val baseArtifactSha256: String,
    val derivedArtifactSha256: String?,
    val profileSha256: String,
    val profileSchema: Int,
    val engineId: String,
    val engineBuild: String,
    val allowedChangedEntries: Set<String>,
    val originalEntryHashes: Map<String, String>,
    val derivedEntryHashes: Map<String, String>,
    val changedEntryHashes: Map<String, Pair<String, String>>,
)


data class EngineArtifactInspection(
    val engineId: String,
    val engineVersionId: String,
    val artifactSha256: String?,
    val status: CompatibilityStatus,
    /** Persistent LevelDB runtime ID assigned by the engine's exact bundled palette. */
    val persistentRuntimeIdsByCanonicalState: Map<String, Int>,
    /** Protocol -> persistent runtime ID -> engine legacy/state mapping. */
    val protocolStatesByRuntimeId: Map<Int, Map<Int, EnginePaletteBlockState>>,
    val inspectedResources: Set<String>,
    val warnings: List<String>,
    val errors: List<String>,
    /** Block-entity save identifiers proven present in the exact engine artifact. */
    val supportedBlockEntityIdentifiers: Set<String> = emptySet(),
    val blockEntityCapabilityEvidence: Map<String, BlockEntityCapabilityEvidence> = emptyMap(),
    /** Exact-artifact classes/constants related to runtime-to-legacy lookup. */
    val runtimeLookupEvidence: List<String> = emptyList(),
    /** Namespace consumed by BlockStateMapping.getLegacyId/getLegacyData, proven from the exact JAR. */
    val runtimeLookupNamespace: RuntimeLookupNamespace = RuntimeLookupNamespace.UNKNOWN,
    val runtimeLookupClassName: String? = null,
    val runtimeLookupMethodName: String? = null,
    /** Exact Block.DATA_BITS value proven from the artifact's <clinit> bytecode. */
    val legacyDataBits: Int? = null,
    /** Mask derived from the proven legacyDataBits value. */
    val legacyDataMask: Int? = null,
)
