package com.example.world

import java.io.File

/** First real adapter. Unknown runtime IDs are recorded as failures, never replaced with fallback blocks. */
object NukkitMotWorldAdapter : EngineWorldAdapter {
    override val engineId: String = "nukkit-mot"
    override val adapterVersion: String = "nukkit-mot-world-adapter-v3-exact-legacy-layout"

    private val fatalRuntimePatterns = listOf(
        "Invalid chunk serializer",
        "Failed to read chunk",
        "No runtime2legacy mapping",
        "No runtime2FullId mapping",
        "Can not find legacyId",
        "Cannot find legacyId",
        "Missing block runtime mapping",
        "Tried to create block entity that doesn't exists",
        "Tried to create block entity that does not exist",
        "Unknown block entity",
        "Unsupported palette",
        "Failed to decode chunk",
        "Failed to load actor data",
        "Failed to load entity data",
        "Failed to load actor/entity data",
    )

    override fun inspectEngineCapabilities(engineVersionId: String): EngineCapabilityProfile = EngineCapabilityProfile(
        engineId = engineId,
        engineVersionId = engineVersionId,
        adapterVersion = adapterVersion,
        supportedChunkSerializerVersions = setOf(42),
        blockStateTranslation = CompatibilityStatus.PARTIALLY_SUPPORTED,
        blockEntityTranslation = CompatibilityStatus.UNSUPPORTED,
        entityTranslation = CompatibilityStatus.UNKNOWN,
        playerPositionMigration = CompatibilityStatus.PARTIALLY_SUPPORTED,
        playerInventoryMigration = CompatibilityStatus.UNSUPPORTED,
        notes = listOf(
            "Serializer 42 container parsing is observed, but every world-used canonical state must exist in both exact JAR palettes",
            "Stock Build 1361 substitutes its default block when runtime-to-legacy mapping is missing",
            "Inventory migration remains blocked until Nukkit-MOT player serialization is proven lossless",
        ),
    )

    override fun inspectEngineArtifact(context: EngineArtifactContext): EngineArtifactInspection =
        NukkitMotArtifactInspector.inspect(context).getOrElse { error ->
            EngineArtifactInspection(
                engineId = engineId,
                engineVersionId = context.engineVersionId,
                artifactSha256 = null,
                status = CompatibilityStatus.CORRUPT,
                persistentRuntimeIdsByCanonicalState = emptyMap(),
                protocolStatesByRuntimeId = emptyMap(),
                inspectedResources = emptySet(),
                warnings = emptyList(),
                errors = listOf(error.message ?: error::class.java.simpleName),
            )
        }

