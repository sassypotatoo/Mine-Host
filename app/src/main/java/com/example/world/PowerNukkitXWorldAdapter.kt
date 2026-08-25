package com.example.world

/**
 * Exact bundled-palette verifier for stable PowerNukkitX releases.
 *
 * This adapter proves whether every decoded world block state exists in the exact
 * pinned engine artifact. It still does not rewrite LevelDB, block entities, actors,
 * or inventories, so every accepted launch remains protected and provisional.
 */
object PowerNukkitXWorldAdapter : ProtectedPassThroughWorldAdapter(
    engineId = "bedrock_power_nukkit_x",
    adapterVersion = "powernukkitx-exact-block-palette-v2",
    displayName = "PowerNukkitX",
    knownSerializerVersions = emptySet(),
) {
    override fun inspectEngineCapabilities(engineVersionId: String): EngineCapabilityProfile =
        EngineCapabilityProfile(
            engineId = engineId,
            engineVersionId = engineVersionId,
            adapterVersion = adapterVersion,
            supportedChunkSerializerVersions = emptySet(),
            blockStateTranslation = CompatibilityStatus.PARTIALLY_SUPPORTED,
            blockEntityTranslation = CompatibilityStatus.UNKNOWN,
            entityTranslation = CompatibilityStatus.UNKNOWN,
            playerPositionMigration = CompatibilityStatus.PARTIALLY_SUPPORTED,
            playerInventoryMigration = CompatibilityStatus.UNSUPPORTED,
            notes = listOf(
                "The exact bundled PowerNukkitX block_palette.nbt is decoded and compared with every world-used canonical block state",
                "MineHost does not rewrite LevelDB records; matching palettes prove recognition, not lossless runtime persistence",
                "Serializer, block-entity, actor and inventory behavior still require protected start-stop-restart evidence",
            ),
        )

    override fun inspectEngineArtifact(context: EngineArtifactContext): EngineArtifactInspection =
        PowerNukkitXArtifactInspector.inspect(context).getOrElse { error ->
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
        val reasons = mutableListOf<String>()
        val serializers = report.chunkSerializerVersions.keys
        val serializerStatus = if (serializers.isEmpty()) {
            CompatibilityStatus.UNKNOWN
        } else {
            reasons += "PowerNukkitX chunk serializer support is not yet proven independently of a protected runtime test; world uses ${serializers.sorted()}"
            CompatibilityStatus.UNKNOWN
        }

        if (report.blockPalette.parseErrors.isNotEmpty()) {
            reasons += "The read-only inspector could not decode ${report.blockPalette.parseErrors.size} world palette/entity records"
        }
        if (report.unmergedActiveLevelDbLogs.isNotEmpty()) {
            reasons += "Active LevelDB logs could not be merged safely: ${report.unmergedActiveLevelDbLogs.joinToString()}"
        }

        val paletteStatus = when {
            artifactInspection == null -> {
                reasons += "The exact PowerNukkitX artifact palette was not inspected"
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
            else -> compareExactPalette(report, artifactInspection, reasons)
        }
        artifactInspection?.warnings.orEmpty().forEach { reasons += "Engine artifact: $it" }

        val blockEntityStatus = when {
            report.blockEntityIdentifiers.isEmpty() -> CompatibilityStatus.UNKNOWN
            artifactInspection == null || artifactInspection.status != CompatibilityStatus.SUPPORTED -> {
                reasons += "PowerNukkitX block-entity registration could not be proven from the exact artifact"
                CompatibilityStatus.UNKNOWN
            }
            else -> compareBlockEntities(report, artifactInspection, reasons)
        }
        if (report.actorEntityIdentifiers.isNotEmpty()) {
            reasons += "${report.actorEntityIdentifiers.values.sum()} actor records require protected PowerNukkitX verification"
        }

        val overall = when {
            report.hasFatalInspectionError -> CompatibilityStatus.CORRUPT
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
            playerMigration = if (report.localPlayer.available) {
                CompatibilityStatus.PARTIALLY_SUPPORTED
            } else {
                CompatibilityStatus.UNKNOWN
            },
            reasons = reasons.distinct(),
            requiresProtectedLaunch = overall == CompatibilityStatus.PARTIALLY_SUPPORTED,
        )
    }


    private fun compareBlockEntities(
        report: WorldCompatibilityReport,
        artifact: EngineArtifactInspection,
        reasons: MutableList<String>,
    ): CompatibilityStatus {
        val missing = report.blockEntityIdentifiers.keys
            .filterNot(artifact.supportedBlockEntityIdentifiers::contains)
            .sorted()
        val supported = report.blockEntityIdentifiers.keys
            .filter(artifact.supportedBlockEntityIdentifiers::contains)
            .sorted()
        if (supported.isNotEmpty()) {
            reasons += "PowerNukkitX 2.0.0 registers ${supported.size} world-used block-entity identifiers, including ${supported.joinToString(limit = 8, truncated = "…")}" 
        }
        missing.forEach { identifier ->
            val observations = report.blockEntityObservations.filter { it.identifier == identifier }
            val sample = observations.firstOrNull()
            val sampleText = if (sample == null) {
                "no decoded record location"
            } else {
                "sample dim=${sample.dimensionId},pos=${sample.blockX},${sample.blockY},${sample.blockZ}," +
                    "chunk=${sample.owningChunkX},${sample.owningChunkZ},raw=${sample.rawNbtSha256}," +
                    "keys=${sample.nbtKeys.joinToString()}"
            }
            reasons += "Not registered by the exact PowerNukkitX 2.0.0 block-entity registry: " +
                "$identifier (${report.blockEntityIdentifiers[identifier] ?: observations.size} records; $sampleText)"
        }
        return if (missing.isEmpty()) CompatibilityStatus.SUPPORTED else CompatibilityStatus.UNSUPPORTED
    }

    private fun compareExactPalette(
        report: WorldCompatibilityReport,
        artifact: EngineArtifactInspection,
        reasons: MutableList<String>,
    ): CompatibilityStatus {
        val missing = report.blockPalette.canonicalStates
            .filterNot { artifact.persistentRuntimeIdsByCanonicalState.containsKey(it.canonicalIdentity) }
            .distinctBy { it.canonicalIdentity }

        missing.take(MAX_DIAGNOSTIC_STATES).forEach { state ->
            reasons += "Missing from PowerNukkitX ${PowerNukkitXArtifactInspector.PALETTE_RESOURCE}: ${formatState(state)}"
        }
        if (missing.size > MAX_DIAGNOSTIC_STATES) {
            reasons += "${missing.size - MAX_DIAGNOSTIC_STATES} additional PowerNukkitX palette misses were suppressed"
        }

        if (missing.isEmpty()) {
            reasons += "All ${report.blockPalette.canonicalStates.size} decoded world block states exist in the exact bundled PowerNukkitX palette"
            return CompatibilityStatus.SUPPORTED
        }
        return CompatibilityStatus.UNSUPPORTED
    }

    private fun formatState(state: CanonicalBlockStateObservation): String {
        val location = state.sampleLocation
        val properties = state.properties.joinToString(",") {
            "${it.name}=${it.type.name}:${it.encodedValue}"
        }
        return "${state.blockName}{$properties} version=${state.blockStateVersion ?: "unknown"} " +
            "at dim=${location.dimensionId},chunk=${location.chunkX},${location.chunkZ}," +
            "subY=${location.subchunkY},palette=${location.paletteIndex},raw=${state.originalRawNbtSha256}"
    }

    private const val MAX_DIAGNOSTIC_STATES = 20
}
