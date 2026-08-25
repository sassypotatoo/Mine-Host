package com.example.world

import java.io.File

data class EngineCapabilityProfile(
    val engineId: String,
    val engineVersionId: String,
    val adapterVersion: String,
    val supportedChunkSerializerVersions: Set<Int>,
    val blockStateTranslation: CompatibilityStatus,
    val blockEntityTranslation: CompatibilityStatus,
    val entityTranslation: CompatibilityStatus,
    val playerPositionMigration: CompatibilityStatus,
    val playerInventoryMigration: CompatibilityStatus,
    val notes: List<String>,
)

data class EngineWorldCompatibility(
    val overall: CompatibilityStatus,
    val clientProtocol: CompatibilityStatus,
    val serializer: CompatibilityStatus,
    val blockPalette: CompatibilityStatus,
    val blockEntities: CompatibilityStatus,
    val entities: CompatibilityStatus,
    val playerMigration: CompatibilityStatus,
    val reasons: List<String>,
    val requiresProtectedLaunch: Boolean,
)

data class WorldConversionResult(
    val status: CompatibilityStatus,
    val outputWorld: File?,
    val warnings: List<String>,
    val errors: List<String>,
)

interface EngineWorldAdapter {
    val engineId: String
    val adapterVersion: String

    fun inspectEngineCapabilities(engineVersionId: String): EngineCapabilityProfile

    fun inspectEngineArtifact(context: EngineArtifactContext): EngineArtifactInspection = EngineArtifactInspection(
        engineId = engineId,
        engineVersionId = context.engineVersionId,
        artifactSha256 = null,
        status = CompatibilityStatus.UNKNOWN,
        persistentRuntimeIdsByCanonicalState = emptyMap(),
        protocolStatesByRuntimeId = emptyMap(),
        inspectedResources = emptySet(),
        warnings = emptyList(),
        errors = listOf("Exact engine-artifact inspection is not implemented for $engineId"),
    )

    fun checkWorldCompatibility(
        report: WorldCompatibilityReport,
        engineVersionId: String,
        artifactInspection: EngineArtifactInspection? = null,
    ): EngineWorldCompatibility

    /** Must never mutate the permanent original. */
    fun prepareWorkingCopy(
        manager: WorldWorkingCopyManager,
        worldName: String,
        engineVersionId: String,
        allowResume: Boolean = true,
    ): WorldWorkingCopyManager.PreparedCopy = manager.prepareEngineWorkingCopy(
        worldName = worldName,
        engineId = engineId,
        engineVersionId = engineVersionId,
        adapterVersion = adapterVersion,
        allowResume = allowResume,
    )

    fun translateBlockStates(report: WorldCompatibilityReport): WorldConversionResult = unsupported("block-state translation")
    fun translateBlockEntities(report: WorldCompatibilityReport): WorldConversionResult = unsupported("block-entity translation")
    fun translateEntities(report: WorldCompatibilityReport): WorldConversionResult = unsupported("entity translation")
    fun translatePlayerData(report: WorldCompatibilityReport): WorldConversionResult = unsupported("player-data translation")

    fun validatePreparedWorld(worldDirectory: File, expectedSourceHash: String): Result<Unit> = runCatching {
        val fingerprint = WorldFileIntegrity.fingerprint(worldDirectory)
        require(fingerprint.rootHash == expectedSourceHash) { "Prepared working copy does not match its protected source" }
    }

    fun detectRuntimeFailure(line: String): String? = null

    fun restoreAfterFailure(
        manager: WorldWorkingCopyManager,
        worldName: String,
        errors: List<String>,
    ): WorldWorkingCopyManager.Metadata = manager.restoreAfterFailure(worldName, errors)

    private fun unsupported(operation: String) = WorldConversionResult(
        status = CompatibilityStatus.UNSUPPORTED,
        outputWorld = null,
        warnings = emptyList(),
        errors = listOf("$operation is not implemented for $engineId; source data was not modified"),
    )
}

object EngineWorldAdapterRegistry {
    fun forEngine(engineId: String): EngineWorldAdapter? = when (engineId.trim().lowercase()) {
        "nukkit-mot" -> NukkitMotWorldAdapter
        "bedrock_power_nukkit_x" -> PowerNukkitXWorldAdapter
        "bedrock_power_nukkit_x_experimental" -> PowerNukkitXExperimentalWorldAdapter
        "bedrock_power_nukkit" -> PowerNukkitWorldAdapter
        "bedrock_nukkit" -> Pm1eWorldAdapter
        "bedrock_cloudburst_nukkit" -> CloudburstWorldAdapter
        else -> null
    }
}