    override fun checkWorldCompatibility(
        report: WorldCompatibilityReport,
        engineVersionId: String,
        artifactInspection: EngineArtifactInspection?,
    ): EngineWorldCompatibility {
        val capabilities = inspectEngineCapabilities(engineVersionId)
        val reasons = mutableListOf<String>()
        val serializers = report.chunkSerializerVersions.keys
        val serializerStatus = when {
            serializers.isEmpty() -> CompatibilityStatus.UNKNOWN
            serializers.all { it in capabilities.supportedChunkSerializerVersions } -> CompatibilityStatus.SUPPORTED
            else -> CompatibilityStatus.UNSUPPORTED
        }
        if (serializerStatus == CompatibilityStatus.UNSUPPORTED) {
            reasons += "World serializer versions ${serializers.sorted()} are outside adapter capability ${capabilities.supportedChunkSerializerVersions.sorted()}"
        }

        val supportedBlockEntities = artifactInspection
            ?.supportedBlockEntityIdentifiers
            .orEmpty()
        val unsupportedBlockEntitiesInWorld = report.blockEntityIdentifiers.keys
            .filterNot(supportedBlockEntities::contains)
            .sorted()

        val blockEntityStatus = when {
            report.blockEntityIdentifiers.isEmpty() -> CompatibilityStatus.UNKNOWN
            artifactInspection == null -> {
                reasons += "The exact Nukkit-MOT artifact was not inspected for block-entity capabilities"
                CompatibilityStatus.UNKNOWN
            }
            supportedBlockEntities.isEmpty() -> {
                reasons += "The exact Nukkit-MOT artifact did not provide provable block-entity registry capabilities"
                appendUnsupportedBlockEntityReasons(
                    report = report,
                    identifiers = report.blockEntityIdentifiers.keys.sorted(),
                    reasons = reasons,
                )
                CompatibilityStatus.UNSUPPORTED
            }
            unsupportedBlockEntitiesInWorld.isEmpty() -> CompatibilityStatus.SUPPORTED
            else -> {
                appendUnsupportedBlockEntityReasons(
                    report = report,
                    identifiers = unsupportedBlockEntitiesInWorld,
                    reasons = reasons,
                )
                CompatibilityStatus.UNSUPPORTED
            }
        }

        if (report.blockPalette.parseErrors.isNotEmpty()) {
            reasons += "The read-only inspector could not decode ${report.blockPalette.parseErrors.size} palette/entity records"
        }
        if (report.unmergedActiveLevelDbLogs.isNotEmpty()) {
            reasons += "Active LevelDB logs could not be merged safely: ${report.unmergedActiveLevelDbLogs.joinToString()}"
        }

        val paletteStatus = when {
            artifactInspection == null -> {
                reasons += "The exact Nukkit-MOT artifact palettes were not inspected"
                CompatibilityStatus.UNKNOWN
            }
            artifactInspection.status != CompatibilityStatus.SUPPORTED -> {
                reasons += artifactInspection.errors.map { "Engine artifact: $it" }
                CompatibilityStatus.UNSUPPORTED
            }
            report.blockPalette.canonicalStates.isEmpty() -> {
                reasons += "The imported world contains no decoded canonical block states"
                CompatibilityStatus.UNKNOWN
            }
            else -> comparePalettes(report, artifactInspection, reasons)
        }

        val overall = when {
            report.hasFatalInspectionError -> CompatibilityStatus.CORRUPT
            serializerStatus == CompatibilityStatus.UNSUPPORTED -> CompatibilityStatus.UNSUPPORTED
            report.blockPalette.parseErrors.isNotEmpty() -> CompatibilityStatus.UNSUPPORTED
            report.unmergedActiveLevelDbLogs.isNotEmpty() -> CompatibilityStatus.UNSUPPORTED
            paletteStatus == CompatibilityStatus.UNSUPPORTED -> CompatibilityStatus.UNSUPPORTED
            blockEntityStatus == CompatibilityStatus.UNSUPPORTED -> CompatibilityStatus.UNSUPPORTED
            else -> CompatibilityStatus.PARTIALLY_SUPPORTED
        }
        return EngineWorldCompatibility(
            overall = overall,
            clientProtocol = CompatibilityStatus.UNKNOWN,
            serializer = serializerStatus,
            blockPalette = paletteStatus,
            blockEntities = blockEntityStatus,
            entities = CompatibilityStatus.UNKNOWN,
            playerMigration = if (report.localPlayer.available) CompatibilityStatus.PARTIALLY_SUPPORTED else CompatibilityStatus.UNKNOWN,
            reasons = reasons.distinct(),
            requiresProtectedLaunch = overall == CompatibilityStatus.PARTIALLY_SUPPORTED,
        )
    }

    private fun appendUnsupportedBlockEntityReasons(
        report: WorldCompatibilityReport,
        identifiers: List<String>,
        reasons: MutableList<String>,
    ) {
        identifiers.forEach { identifier ->
            val observations = report.blockEntityObservations.filter { it.identifier == identifier }
            val sample = observations.firstOrNull()
            val sampleText = if (sample == null) {
                "no decoded record location"
            } else {
                "sample dim=${sample.dimensionId},pos=${sample.blockX},${sample.blockY},${sample.blockZ}," +
                    "chunk=${sample.owningChunkX},${sample.owningChunkZ},raw=${sample.rawNbtSha256}," +
                    "keys=${sample.nbtKeys.joinToString()}"
            }
            reasons += "Nukkit-MOT artifact cannot safely preserve block entity " +
                "$identifier (${observations.size.coerceAtLeast(report.blockEntityIdentifiers[identifier] ?: 0)} records; $sampleText)"
        }
    }

