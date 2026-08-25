package com.example.world

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Protected pass-through adapters for Nukkit-family engines whose exact persistent palette
 * format has not yet been decoded by MineHost. They never claim lossless conversion.
 * The immutable source + disposable working-copy gate remains mandatory.
 */
abstract class ProtectedPassThroughWorldAdapter(
    final override val engineId: String,
    final override val adapterVersion: String,
    private val displayName: String,
    private val knownSerializerVersions: Set<Int>,
) : EngineWorldAdapter {

    override fun inspectEngineCapabilities(engineVersionId: String): EngineCapabilityProfile =
        EngineCapabilityProfile(
            engineId = engineId,
            engineVersionId = engineVersionId,
            adapterVersion = adapterVersion,
            supportedChunkSerializerVersions = knownSerializerVersions,
            blockStateTranslation = CompatibilityStatus.UNKNOWN,
            blockEntityTranslation = CompatibilityStatus.UNKNOWN,
            entityTranslation = CompatibilityStatus.UNKNOWN,
            playerPositionMigration = CompatibilityStatus.PARTIALLY_SUPPORTED,
            playerInventoryMigration = CompatibilityStatus.UNSUPPORTED,
            notes = listOf(
                "$displayName receives an unchanged verified working copy; MineHost does not rewrite LevelDB records for this adapter",
                "Runtime world errors are fail-closed and restore the engine-facing copy from the immutable original",
                "Exact block-state, block-entity, entity and inventory preservation still requires engine-specific proof",
            ),
        )

    override fun inspectEngineArtifact(context: EngineArtifactContext): EngineArtifactInspection =
        inspectReadableJar(context)

    override fun checkWorldCompatibility(
        report: WorldCompatibilityReport,
        engineVersionId: String,
        artifactInspection: EngineArtifactInspection?,
    ): EngineWorldCompatibility {
        val reasons = mutableListOf<String>()
        val serializers = report.chunkSerializerVersions.keys
        val serializer = when {
            serializers.isEmpty() -> CompatibilityStatus.UNKNOWN
            knownSerializerVersions.isEmpty() -> CompatibilityStatus.UNKNOWN
            serializers.all(knownSerializerVersions::contains) -> CompatibilityStatus.SUPPORTED
            else -> CompatibilityStatus.UNSUPPORTED
        }
        if (knownSerializerVersions.isEmpty()) {
            reasons += "$displayName serializer support has not been proven from the exact engine artifact"
        } else if (serializer == CompatibilityStatus.UNSUPPORTED) {
            reasons += "World serializer versions ${serializers.sorted()} are outside the proven adapter set ${knownSerializerVersions.sorted()}"
        }

        val artifactStatus = artifactInspection?.status ?: CompatibilityStatus.UNKNOWN
        when {
            artifactInspection == null -> reasons += "The exact $displayName JAR was not inspected"
            artifactInspection.errors.isNotEmpty() -> reasons += artifactInspection.errors.map { "Engine artifact: $it" }
            else -> reasons += artifactInspection.warnings.map { "Engine artifact: $it" }
        }
        if (report.blockPalette.parseErrors.isNotEmpty()) {
            reasons += "The read-only inspector could not decode ${report.blockPalette.parseErrors.size} world palette/entity records"
        }
        if (report.unmergedActiveLevelDbLogs.isNotEmpty()) {
            reasons += "Active LevelDB logs could not be merged safely: ${report.unmergedActiveLevelDbLogs.joinToString()}"
        }
        if (report.blockPalette.canonicalStates.isNotEmpty()) {
            reasons += "${report.blockPalette.canonicalStates.size} canonical world states were decoded, but this adapter has no exact $displayName persistent-runtime mapping yet"
        }
        if (report.blockEntityIdentifiers.isNotEmpty()) {
            reasons += "${report.blockEntityIdentifiers.values.sum()} block-entity records require protected runtime verification"
        }
        if (report.actorEntityIdentifiers.isNotEmpty()) {
            reasons += "${report.actorEntityIdentifiers.values.sum()} actor records require protected runtime verification"
        }

        val overall = when {
            report.hasFatalInspectionError -> CompatibilityStatus.CORRUPT
            artifactStatus == CompatibilityStatus.CORRUPT || artifactStatus == CompatibilityStatus.UNSUPPORTED ->
                CompatibilityStatus.UNSUPPORTED
            serializer == CompatibilityStatus.UNSUPPORTED -> CompatibilityStatus.UNSUPPORTED
            report.blockPalette.parseErrors.isNotEmpty() -> CompatibilityStatus.UNSUPPORTED
            report.unmergedActiveLevelDbLogs.isNotEmpty() -> CompatibilityStatus.UNSUPPORTED
            else -> CompatibilityStatus.PARTIALLY_SUPPORTED
        }

        return EngineWorldCompatibility(
            overall = overall,
            clientProtocol = CompatibilityStatus.UNKNOWN,
            serializer = serializer,
            blockPalette = CompatibilityStatus.UNKNOWN,
            blockEntities = CompatibilityStatus.UNKNOWN,
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

    override fun detectRuntimeFailure(line: String): String? {
        val normalized = line.trim()
        return RUNTIME_WORLD_FAILURE_PATTERNS.firstOrNull { normalized.contains(it, ignoreCase = true) }
            ?.let { normalized.take(MAX_RUNTIME_ERROR_LENGTH) }
    }

    private fun inspectReadableJar(context: EngineArtifactContext): EngineArtifactInspection {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val inspectedResources = linkedSetOf<String>()
        var actualSha256: String? = null

        runCatching {
            require(context.artifact.isFile && context.artifact.length() > 0L) {
                "$displayName artifact is missing or empty: ${context.artifact.absolutePath}"
            }
            actualSha256 = sha256(context.artifact)
            val expected = context.expectedSha256?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            if (expected == null) {
                warnings += "No catalogued SHA-256 was available; the JAR can be inspected but not pinned by this adapter"
            } else {
                require(expected.matches(SHA_256_PATTERN)) { "Catalogued SHA-256 has an invalid format" }
                require(actualSha256 == expected) {
                    "$displayName artifact SHA-256 mismatch: expected $expected but found $actualSha256"
                }
            }

            ZipFile(context.artifact).use { jar ->
                var classEntries = 0
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val lower = entry.name.lowercase()
                    if (lower.endsWith(".class")) classEntries++
                    if (RESOURCE_HINTS.any(lower::contains)) {
                        inspectedResources += entry.name
                    }
                }
                require(classEntries > 0) { "$displayName artifact contains no Java classes" }
                if (inspectedResources.isEmpty()) {
                    warnings += "No palette-labelled resources were found; exact persistent runtime mapping remains unknown"
                } else {
                    warnings += "Palette-labelled resources were located but are not yet decoded by this adapter"
                }
            }
        }.onFailure { error ->
            errors += error.message ?: error::class.java.simpleName
        }

        return EngineArtifactInspection(
            engineId = engineId,
            engineVersionId = context.engineVersionId,
            artifactSha256 = actualSha256,
            status = if (errors.isEmpty()) CompatibilityStatus.PARTIALLY_SUPPORTED else CompatibilityStatus.CORRUPT,
            persistentRuntimeIdsByCanonicalState = emptyMap(),
            protocolStatesByRuntimeId = emptyMap(),
            inspectedResources = inspectedResources,
            warnings = warnings.distinct(),
            errors = errors.distinct(),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val SHA_256_PATTERN = Regex("^[a-f0-9]{64}$")
        val RESOURCE_HINTS = listOf("palette", "runtime_block", "block_state", "leveldb", "serializer")
        val RUNTIME_WORLD_FAILURE_PATTERNS = listOf(
            "Invalid chunk serializer",
            "Unsupported chunk serializer",
            "Failed to read chunk",
            "Cannot deserialize chunk",
            "Corrupt LevelDB chunk",
            "Missing required world database",
            "Failed to load the active level",
            "No runtime2legacy mapping",
            "No runtime2FullId mapping",
            "Can not find legacyId",
            "Cannot find legacyId",
            "Missing block runtime mapping",
            "Unknown block entity",
            "Unsupported palette",
            "Failed to decode chunk",
            "Failed to load actor data",
            "Failed to load entity data",
            "Failed to load actor/entity data",
        )
        const val MAX_RUNTIME_ERROR_LENGTH = 16_000
    }
}


object PowerNukkitXExperimentalWorldAdapter : ProtectedPassThroughWorldAdapter(
    engineId = "bedrock_power_nukkit_x_experimental",
    adapterVersion = "powernukkitx-experimental-protected-pass-through-v1",
    displayName = "PowerNukkitX Experimental",
    knownSerializerVersions = emptySet(),
)

object Pm1eWorldAdapter : ProtectedPassThroughWorldAdapter(
    engineId = "bedrock_nukkit",
    adapterVersion = "pm1e-protected-pass-through-v1",
    displayName = "PM1E",
    knownSerializerVersions = emptySet(),
)

object PowerNukkitWorldAdapter : ProtectedPassThroughWorldAdapter(
    engineId = "bedrock_power_nukkit",
    adapterVersion = "powernukkit-protected-pass-through-v1",
    displayName = "PowerNukkit",
    knownSerializerVersions = emptySet(),
)

object CloudburstWorldAdapter : ProtectedPassThroughWorldAdapter(
    engineId = "bedrock_cloudburst_nukkit",
    adapterVersion = "cloudburst-protected-pass-through-v1",
    displayName = "Cloudburst Nukkit",
    knownSerializerVersions = emptySet(),
)
