package com.example.world

import java.io.File

data class PersistentRuntimeDiagnostic(
    val persistentRuntimeId: Int,
    val canonicalBlockState: String,
    val typedProperties: Map<String, Any?>,
    val stateVersion: Int?,
    val dimension: Int?,
    val chunkX: Int?,
    val chunkZ: Int?,
    val subchunkY: Int?,
    val paletteIndex: Int?,
    val occurrenceCount: Long,
    val protocol1001Candidate: String? = null,
    val legacyIdDataResult: String? = null,
    val exactIdNamespace: String = "UNRESOLVED",
    val protocol1001MappingResult: String,
    val associatedBlockEntityType: String?,
    val persistentCandidate: String? = null,
    val protocolCandidate: String? = null,
    val confidence: String = "UNRESOLVED",
    val evidence: List<String> = emptyList(),
)

/**
 * Prepares runtime diagnostics from the exact inspected launch artifact.
 * A canonical state is disclosed only when the artifact proves that the warning
 * consumes BlockStateMapping's persistent runtime ID and the derived palette has
 * the same semantic state at that ID in the selected protocol mapping.
 */
object RuntimeMappingDiagnostics {

    data class DiagnosticSummary(
        val engineId: String,
        val engineVersionId: String,
        val launchArtifactName: String,
        val totalRuntimeIdsMapped: Int,
        val protocolVersionsSupported: List<Int>,
        val totalBlockEntitiesInWorld: Int,
        val supportedBlockEntitiesInJar: Set<String>,
        val unsupportedBlockEntitiesInWorld: Set<String>,
        val worldCanonicalStatesCount: Int,
        val missingCanonicalStates: List<String>,
        val repairsAppliedCount: Int,
        val runtimeLookupEvidence: List<String> = emptyList(),
        val persistentRuntimeDiagnostics: Map<Int, PersistentRuntimeDiagnostic> = emptyMap(),
    ) {
        fun formatLogs(): List<String> = buildList {
            add("[RuntimeMappingDiagnostics] Engine: $engineId ($engineVersionId)")
            add("[RuntimeMappingDiagnostics] Launch Artifact: $launchArtifactName")
            add("[RuntimeMappingDiagnostics] Protocol runtime IDs: $totalRuntimeIdsMapped across protocols $protocolVersionsSupported")
            add("[RuntimeMappingDiagnostics] Artifact-derived block entity support: ${supportedBlockEntitiesInJar.size} identifiers")
            if (unsupportedBlockEntitiesInWorld.isNotEmpty()) {
                add("[RuntimeMappingDiagnostics] WARNING: Unsupported block entities in world: $unsupportedBlockEntitiesInWorld")
            }
            add("[RuntimeMappingDiagnostics] World canonical states: $worldCanonicalStatesCount evaluated")
            if (missingCanonicalStates.isNotEmpty()) {
                add("[RuntimeMappingDiagnostics] WARNING: Missing canonical states (${missingCanonicalStates.size}): ${missingCanonicalStates.take(5)}")
            }
            if (repairsAppliedCount > 0) {
                add("[RuntimeMappingDiagnostics] Derived repairs applied: $repairsAppliedCount")
            }
            if (runtimeLookupEvidence.isNotEmpty()) {
                runtimeLookupEvidence.take(8).forEach { evidence ->
                    add("[RuntimeMappingDiagnostics] Artifact evidence: $evidence")
                }
            }
            add("[RuntimeMappingDiagnostics] Runtime-warning IDs are treated as persistent BlockStateMapping IDs only when that path is proven from the exact launch artifact.")
        }

        fun formatRuntimeIdDiagnostic(runtimeId: Int): List<String> {
            val diag = persistentRuntimeDiagnostics[runtimeId]
            if (diag == null) {
                return listOf(
                    "[RuntimeMappingDiagnostics] Observed runtime ID $runtimeId could not be linked to a proven world-used persistent state.",
                    "[RuntimeMappingDiagnostics] No block identity was guessed; the server remains fail-closed.",
                )
            }
            return buildList {
                add("[RuntimeMappingDiagnostics] Runtime ID $runtimeId diagnostic:")
                add("  Confidence: ${diag.confidence}")
                add("  ID namespace: ${diag.exactIdNamespace}")
                add("  Persistent candidate: ${diag.persistentCandidate ?: "none"}")
                add("  Protocol-1001 candidate: ${diag.protocolCandidate ?: "none"}")
                if (diag.confidence != "UNRESOLVED") {
                    add("  Canonical state: ${diag.canonicalBlockState}")
                    add("  Typed properties: ${diag.typedProperties}")
                    add("  State version: ${diag.stateVersion ?: "N/A"}")
                    add("  Dimension: ${diag.dimension ?: "N/A"}")
                    add("  Chunk: (${diag.chunkX ?: "N/A"}, ${diag.chunkZ ?: "N/A"})")
                    add("  Subchunk: ${diag.subchunkY ?: "N/A"}")
                    add("  Palette index: ${diag.paletteIndex ?: "N/A"}")
                    add("  Occurrence count: ${diag.occurrenceCount}")
                    add("  Associated block entity: ${diag.associatedBlockEntityType ?: "none"}")
                } else {
                    add("  Canonical identity intentionally withheld because the numeric namespaces conflict or remain unproven.")
                }
                add("  Protocol mapping result: ${diag.protocol1001MappingResult}")
                add("  Legacy ID/data: ${diag.legacyIdDataResult ?: "N/A"}")
                diag.evidence.forEach { add("  Evidence: $it") }
            }
        }
    }