    private fun comparePalettes(
        report: WorldCompatibilityReport,
        artifact: EngineArtifactInspection,
        reasons: MutableList<String>,
    ): CompatibilityStatus {
        if (artifact.runtimeLookupNamespace != RuntimeLookupNamespace.PERSISTENT_PALETTE) {
            reasons += "The exact Nukkit-MOT runtime2legacy input namespace was not proven from the launch artifact"
            return CompatibilityStatus.UNSUPPORTED
        }

        val unresolvedStates = mutableListOf<Pair<CanonicalBlockStateObservation, Int>>()
        val ambiguousStates = mutableListOf<Pair<CanonicalBlockStateObservation, Int>>()
        val versionMismatchStates = mutableListOf<Pair<CanonicalBlockStateObservation, Int>>()

        for (worldState in report.blockPalette.canonicalStates) {
            for (protocol in artifact.protocolStatesByRuntimeId.keys) {
                val resolution = NukkitMotRuntimeLookupResolver.resolve(
                    observation = worldState,
                    artifact = artifact,
                    protocol = protocol,
                )
                when (resolution) {
                    is RuntimeLookupResolution.Proven -> { /* Satisfied */ }
                    is RuntimeLookupResolution.Ambiguous -> {
                        ambiguousStates += worldState to protocol
                    }
                    is RuntimeLookupResolution.VersionTranslationRequired -> {
                        versionMismatchStates += worldState to protocol
                    }
                    else -> {
                        unresolvedStates += worldState to protocol
                    }
                }
            }
        }

        if (unresolvedStates.isNotEmpty()) {
            addStateDiagnostics(
                reasons,
                "Unresolved runtime mapping for state",
                unresolvedStates.map { it.first }.distinct(),
                suffix = { state ->
                    val protocols = unresolvedStates.filter { it.first.canonicalIdentity == state.canonicalIdentity }
                        .map { it.second }.distinct().sorted()
                    " protocols=${protocols.joinToString()}"
                }
            )
        }
        if (ambiguousStates.isNotEmpty()) {
            addStateDiagnostics(
                reasons,
                "Ambiguous runtime mapping candidate for state",
                ambiguousStates.map { it.first }.distinct(),
                suffix = { state ->
                    val protocols = ambiguousStates.filter { it.first.canonicalIdentity == state.canonicalIdentity }
                        .map { it.second }.distinct().sorted()
                    " protocols=${protocols.joinToString()}"
                }
            )
        }
        if (versionMismatchStates.isNotEmpty()) {
            addStateDiagnostics(
                reasons,
                "Version translation required for state",
                versionMismatchStates.map { it.first }.distinct(),
                suffix = { state ->
                    val protocols = versionMismatchStates.filter { it.first.canonicalIdentity == state.canonicalIdentity }
                        .map { it.second }.distinct().sorted()
                    " protocols=${protocols.joinToString()}"
                }
            )
        }

        return if (unresolvedStates.isEmpty() && ambiguousStates.isEmpty() && versionMismatchStates.isEmpty()) {
            CompatibilityStatus.SUPPORTED
        } else {
            CompatibilityStatus.UNSUPPORTED
        }
    }

    private fun addStateDiagnostics(
        reasons: MutableList<String>,
        heading: String,
        states: List<CanonicalBlockStateObservation>,
        suffix: (CanonicalBlockStateObservation) -> String = { "" },
    ) {
        if (states.isEmpty()) return
        val unique = states.distinctBy { it.canonicalIdentity }
        unique.take(MAX_DIAGNOSTIC_STATES).forEach { state ->
            reasons += "$heading: ${formatState(state)}${suffix(state)}"
        }
        if (unique.size > MAX_DIAGNOSTIC_STATES) {
            reasons += "${unique.size - MAX_DIAGNOSTIC_STATES} additional states for '$heading' were suppressed"
        }
    }

    private fun formatState(state: CanonicalBlockStateObservation): String {
        val location = state.sampleLocation
        val properties = state.properties.joinToString(",") {
            "${it.name}=${it.type.name}:${it.encodedValue}"
        }
        return "${state.blockName}{$properties} version=${state.blockStateVersion ?: "unknown"} " +
            "at dim=${location.dimensionId},chunk=${location.chunkX},${location.chunkZ},subY=${location.subchunkY},palette=${location.paletteIndex},raw=${state.originalRawNbtSha256}"
    }

    override fun detectRuntimeFailure(line: String): String? {
        val normalized = line.trim()
        return fatalRuntimePatterns.firstOrNull { normalized.contains(it, ignoreCase = true) }
            ?.let { normalized }
    }

    fun extractUnknownRuntimeId(line: String): Int? {
        val mapping = Regex(
            "runtime2(?:legacy|FullId)\\s+mapping(?:\\s+for)?\\s+(-?\\d+)",
            RegexOption.IGNORE_CASE,
        ).find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (mapping != null) return mapping
        return Regex("legacyId[^0-9-]+(-?\\d+)", RegexOption.IGNORE_CASE)
            .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }


    private const val MAX_DIAGNOSTIC_STATES = 20
}
