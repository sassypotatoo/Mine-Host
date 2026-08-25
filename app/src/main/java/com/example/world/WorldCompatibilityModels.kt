package com.example.world

enum class CompatibilityStatus {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
    CORRUPT,
}

enum class ConfidenceLevel { HIGH, MEDIUM, LOW, NONE }

data class CompatibilitySection(
    val status: CompatibilityStatus,
    val confidence: ConfidenceLevel,
    val message: String,
)

data class WorldCoordinates(val x: Double, val y: Double, val z: Double)
data class WorldRotation(val yaw: Double, val pitch: Double)

data class ImportedItemSummary(
    val slot: Int?,
    val name: String,
    val count: Int,
    val damage: Int?,
    val hasCustomNbt: Boolean,
    val nbtKeys: Set<String>,
)

data class ImportedPlayerData(
    val available: Boolean,
    val recordSha256: String?,
    val position: WorldCoordinates?,
    val rotation: WorldRotation?,
    val dimensionId: Int?,
    val gameMode: Int?,
    val selectedHotbarSlot: Int?,
    val experienceLevel: Int?,
    val experienceProgress: Double?,
    val inventory: List<ImportedItemSummary>,
    val armor: List<ImportedItemSummary>,
    val offhand: List<ImportedItemSummary>,
    val enderChest: List<ImportedItemSummary>,
    val rawKeys: Set<String>,
)

data class PackReference(
    val packId: String,
    val version: String?,
    val subpack: String?,
    val filesPresent: Boolean,
)

data class BlockPaletteInformation(
    val subchunkFormatVersions: Map<Int, Int>,
    val paletteVersions: Map<Int, Int>,
    val blockNameCounts: Map<String, Int>,
    val uniqueBlockStateCount: Int,
    val paletteEntryCount: Long,
    val parseErrors: List<String>,
    /** Unique typed canonical states used by the imported world's persistent palettes. */
    val canonicalStates: List<CanonicalBlockStateObservation> = emptyList(),
)


data class BlockEntityObservationSummary(
    val identifier: String?,
    val dimensionId: Int,
    val blockX: Int?,
    val blockY: Int?,
    val blockZ: Int?,
    val owningChunkX: Int,
    val owningChunkZ: Int,
    val positionMatchesOwningChunk: Boolean?,
    val nbtKeys: Set<String>,
    val rawNbtSha256: String,
)

data class WorldCompatibilityReport(
    val worldName: String?,
    val sourceWorldHash: String,
    val totalFiles: Int,
    val totalBytes: Long,
    val lastOpenedMinecraftVersion: String?,
    val storageVersion: Int?,
    val networkVersion: Int?,
    val chunkSerializerVersions: Map<Int, Int>,
    val minimumChunkSerializerVersion: Int?,
    val maximumChunkSerializerVersion: Int?,
    val chunkCountByDimension: Map<Int, Int>,
    val dimensionsPresent: Set<Int>,
    val worldSpawn: WorldCoordinates?,
    val localPlayer: ImportedPlayerData,
    val blockPalette: BlockPaletteInformation,
    val blockEntityIdentifiers: Map<String, Int>,
    val actorEntityIdentifiers: Map<String, Int>,
    val behaviorPacks: List<PackReference>,
    val resourcePacks: List<PackReference>,
    val unknownChunkRecordTypes: Map<Int, Int>,
    val levelDbTableErrors: List<String>,
    val activeLevelDbLogs: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
    val sections: Map<String, CompatibilitySection>,
    /** Individual read-only block-entity evidence retained for adapter diagnostics. */
    val blockEntityObservations: List<BlockEntityObservationSummary> = emptyList(),
    val selectedLevelDbManifest: String? = null,
    val liveLevelDbTables: List<String> = emptyList(),
    val ignoredLevelDbTables: List<String> = emptyList(),
    val mergedLevelDbLogs: List<String> = emptyList(),
) {
    val unmergedActiveLevelDbLogs: List<String>
        get() = activeLevelDbLogs.filterNot { it in mergedLevelDbLogs }

    val hasFatalInspectionError: Boolean
        get() = errors.isNotEmpty() || sections.values.any {
            it.status == CompatibilityStatus.CORRUPT
        }
}

data class BedrockWorldInspectionResult(
    val report: WorldCompatibilityReport,
    /** Exact bytes of ~local_player, retained outside the world for one-time migration. */
    val localPlayerRecord: ByteArray?,
    /** Lossless raw records retained for future engine adapters; inspection never rewrites them. */
    val blockEntityRecords: List<BedrockBlockEntityRecord> = emptyList(),
    val actorRecords: List<BedrockActorRecord> = emptyList(),
)