    fun analyze(
        engineId: String,
        engineVersionId: String,
        launchArtifact: File,
        inspection: EngineArtifactInspection,
        report: WorldCompatibilityReport,
        repairs: List<NukkitMotArtifactRepairer.RuntimeMappingRepair>,
    ): DiagnosticSummary {
        val worldBlockEntities = report.blockEntityIdentifiers.keys
        val supportedInJar = inspection.supportedBlockEntityIdentifiers
        val unsupportedInWorld = worldBlockEntities - supportedInJar

        val missingStates = report.blockPalette.canonicalStates.filter { state ->
            val semantic = CanonicalBlockStateCodec.semanticIdentity(state.blockName, state.properties)
            inspection.persistentRuntimeIdsByCanonicalState.entries.none {
                CanonicalBlockStateCodec.semanticIdentityFromCanonical(it.key) == semantic
            }
        }.map { it.blockName }.distinct()

        val selectedProtocol = inspection.protocolStatesByRuntimeId.keys.maxOrNull()
        val worldByPersistentId = linkedMapOf<Int, CanonicalBlockStateObservation>()
        report.blockPalette.canonicalStates.forEach { observation ->
            val semantic = CanonicalBlockStateCodec.semanticIdentity(
                observation.blockName,
                observation.properties,
            )
            val persistentId = inspection.persistentRuntimeIdsByCanonicalState[observation.canonicalIdentity]
                ?: inspection.persistentRuntimeIdsByCanonicalState.entries.firstOrNull {
                    CanonicalBlockStateCodec.semanticIdentityFromCanonical(it.key) == semantic
                }?.value
            if (persistentId != null) worldByPersistentId.putIfAbsent(persistentId, observation)
        }

        val diagnostics = linkedMapOf<Int, PersistentRuntimeDiagnostic>()
        worldByPersistentId.forEach { (id, observation) ->
            val resolved = selectedProtocol?.let { protocol ->
                NukkitMotRuntimeLookupResolver.resolveState(
                    observation = observation,
                    artifact = inspection,
                    protocol = protocol,
                )
            }
            val resolution = selectedProtocol?.let { protocol ->
                NukkitMotRuntimeLookupResolver.resolve(
                    observation = observation,
                    artifact = inspection,
                    protocol = protocol,
                )
            }
            val protocolState = resolved?.protocolState
            val confidence = if (resolved != null) "PROVEN_PERSISTENT_RUNTIME_ID" else "UNRESOLVED"
            val evidence = resolution?.evidence.orEmpty().take(12)
            diagnostics[id] = PersistentRuntimeDiagnostic(
                persistentRuntimeId = id,
                canonicalBlockState = if (confidence == "UNRESOLVED") "UNRESOLVED" else observation.canonicalIdentity,
                typedProperties = if (confidence == "UNRESOLVED") emptyMap() else observation.properties.associate { it.name to it.encodedValue },
                stateVersion = if (confidence == "UNRESOLVED") null else observation.blockStateVersion,
                dimension = if (confidence == "UNRESOLVED") null else observation.sampleLocation.dimensionId,
                chunkX = if (confidence == "UNRESOLVED") null else observation.sampleLocation.chunkX,
                chunkZ = if (confidence == "UNRESOLVED") null else observation.sampleLocation.chunkZ,
                subchunkY = if (confidence == "UNRESOLVED") null else observation.sampleLocation.subchunkY,
                paletteIndex = if (confidence == "UNRESOLVED") null else observation.sampleLocation.paletteIndex,
                occurrenceCount = if (confidence == "UNRESOLVED") 0L else observation.occurrenceCount.toLong(),
                protocol1001Candidate = protocolState?.canonicalIdentity,
                legacyIdDataResult = protocolState?.let { "${it.legacyBlockId}:${it.legacyData}" },
                exactIdNamespace = if (resolved != null) {
                    "leveldb_palette.nbt list index proven by exact launch-artifact lookup evidence"
                } else {
                    "UNRESOLVED"
                },
                protocol1001MappingResult = protocolState?.let {
                    "protocol=$selectedProtocol, runtime=${it.runtimeId}, state=${it.canonicalIdentity}, legacy=${it.legacyBlockId}:${it.legacyData}"
                } ?: "no selected-protocol runtime-to-legacy mapping",
                associatedBlockEntityType = exactBlockEntity(report, if (confidence == "UNRESOLVED") null else observation),
                persistentCandidate = observation.canonicalIdentity,
                protocolCandidate = protocolState?.canonicalIdentity,
                confidence = confidence,
                evidence = evidence,
            )
        }

        return DiagnosticSummary(
            engineId = engineId,
            engineVersionId = engineVersionId,
            launchArtifactName = launchArtifact.name,
            totalRuntimeIdsMapped = inspection.protocolStatesByRuntimeId.values.firstOrNull()?.size ?: 0,
            protocolVersionsSupported = inspection.protocolStatesByRuntimeId.keys.sorted(),
            totalBlockEntitiesInWorld = worldBlockEntities.size,
            supportedBlockEntitiesInJar = supportedInJar,
            unsupportedBlockEntitiesInWorld = unsupportedInWorld,
            worldCanonicalStatesCount = report.blockPalette.canonicalStates.size,
            missingCanonicalStates = missingStates,
            repairsAppliedCount = repairs.size,
            runtimeLookupEvidence = inspection.runtimeLookupEvidence,
            persistentRuntimeDiagnostics = diagnostics,
        )
    }

    private fun exactBlockEntity(
        report: WorldCompatibilityReport,
        observation: CanonicalBlockStateObservation?,
    ): String? {
        // BlockStateSourceLocation currently records chunk/subchunk/palette position,
        // not absolute block coordinates. A same-chunk block entity is only a weak
        // hint and must not be reported as a direct association.
        @Suppress("UNUSED_VARIABLE")
        val unavailableExactCoordinates = observation?.sampleLocation
        @Suppress("UNUSED_VARIABLE")
        val retainedEvidence = report.blockEntityObservations
        return null
    }

}
