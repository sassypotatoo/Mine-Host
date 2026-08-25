package com.example.world

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.SyncFailedException
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@JvmInline
value class PersistentPaletteId(val value: Int)

@JvmInline
value class ProtocolRuntimeId(val value: Int)

@JvmInline
value class Runtime2LegacyLookupKey(val value: Int)

@JvmInline
value class LegacyFullId(val value: Int)

@JvmInline
value class ProtocolId(val value: Int)

enum class RuntimeLookupNamespace {
    PERSISTENT_PALETTE,
    PROTOCOL_RUNTIME,
    STATE_HASH,
    ENGINE_TRANSLATED,
    LEGACY_FULL_ID,
    UNKNOWN,
}

sealed interface RuntimeLookupResolution {
    val evidence: List<String>

    data class Proven(
        val persistentId: PersistentPaletteId,
        val protocolRuntimeId: ProtocolRuntimeId?,
        val runtime2LegacyKey: Runtime2LegacyLookupKey,
        val legacyFullId: LegacyFullId?,
        val namespace: RuntimeLookupNamespace,
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class Ambiguous(
        val persistentId: PersistentPaletteId,
        val canonicalIdentity: String,
        val candidates: List<String>,
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class VersionTranslationRequired(
        val persistentId: PersistentPaletteId,
        val canonicalIdentity: String,
        val worldVersion: Int?,
        val targetVersion: Int?,
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class ProfileMismatch(
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class NamespaceUnproven(
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class Unsupported(
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class CorruptInput(
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class RequiresEngineUpdate(
        override val evidence: List<String>,
    ) : RuntimeLookupResolution

    data class Unresolved(
        val persistentId: PersistentPaletteId,
        val canonicalIdentity: String,
        override val evidence: List<String>,
    ) : RuntimeLookupResolution
}

data class PersistentPaletteState(
    val persistentId: PersistentPaletteId,
    val canonicalState: CanonicalBlockStateCodec.State,
)

data class ProtocolPaletteState(
    val entryIndex: Int,
    val protocolRuntimeId: ProtocolRuntimeId,
    val canonicalState: CanonicalBlockStateCodec.State,
    val legacyFullId: LegacyFullId,
    val stateOverload: Boolean,
)

data class EffectiveRuntimeMapping(
    val runtimeId: ProtocolRuntimeId,
    val canonicalState: CanonicalBlockStateCodec.State,
    val legacyFullId: LegacyFullId,
    val sourceEntryIndex: Int,
)

enum class RuntimeCollisionCause {
    WRONG_ID_NAMESPACE,
    DUPLICATE_ENGINE_MAPPING,
    TARGET_ALREADY_OCCUPIED,
    AMBIGUOUS_CANONICAL_MATCH,
    WORLD_STATE_UNSUPPORTED,
    UNRESOLVED_LOOKUP_PATH,
}

data class RuntimeMappingCollision(
    val runtimeId: Int,
    val existingState: CanonicalBlockStateCodec.State,
    val requestedState: CanonicalBlockStateCodec.State,
    val existingEntryIndex: Int?,
    val requestedEntryIndex: Int?,
    val existingLegacyFullId: Int?,
    val requestedLegacyFullId: Int?,
    val existingStateOverload: Boolean?,
    val requestedStateOverload: Boolean?,
    val existingIdNamespace: String,
    val requestedIdNamespace: String,
    val worldOccurrenceCount: Int,
    val firstWorldLocation: BlockStateSourceLocation?,
    val cause: RuntimeCollisionCause = RuntimeCollisionCause.TARGET_ALREADY_OCCUPIED,
    val evidence: List<String> = emptyList(),
)

/**
 * Aligns Nukkit-MOT's bundled persistent palette with the exact protocol palette
 * selected by the engine.
 *
 * The exact Nukkit-MOT lookup architecture is:
 * leveldb_palette.nbt list index -> BlockStateMapping runtime ID ->
 * NukkitLegacyMapper.runtimeToLegacy* -> BlockPalette.getLegacyFullId(runtime ID).
 * Consequently, changing protocol runtime IDs is unsafe. The only resource-level
 * repair used here is a deterministic permutation of leveldb_palette.nbt so a
 * semantic state is registered at the runtime ID already owned by the same state
 * in runtime_block_states_<protocol>.dat.
 */
object NukkitMotArtifactRepairer {
    data class RuntimeNamespaceCardinality(
        val persistentEntryCount: Int,
        val maximumPersistentId: Int,
        val protocolEntryCount: Int,
        val maximumProtocolRuntimeId: Int,
        val protocolGapCount: Int,
        val overloadedRuntimeIds: Set<ProtocolRuntimeId>,
    )

    const val REPAIRER_VERSION = "nukkit-mot-persistent-palette-alignment-v5-exact-legacy-layout"
    private const val PERSISTENT_RESOURCE = "leveldb_palette.nbt"

    enum class MatchKind { EXACT_CANONICAL, VERSION_ONLY }

    data class RuntimeMappingRepair(
        val protocol: Int,
        /** Runtime ID before the persistent palette was aligned. */
        val persistentRuntimeId: Int,
        /** Runtime ID already owned by the matching state in the protocol palette. */
        val originalProtocolRuntimeId: Int,
        val blockName: String,
        val canonicalIdentity: String,
        val legacyBlockId: Int,
        val legacyData: Int,
        val matchKind: MatchKind,
    )

    data class PreparedArtifact(
        val launchArtifact: File,
        val baseArtifactSha256: String,
        val launchArtifactSha256: String,
        val derived: Boolean,
        val reusedCachedArtifact: Boolean,
        val remappedEntryCount: Int,
        val worldRepairs: List<RuntimeMappingRepair>,
        val warnings: List<String>,
        val provenance: EngineArtifactProvenance? = null,
    )

    data class PersistentPalette(
        val rootName: String,
        val rootCompound: Map<String, Any?>,
        val rawBlocks: List<Map<String, Any?>>,
        val states: List<PersistentPaletteState>,
        val stateByPersistentId: Map<PersistentPaletteId, PersistentPaletteState>,
        val persistentIdByCanonical: Map<String, PersistentPaletteId>,
        val persistentIdsBySemantic: Map<String, List<PersistentPaletteId>>,
    )

    data class ProtocolEntry(
        val originalIndex: Int,
        val originalRuntimeId: ProtocolRuntimeId,
        val legacyBlockId: Int,
        val legacyData: Int,
        val legacyFullId: LegacyFullId,
        val stateOverload: Boolean,
        val canonical: CanonicalBlockStateCodec.State,
    )

    data class ProtocolPalette(
        val protocol: Int,
        val rootName: String,
        val entries: List<ProtocolEntry>,
        val states: List<ProtocolPaletteState>,
    )

    private data class AlignmentPlan(
        val reorderedBlocks: List<Map<String, Any?>>,
        val movedEntryCount: Int,
        val worldRepairs: List<RuntimeMappingRepair>,
        val evidence: List<String>,
    )

    fun prepare(
        context: EngineArtifactContext,
        report: WorldCompatibilityReport,
        cacheRoot: File,
    ): Result<PreparedArtifact> = runCatching {
        require(context.protocolVersions.isNotEmpty()) {
            "Nukkit-MOT palette alignment requires at least one protocol palette"
        }

        val verifiedOriginal = NukkitMotArtifactInspector.inspect(context).getOrThrow()
        require(verifiedOriginal.status == CompatibilityStatus.SUPPORTED) {
            "The original Nukkit-MOT artifact failed inspection: " +
                verifiedOriginal.errors.joinToString()
        }
        val baseHash = verifiedOriginal.artifactSha256
            ?: error("The verified Nukkit-MOT artifact has no SHA-256")
        val legacyDataBits = verifiedOriginal.legacyDataBits
            ?: error("The exact Nukkit-MOT artifact did not prove Block.DATA_BITS")
        val legacyDataMask = verifiedOriginal.legacyDataMask
            ?: error("The exact Nukkit-MOT artifact did not provide a derived Block.DATA_MASK")
        val protocol = context.protocolVersions.maxOrNull()
            ?: error("No Nukkit-MOT protocol was selected")

        if (verifiedOriginal.runtimeLookupNamespace != RuntimeLookupNamespace.PERSISTENT_PALETTE) {
            error("The exact artifact runtime2legacy input namespace could not be proven; derived palette repair is disabled.")
        }

        val plan: AlignmentPlan
        val encodedBytes: ByteArray
        val reportCard: RuntimeNamespaceCardinality
        java.util.zip.ZipFile(context.artifact).use { jar ->
            rejectSignedJar(jar)
            val persistent = readPersistentPalette(jar)
            val protocolPalette = readProtocolPalette(
                jar = jar,
                resource = "runtime_block_states_${protocol}.dat",
                protocol = protocol,
                legacyDataBits = legacyDataBits,
                legacyDataMask = legacyDataMask,
            )
            reportCard = produceNamespaceReport(persistent, protocolPalette)
            plan = buildAlignmentPlan(persistent, protocolPalette, report, verifiedOriginal)
            encodedBytes = encodePersistentPalette(persistent, plan.reorderedBlocks)
        }
        
        val warnings = mutableListOf<String>()
        warnings.add("Exact artifact lookup path was proven.")
        warnings.add("Namespace cardinality: $reportCard")
        warnings.addAll(plan.evidence)
        
        if (plan.movedEntryCount == 0) {
            warnings.add("No resource permutation was required because persistent palette was already aligned.")
            return@runCatching PreparedArtifact(
                launchArtifact = context.artifact,
                baseArtifactSha256 = baseHash,
                launchArtifactSha256 = baseHash,
                derived = false,
                reusedCachedArtifact = false,
                remappedEntryCount = 0,
                worldRepairs = emptyList(),
                warnings = warnings,
                provenance = null,
            )
        }

        val worldHash = report.sourceWorldHash
        val patchKey = sha256(encodedBytes.inputStream())
        val patchName = "$baseHash-${worldHash.take(16)}-$protocol-$patchKey.jar"
        val derivedJar = File(cacheRoot, patchName)
        val metadataFile = File(cacheRoot, "$patchName.meta")
        
        // Compute entry hashes of original jar
        val originalEntryHashes = mutableMapOf<String, String>()
        ZipFile(context.artifact).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    originalEntryHashes[entry.name] = sha256(jar.getInputStream(entry))
                }
            }
        }

        createDerivedJar(
            sourceJar = context.artifact,
            destination = derivedJar,
            replacementResource = PERSISTENT_RESOURCE,
            replacementBytes = encodedBytes,
        )
        
        val derivedHash = sha256(derivedJar)

        // Compute entry hashes of derived jar
        val derivedEntryHashes = mutableMapOf<String, String>()
        ZipFile(derivedJar).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    derivedEntryHashes[entry.name] = sha256(jar.getInputStream(entry))
                }
            }
        }

        val origNbtHash = originalEntryHashes[PERSISTENT_RESOURCE] ?: ""
        val derivedNbtHash = derivedEntryHashes[PERSISTENT_RESOURCE] ?: ""
        val changedEntryHashes = mapOf(PERSISTENT_RESOURCE to Pair(origNbtHash, derivedNbtHash))

        val provenance = EngineArtifactProvenance(
            baseArtifactSha256 = baseHash,
            derivedArtifactSha256 = derivedHash,
            profileSha256 = NukkitMotBuild1361Profile.PROFILE.artifactSha256,
            profileSchema = NukkitMotBuild1361Profile.PROFILE.profileSchema,
            engineId = "Nukkit-MOT",
            engineBuild = "1361",
            allowedChangedEntries = setOf(PERSISTENT_RESOURCE),
            originalEntryHashes = originalEntryHashes,
            derivedEntryHashes = derivedEntryHashes,
            changedEntryHashes = changedEntryHashes,
        )

        writeCacheMetadata(
            metadataFile = metadataFile,
            baseHash = baseHash,
            worldHash = worldHash,
            protocol = protocol,
            derivedHash = derivedHash
        )
        
        val derivedContext = context.copy(
            artifact = derivedJar,
            expectedSha256 = derivedHash,
            provenance = provenance
        )
        
        val derivedInspection = NukkitMotArtifactInspector.inspect(derivedContext).getOrThrow()
        require(derivedInspection.status == CompatibilityStatus.SUPPORTED) {
            "Derived artifact failed inspection: ${derivedInspection.errors.joinToString()}"
        }
        
        validateAlignedWorldMappings(report, derivedInspection, protocol)

        PreparedArtifact(
            launchArtifact = derivedJar,
            baseArtifactSha256 = baseHash,
            launchArtifactSha256 = derivedHash,
            derived = true,
            reusedCachedArtifact = false,
            remappedEntryCount = plan.movedEntryCount,
            worldRepairs = plan.worldRepairs,
            warnings = warnings,
            provenance = provenance,
        )
    }

    private fun buildAlignmentPlan(
        persistent: PersistentPalette,
        protocolPalette: ProtocolPalette,
        report: WorldCompatibilityReport,
        verifiedOriginal: EngineArtifactInspection,
    ): AlignmentPlan {
        val targetSize = Math.max(persistent.states.size, (protocolPalette.entries.maxOfOrNull { it.originalRuntimeId.value } ?: 0) + 1)
        val newRawBlocks = arrayOfNulls<Map<String, Any?>>(targetSize)
        val placedPersistentIds = mutableSetOf<Int>()
        var movedCount = 0
        val evidence = mutableListOf<String>()
        val worldRepairs = mutableListOf<RuntimeMappingRepair>()
        
        // Map world states to their expected protocol ID using the authoritative resolver
        val worldStatesTargetIds = mutableMapOf<Int, Int>()
        for (observation in report.blockPalette.canonicalStates) {
            val resolution = NukkitMotRuntimeLookupResolver.resolve(observation, verifiedOriginal, protocolPalette.protocol)
            
            if (resolution is RuntimeLookupResolution.Proven) {
                worldStatesTargetIds[resolution.persistentId.value] = resolution.protocolRuntimeId?.value ?: error("Missing protocolRuntimeId for proven resolution")
            } else if (resolution is RuntimeLookupResolution.Unresolved) {
                error("World uses state ${observation.blockName} which cannot be resolved in protocol ${protocolPalette.protocol}: ${resolution.evidence.joinToString()}")
            }
        }
        
        // First, place world-used states exactly where they need to be
        for ((persistentId, targetId) in worldStatesTargetIds) {
            if (newRawBlocks.size <= targetId) {
                error("Target protocol runtime ID $targetId exceeds palette size")
            }
            if (newRawBlocks[targetId] != null && newRawBlocks[targetId] !== persistent.rawBlocks[persistentId]) {
                error("World state collision at target runtime ID $targetId")
            }
            newRawBlocks[targetId] = persistent.rawBlocks[persistentId]
            placedPersistentIds.add(persistentId)
            if (persistentId != targetId && worldRepairs.none { it.persistentRuntimeId == persistentId }) {
                movedCount++
                val protocolEntry = protocolPalette.entries.first { it.originalRuntimeId.value == targetId }
                worldRepairs.add(RuntimeMappingRepair(
                    protocol = protocolPalette.protocol,
                    persistentRuntimeId = persistentId,
                    originalProtocolRuntimeId = targetId,
                    canonicalIdentity = protocolEntry.canonical.canonicalIdentity,
                    blockName = protocolEntry.canonical.blockName,
                    legacyBlockId = protocolEntry.legacyBlockId,
                    legacyData = protocolEntry.legacyData,
                    matchKind = MatchKind.EXACT_CANONICAL
                ))
            }
        }
        
        // Second, place other protocol states
        for (entry in protocolPalette.entries) {
            val targetId = entry.originalRuntimeId.value
            if (newRawBlocks[targetId] != null) continue
            val persistentId = persistent.persistentIdByCanonical[entry.canonical.canonicalIdentity]?.value
            if (persistentId != null && !placedPersistentIds.contains(persistentId)) {
                newRawBlocks[targetId] = persistent.rawBlocks[persistentId]
                placedPersistentIds.add(persistentId)
                if (persistentId != targetId) movedCount++
            }
        }
        
        // Third, place remaining persistent states in empty slots
        var nextEmptyIndex = 0
        for (i in persistent.rawBlocks.indices) {
            if (!placedPersistentIds.contains(i)) {
                while (nextEmptyIndex < newRawBlocks.size && newRawBlocks[nextEmptyIndex] != null) {
                    nextEmptyIndex++
                }
                if (nextEmptyIndex < newRawBlocks.size) {
                    newRawBlocks[nextEmptyIndex] = persistent.rawBlocks[i]
                    if (i != nextEmptyIndex) movedCount++
                } else {
                    error("Not enough space to place remaining persistent state $i")
                }
            }
        }
        
        val finalBlocks = newRawBlocks.filterNotNull()
        if (movedCount > 0) {
            evidence.add("Permuted leveldb_palette.nbt to align persistent IDs with protocol runtime IDs.")
            evidence.add("Repaired ${worldRepairs.size} world-used mappings.")
        } else {
            evidence.add("No JAR mutation is required.")
        }
        
        return AlignmentPlan(
            reorderedBlocks = finalBlocks,
            movedEntryCount = movedCount,
            worldRepairs = worldRepairs,
            evidence = evidence
        )
    }

    internal fun produceNamespaceReport(
        persistent: PersistentPalette,
        protocolPalette: ProtocolPalette
    ): RuntimeNamespaceCardinality {
        val persistentEntryCount = persistent.states.size
        val maximumPersistentId = persistent.states.maxOfOrNull { it.persistentId.value } ?: 0
        val protocolEntryCount = protocolPalette.entries.size
        val maxProtocolId = protocolPalette.entries.maxOfOrNull { it.originalRuntimeId.value } ?: 0
        
        val uniqueProtocolIds = protocolPalette.entries.map { it.originalRuntimeId.value }.toSet()
        val protocolGapCount = (maxProtocolId + 1) - uniqueProtocolIds.size
        
        val overloaded = protocolPalette.entries.groupBy { it.originalRuntimeId }
            .filter { it.value.size > 1 }
            .keys
            
        return RuntimeNamespaceCardinality(
            persistentEntryCount = persistentEntryCount,
            maximumPersistentId = maximumPersistentId,
            protocolEntryCount = protocolEntryCount,
            maximumProtocolRuntimeId = maxProtocolId,
            protocolGapCount = protocolGapCount,
            overloadedRuntimeIds = overloaded
        )
    }

    private fun readPersistentPalette(jar: ZipFile): PersistentPalette {
        val parsed = readGzipNbt(jar, PERSISTENT_RESOURCE)
        val root = parsed.value.stringKeyMap("$PERSISTENT_RESOURCE root")
        val blocks = root["blocks"] as? List<*>
            ?: error("$PERSISTENT_RESOURCE has no compound list named 'blocks'")
        require(blocks.size <= MAX_PALETTE_ENTRIES) {
            "$PERSISTENT_RESOURCE has too many entries: ${blocks.size}"
        }
        val rawBlocks = blocks.mapIndexed { index, raw ->
            raw.stringKeyMap("$PERSISTENT_RESOURCE block $index")
        }
        val states = rawBlocks.mapIndexed { index, compound ->
            PersistentPaletteState(
                persistentId = PersistentPaletteId(index),
                canonicalState = CanonicalBlockStateCodec.fromCompound(compound),
            )
        }
        val byCanonical = linkedMapOf<String, PersistentPaletteId>()
        states.forEach { state ->
            val previous = byCanonical.putIfAbsent(
                state.canonicalState.canonicalIdentity,
                state.persistentId,
            )
            require(previous == null) {
                "Duplicate persistent canonical state at IDs ${previous?.value} and " +
                    "${state.persistentId.value}: ${state.canonicalState.blockName}"
            }
        }
        return PersistentPalette(
            rootName = parsed.rootName,
            rootCompound = root,
            rawBlocks = rawBlocks,
            states = states,
            stateByPersistentId = states.associateBy { it.persistentId },
            persistentIdByCanonical = byCanonical,
            persistentIdsBySemantic = states.groupBy { it.canonicalState.semanticIdentity }
                .mapValues { (_, values) -> values.map { it.persistentId }.distinct() },
        )
    }

    private fun resolvePersistentRuntimeId(
        observation: CanonicalBlockStateObservation,
        persistent: PersistentPalette,
    ): PersistentPaletteId {
        persistent.persistentIdByCanonical[observation.canonicalIdentity]?.let { return it }
        val semantic = CanonicalBlockStateCodec.semanticIdentity(
            observation.blockName,
            observation.properties,
        )
        val candidates = persistent.persistentIdsBySemantic[semantic].orEmpty()
        require(candidates.size == 1) {
            if (candidates.isEmpty()) {
                "World state is missing from Nukkit-MOT $PERSISTENT_RESOURCE: ${formatWorldState(observation)}"
            } else {
                "World state ${observation.blockName} has ambiguous persistent mappings: " +
                    candidates.joinToString { it.value.toString() }
            }
        }
        return candidates.single()
    }

    private fun readProtocolPalette(
        jar: ZipFile,
        resource: String,
        protocol: Int,
        legacyDataBits: Int,
        legacyDataMask: Int,
    ): ProtocolPalette {
        val parsed = readGzipNbt(jar, resource)
        val list = parsed.value as? List<*>
            ?: error("$resource root is not an NBT list")
        require(list.size <= MAX_PALETTE_ENTRIES) { "$resource has too many entries: ${list.size}" }
        val entries = list.mapIndexed { index, raw ->
            val compound = raw.stringKeyMap("$resource entry $index")
            val canonical = CanonicalBlockStateCodec.fromCompound(compound)
            val runtimeId = compound.intValue("runtimeId")
                ?: error("$resource entry $index has no runtimeId")
            val legacyBlockId = compound.intValue("id")
                ?: error("$resource entry $index has no legacy id")
            val legacyData = compound.intValue("data")
                ?: error("$resource entry $index has no legacy data")
            require(runtimeId >= 0) { "$resource entry $index has negative runtimeId $runtimeId" }
            require(legacyBlockId >= 0 && legacyData in 0..legacyDataMask) {
                "$resource entry $index has invalid legacy mapping $legacyBlockId:$legacyData " +
                    "for proven DATA_BITS=$legacyDataBits and DATA_MASK=$legacyDataMask"
            }
            ProtocolEntry(
                originalIndex = index,
                originalRuntimeId = ProtocolRuntimeId(runtimeId),
                legacyBlockId = legacyBlockId,
                legacyData = legacyData,
                legacyFullId = LegacyFullId(
                    (legacyBlockId shl legacyDataBits) or (legacyData and legacyDataMask),
                ),
                stateOverload = compound.booleanValue("stateOverload"),
                canonical = canonical,
            )
        }
        require(entries.isNotEmpty()) { "$resource contains no protocol states for $protocol" }
        return ProtocolPalette(
            protocol = protocol,
            rootName = parsed.rootName,
            entries = entries,
            states = entries.map { entry ->
                ProtocolPaletteState(
                    entryIndex = entry.originalIndex,
                    protocolRuntimeId = entry.originalRuntimeId,
                    canonicalState = entry.canonical,
                    legacyFullId = entry.legacyFullId,
                    stateOverload = entry.stateOverload,
                )
            },
        )
    }

    fun buildEffectiveRegistration(
        entries: List<ProtocolPaletteState>,
    ): Map<ProtocolRuntimeId, EffectiveRuntimeMapping> {
        val map = linkedMapOf<ProtocolRuntimeId, EffectiveRuntimeMapping>()
        (entries.filter { !it.stateOverload } + entries.filter { it.stateOverload }).forEach { state ->
            map.putIfAbsent(
                state.protocolRuntimeId,
                EffectiveRuntimeMapping(
                    runtimeId = state.protocolRuntimeId,
                    canonicalState = state.canonicalState,
                    legacyFullId = state.legacyFullId,
                    sourceEntryIndex = state.entryIndex,
                ),
            )
        }
        return map
    }

    private fun buildEffectiveEntries(entries: List<ProtocolEntry>): Map<Int, ProtocolEntry> {
        val map = linkedMapOf<Int, ProtocolEntry>()
        (entries.filter { !it.stateOverload } + entries.filter { it.stateOverload }).forEach { entry ->
            map.putIfAbsent(entry.originalRuntimeId.value, entry)
        }
        return map
    }

    private fun encodePersistentPalette(
        persistent: PersistentPalette,
        reorderedBlocks: List<Map<String, Any?>>,
    ): ByteArray {
        val root = LinkedHashMap(persistent.rootCompound)
        root["blocks"] = reorderedBlocks
        val nbt = BedrockNbtWriter.writeRoot(
            rootName = persistent.rootName,
            value = root,
            byteOrder = ByteOrder.BIG_ENDIAN,
        )
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(nbt) }
            output.toByteArray()
        }
    }

    private fun createDerivedJar(
        sourceJar: File,
        destination: File,
        replacementResource: String,
        replacementBytes: ByteArray,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            ZipFile(sourceJar).use { source ->
                FileOutputStream(temporary).use { fileOutput ->
                    ZipOutputStream(fileOutput.buffered()).use { output ->
                        val seen = hashSetOf<String>()
                        source.entries().asSequence().forEach { entry ->
                            require(seen.add(entry.name)) { "Duplicate JAR entry: ${entry.name}" }
                            val copy = ZipEntry(entry.name).apply {
                                time = entry.time
                                comment = entry.comment
                                extra = entry.extra
                            }
                            output.putNextEntry(copy)
                            if (!entry.isDirectory) {
                                if (entry.name == replacementResource) {
                                    output.write(replacementBytes)
                                } else {
                                    source.getInputStream(entry).use { input -> input.copyTo(output) }
                                }
                            }
                            output.closeEntry()
                        }
                        require(replacementResource in seen) {
                            "Source JAR is missing $replacementResource"
                        }
                    }
                    try {
                        fileOutput.fd.sync()
                    } catch (_: SyncFailedException) {
                        // Some unit-test and emulated filesystems do not support
                        // fsync. The following atomic rename and SHA-256 validation
                        // still prevent a partial derived artifact from being used.
                    }
                }
            }
            require(temporary.isFile && temporary.length() > 0L) { "Derived JAR was not written" }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun rejectSignedJar(jar: ZipFile) {
        val signature = jar.entries().asSequence().firstOrNull { entry ->
            val upper = entry.name.uppercase()
            upper.startsWith("META-INF/") &&
                (upper.endsWith(".SF") || upper.endsWith(".RSA") ||
                    upper.endsWith(".DSA") || upper.endsWith(".EC"))
        }
        require(signature == null) {
            "Nukkit-MOT JAR is signed (${signature?.name}); MineHost will not mutate signed artifacts"
        }
    }

    private fun reusableCachedHash(
        metadataFile: File,
        derivedJar: File,
        baseHash: String,
        worldHash: String,
        protocol: Int,
    ): String? = runCatching {
        if (!metadataFile.isFile || !derivedJar.isFile) return@runCatching null
        val properties = Properties().apply { metadataFile.inputStream().use(::load) }
        require(properties.getProperty("baseHash") == baseHash)
        require(properties.getProperty("worldHash") == worldHash)
        require(properties.getProperty("protocol") == protocol.toString())
        require(properties.getProperty("repairerVersion") == REPAIRER_VERSION)
        val expected = properties.getProperty("derivedHash")
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            ?: return@runCatching null
        require(sha256(derivedJar) == expected)
        expected
    }.getOrNull()

    private fun writeCacheMetadata(
        metadataFile: File,
        baseHash: String,
        worldHash: String,
        protocol: Int,
        derivedHash: String,
    ) {
        val properties = Properties().apply {
            setProperty("baseHash", baseHash)
            setProperty("worldHash", worldHash)
            setProperty("protocol", protocol.toString())
            setProperty("repairerVersion", REPAIRER_VERSION)
            setProperty("derivedHash", derivedHash)
        }
        metadataFile.parentFile?.mkdirs()
        val temporary = File(metadataFile.parentFile, ".${metadataFile.name}.${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, "MineHost Nukkit-MOT derived artifact")
                output.flush()
                try {
                    output.fd.sync()
                } catch (_: SyncFailedException) {
                    // Cache metadata is validated against the derived artifact
                    // hash before reuse, so unsupported fsync is non-fatal here.
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    metadataFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    metadataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validateAlignedWorldMappings(
        report: WorldCompatibilityReport,
        inspection: EngineArtifactInspection,
        protocol: Int,
    ) {
        val protocolMap = inspection.protocolStatesByRuntimeId[protocol]
            ?: error("Derived artifact has no protocol $protocol palette")
        report.blockPalette.canonicalStates.forEach { state ->
            val semantic = CanonicalBlockStateCodec.semanticIdentity(state.blockName, state.properties)
            val persistentId = inspection.persistentRuntimeIdsByCanonicalState[state.canonicalIdentity]
                ?: inspection.persistentRuntimeIdsByCanonicalState.entries.firstOrNull {
                    CanonicalBlockStateCodec.semanticIdentityFromCanonical(it.key) == semantic
                }?.value
                ?: error("Derived artifact has no persistent state for ${formatWorldState(state)}")
            val protocolState = protocolMap[persistentId]
                ?: error(
                    "Derived artifact persistent runtime ID $persistentId for ${state.canonicalIdentity} " +
                        "has no runtime-to-legacy entry in protocol $protocol",
                )
            require(
                CanonicalBlockStateCodec.semanticIdentityFromCanonical(protocolState.canonicalIdentity) == semantic,
            ) {
                "Derived artifact persistent runtime ID $persistentId resolves to " +
                    "${protocolState.canonicalIdentity}, not ${state.canonicalIdentity}"
            }
        }
    }

    private fun requireProtocolPaletteUnchanged(
        original: EngineArtifactInspection,
        derived: EngineArtifactInspection,
        protocol: Int,
    ) {
        require(original.protocolStatesByRuntimeId[protocol] == derived.protocolStatesByRuntimeId[protocol]) {
            "Derived artifact changed protocol $protocol runtime mappings; only $PERSISTENT_RESOURCE may change"
        }
    }

    fun formatCollisionDiagnostic(collision: RuntimeMappingCollision): String = buildString {
        appendLine("=== RUNTIME MAPPING COLLISION DIAGNOSTIC ===")
        appendLine("Cause: ${collision.cause}")
        appendLine("Observed numeric ID: ${collision.runtimeId}")
        appendLine("Existing state: ${collision.existingState.canonicalIdentity}")
        appendLine("Requested state: ${collision.requestedState.canonicalIdentity}")
        appendLine("Existing namespace: ${collision.existingIdNamespace}")
        appendLine("Requested namespace: ${collision.requestedIdNamespace}")
        appendLine("World occurrence count: ${collision.worldOccurrenceCount}")
        appendLine("First world location: ${collision.firstWorldLocation ?: "unknown"}")
        collision.evidence.forEach { appendLine("Evidence: $it") }
    }

    private fun formatWorldState(state: CanonicalBlockStateObservation): String = buildString {
        append(state.canonicalIdentity)
        append(" occurrenceCount=")
        append(state.occurrenceCount)
        append(" firstLocation=dim=")
        append(state.sampleLocation.dimensionId)
        append(",chunk=")
        append(state.sampleLocation.chunkX)
        append(',')
        append(state.sampleLocation.chunkZ)
        append(",subY=")
        append(state.sampleLocation.subchunkY)
        append(",palette=")
        append(state.sampleLocation.paletteIndex)
    }

    private fun readGzipNbt(jar: ZipFile, resource: String): BedrockNbt.Parsed {
        val entry = jar.getEntry(resource)
            ?: error("Nukkit-MOT JAR is missing required resource $resource")
        require(!entry.isDirectory) { "Nukkit-MOT resource is a directory: $resource" }
        val compressed = jar.getInputStream(entry).use {
            it.readLimited(MAX_COMPRESSED_RESOURCE_BYTES, "$resource compressed payload")
        }
        require(compressed.isNotEmpty()) { "Nukkit-MOT resource is empty: $resource" }
        val bytes = GZIPInputStream(ByteArrayInputStream(compressed)).use {
            it.readLimited(MAX_DECOMPRESSED_RESOURCE_BYTES, "$resource decompressed payload")
        }
        val parsed = BedrockNbt.parse(bytes, byteOrder = ByteOrder.BIG_ENDIAN)
        require(parsed.bytesConsumed == bytes.size) {
            "$resource has ${bytes.size - parsed.bytesConsumed} trailing decompressed bytes"
        }
        return parsed
    }

    private fun java.io.InputStream.readLimited(limit: Int, label: String): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE * 4))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= limit) { "$label exceeds the $limit-byte safety limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun Any?.stringKeyMap(label: String): Map<String, Any?> {
        val source = this as? Map<*, *> ?: error("$label is not an NBT compound")
        return source.entries.associateTo(linkedMapOf()) { entry ->
            val key = entry.key as? String ?: error("$label contains a non-string key")
            key to entry.value
        }
    }

    private fun Map<String, Any?>.intValue(name: String): Int? = (this[name] as? Number)?.toInt()

    private fun Map<String, Any?>.booleanValue(name: String): Boolean = when (val value = this[name]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun sha256(file: File): String = file.inputStream().buffered().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = buffered.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_PALETTE_ENTRIES = 1_000_000
    private const val MAX_COMPRESSED_RESOURCE_BYTES = 64 * 1024 * 1024
    private const val MAX_DECOMPRESSED_RESOURCE_BYTES = 192 * 1024 * 1024
}
