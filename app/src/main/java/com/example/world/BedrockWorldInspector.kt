package com.example.world

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONException

private const val MAX_PACK_REFERENCE_JSON_CHARS = 1_000_000

/** Complete read-only inspection of an extracted Bedrock world. */
class BedrockWorldInspector {
    fun inspect(
        worldDirectory: File,
        onLevelDbProgress: (BedrockLevelDbProgress) -> Unit = {},
    ): Result<BedrockWorldInspectionResult> = runCatching {
        require(worldDirectory.isDirectory) { "World directory is missing" }
        val levelDat = File(worldDirectory, "level.dat")
        val dbDirectory = File(worldDirectory, "db")
        require(levelDat.isFile) { "level.dat is missing" }
        require(dbDirectory.isDirectory) { "Bedrock LevelDB directory is missing" }

        val fingerprint = WorldFileIntegrity.fingerprint(worldDirectory)
        val metadata = BedrockLevelDatReader.read(levelDat).getOrElse { error ->
            throw IllegalArgumentException("level.dat is unreadable: ${error.message}", error)
        }
        val chunkCoordinates = linkedMapOf<Int, MutableSet<Long>>()
        val serializerVersions = linkedMapOf<Int, Int>()
        val subchunkVersions = linkedMapOf<Int, Int>()
        val paletteVersions = linkedMapOf<Int, Int>()
        val blockNames = linkedMapOf<String, Int>()
        val canonicalStates = linkedMapOf<String, MutableCanonicalState>()
        val paletteErrors = mutableListOf<String>()
        val blockEntities = linkedMapOf<String, Int>()
        val actorEntities = linkedMapOf<String, Int>()
        val blockEntityRecords = mutableListOf<BedrockBlockEntityRecord>()
        val actorRecords = mutableListOf<BedrockActorRecord>()
        val unknownChunkTags = linkedMapOf<Int, Int>()
        var paletteEntries = 0L
        var localPlayerBytes: ByteArray? = null
        var localPlayer: ImportedPlayerData? = null

        val inspectionParent = worldDirectory.parentFile
            ?: error("World has no safe parent for a temporary LevelDB index")
        val temporaryIndexDirectory = File(
            inspectionParent,
            ".minehost-leveldb-index-${java.util.UUID.randomUUID()}",
        )
        val snapshot = BedrockLevelDbReader.visitLatestRecords(
            dbDirectory = dbDirectory,
            temporaryIndexDirectory = temporaryIndexDirectory,
            visitor = object : BedrockLevelDbRecordVisitor {
                override fun visit(record: BedrockLevelDbReader.Record): Boolean {
                if (record.key.contentEquals(LOCAL_PLAYER_KEY)) {
                    localPlayerBytes = record.value.copyOf()
                    localPlayer = parseLocalPlayer(record.value)
                    return true
                }

                val chunkKey = decodeChunkKey(record.key)
                if (chunkKey != null) {
                    chunkCoordinates.getOrPut(chunkKey.dimension) { linkedSetOf() }
                        .add(packChunk(chunkKey.x, chunkKey.z))
                    when (chunkKey.tag) {
                        TAG_VERSION -> {
                            record.value.firstOrNull()?.let { byte ->
                                val version = byte.toInt() and 0xff
                                serializerVersions[version] = serializerVersions.getOrDefault(version, 0) + 1
                            }
                        }
                        TAG_SUBCHUNK -> {
                            runCatching {
                                val result = parseSubchunkAndDeduplicate(record.value, chunkKey, canonicalStates)
                                subchunkVersions[result.subchunkVersion] =
                                    subchunkVersions.getOrDefault(result.subchunkVersion, 0) + 1
                                paletteEntries += result.paletteEntries
                                result.paletteVersions.forEach { version ->
                                    paletteVersions[version] = paletteVersions.getOrDefault(version, 0) + 1
                                }
                                result.blockNames.forEach { name ->
                                    blockNames[name] = blockNames.getOrDefault(name, 0) + 1
                                }
                            }.onFailure { error ->
                                paletteErrors += "dim=${chunkKey.dimension},chunk=${chunkKey.x},${chunkKey.z}," +
                                    "subY=${chunkKey.subY}: ${error.message}"
                            }
                        }
                        TAG_BLOCK_ENTITY -> runCatching {
                            val raw = BedrockRawNbtRecord.fromConcatenated(record.value)
                            raw.verifyLosslessRoundTrip().getOrThrow()
                            val observations = raw.elements.map { element ->
                                val compound = element.compound
                                val x = (compound["x"] as? Number)?.toInt()
                                val y = (compound["y"] as? Number)?.toInt()
                                val z = (compound["z"] as? Number)?.toInt()
                                val identifier = compound["id"] as? String
                                    ?: compound["identifier"] as? String
                                val positionMatches = if (x != null && z != null) {
                                    Math.floorDiv(x, 16) == chunkKey.x && Math.floorDiv(z, 16) == chunkKey.z
                                } else null
                                BedrockBlockEntityObservation(
                                    dimensionId = chunkKey.dimension,
                                    owningChunkX = chunkKey.x,
                                    owningChunkZ = chunkKey.z,
                                    identifier = identifier,
                                    blockX = x,
                                    blockY = y,
                                    blockZ = z,
                                    positionMatchesOwningChunk = positionMatches,
                                    nbtKeys = compound.keys.toSortedSet(),
                                    rawNbt = element,
                                )
                            }
                            if (blockEntityRecords.size < 100) {
                                blockEntityRecords += BedrockBlockEntityRecord(
                                    dimensionId = chunkKey.dimension,
                                    chunkX = chunkKey.x,
                                    chunkZ = chunkKey.z,
                                    nbt = raw,
                                    entities = observations,
                                )
                            }
                            observations.mapNotNull { it.identifier }.forEach { id ->
                                blockEntities[id] = blockEntities.getOrDefault(id, 0) + 1
                            }
                        }.fold(
                            onSuccess = { Unit },
                            onFailure = { error ->
                                paletteErrors += "block entities dim=${chunkKey.dimension},chunk=${chunkKey.x},${chunkKey.z}: ${error.message}"
                            },
                        )
                        else -> if (chunkKey.tag !in KNOWN_CHUNK_TAGS) {
                            unknownChunkTags[chunkKey.tag] = unknownChunkTags.getOrDefault(chunkKey.tag, 0) + 1
                        }
                    }
                    return true
                }

                if (record.key.startsWith(ACTOR_PREFIX)) {
                    runCatching {
                        val parsed = BedrockNbt.parse(record.value)
                        val compound = BedrockNbt.compound(parsed)
                        require(parsed.bytesConsumed == record.value.size) { "actor record has trailing bytes" }
                        val identifier = compound["identifier"] as? String
                            ?: compound["id"] as? String
                            ?: "<unknown>"
                        actorEntities[identifier] = actorEntities.getOrDefault(identifier, 0) + 1
                        val raw = BedrockRawNbtRecord.fromConcatenated(record.value)
                        raw.verifyLosslessRoundTrip().getOrThrow()
                        if (actorRecords.size < 100) {
                            actorRecords += BedrockActorRecord(
                                keySha256 = WorldFileIntegrity.sha256(record.key),
                                identifier = identifier,
                                nbt = raw,
                            )
                        }
                    }.onFailure { error ->
                        paletteErrors += "actor record ${record.key.toHexPrefix()}: ${error.message}"
                    }
                }
                    return true
                }

                override fun onProgress(progress: BedrockLevelDbProgress) {
                    onLevelDbProgress(progress)
                }
            },
        )

        val behaviorPacks = readPackReferences(
            File(worldDirectory, "world_behavior_packs.json"),
            File(worldDirectory, "behavior_packs"),
        )
        val resourcePacks = readPackReferences(
            File(worldDirectory, "world_resource_packs.json"),
            File(worldDirectory, "resource_packs"),
        )

        val warnings = mutableListOf<String>()
        val unmergedLevelDbLogs = snapshot.activeLogFiles.filterNot { it in snapshot.mergedLogFiles }
        if (unmergedLevelDbLogs.isNotEmpty()) {
            warnings += "Active LevelDB log files could not be merged safely: ${unmergedLevelDbLogs.joinToString()}"
        }
        if (unknownChunkTags.isNotEmpty()) {
            warnings += "Unknown chunk record types: ${unknownChunkTags.keys.sorted().joinToString()}"
        }
        if (behaviorPacks.any { !it.filesPresent } || resourcePacks.any { !it.filesPresent }) {
            warnings += "One or more referenced Bedrock packs are missing from the imported archive"
        }
        if (chunkCoordinates[DIMENSION_END].isNullOrEmpty()) {
            warnings += "No End chunks were found; End preservation cannot be proven with this fixture"
        }

        val errors = mutableListOf<String>()
        errors += snapshot.tableErrors
        val sections = linkedMapOf<String, CompatibilitySection>()
        sections["level.dat"] = CompatibilitySection(
            CompatibilityStatus.SUPPORTED,
            ConfidenceLevel.HIGH,
            "Bedrock little-endian level.dat parsed successfully",
        )
        sections["leveldb"] = when {
            snapshot.tableErrors.isNotEmpty() -> CompatibilitySection(
                CompatibilityStatus.CORRUPT,
                ConfidenceLevel.HIGH,
                "${snapshot.tableErrors.size} LevelDB table(s) could not be decoded",
            )
            unmergedLevelDbLogs.isNotEmpty() -> CompatibilitySection(
                CompatibilityStatus.UNSUPPORTED,
                ConfidenceLevel.HIGH,
                "SSTables parsed, but ${unmergedLevelDbLogs.size} active LevelDB log file(s) could not be merged safely",
            )
            else -> CompatibilitySection(
                CompatibilityStatus.SUPPORTED,
                ConfidenceLevel.HIGH,
                "${snapshot.liveRecordCount} current LevelDB records decoded from ${snapshot.selectedManifest ?: "unknown manifest"}; " +
                    "${snapshot.mergedLogFiles.size} active log file(s) merged",
            )
        }
        sections["chunks"] = if (serializerVersions.isEmpty()) {
            CompatibilitySection(CompatibilityStatus.UNKNOWN, ConfidenceLevel.LOW, "No chunk serializer records were found")
        } else {
            CompatibilitySection(
                CompatibilityStatus.SUPPORTED,
                ConfidenceLevel.HIGH,
                "Detected serializer versions ${serializerVersions.keys.sorted().joinToString()}",
            )
        }
        sections["blockPalettes"] = if (paletteErrors.isEmpty()) {
            CompatibilitySection(
                CompatibilityStatus.SUPPORTED,
                ConfidenceLevel.HIGH,
                "$paletteEntries persistent palette entries decoded",
            )
        } else {
            CompatibilitySection(
                CompatibilityStatus.PARTIALLY_SUPPORTED,
                ConfidenceLevel.HIGH,
                "${paletteErrors.size} palette/entity records could not be decoded",
            )
        }
        sections["localPlayer"] = if (localPlayer?.available == true) {
            CompatibilitySection(
                CompatibilityStatus.SUPPORTED,
                ConfidenceLevel.HIGH,
                "~local_player position, rotation, dimension and item containers decoded",
            )
        } else {
            CompatibilitySection(CompatibilityStatus.UNKNOWN, ConfidenceLevel.HIGH, "~local_player record is absent")
        }
        sections["packs"] = if ((behaviorPacks + resourcePacks).any { !it.filesPresent }) {
            CompatibilitySection(
                CompatibilityStatus.PARTIALLY_SUPPORTED,
                ConfidenceLevel.HIGH,
                "Pack references parsed, but one or more pack payloads are missing",
            )
        } else {
            CompatibilitySection(CompatibilityStatus.SUPPORTED, ConfidenceLevel.HIGH, "Pack references and payload presence inspected")
        }

        val report = WorldCompatibilityReport(
            worldName = metadata.levelName,
            sourceWorldHash = fingerprint.rootHash,
            totalFiles = fingerprint.fileCount,
            totalBytes = fingerprint.totalBytes,
            lastOpenedMinecraftVersion = metadata.lastOpenedWithVersion.takeIf { it.isNotEmpty() }?.joinToString("."),
            storageVersion = metadata.storageVersion,
            networkVersion = metadata.networkVersion,
            chunkSerializerVersions = serializerVersions.toSortedMap(),
            minimumChunkSerializerVersion = serializerVersions.keys.minOrNull(),
            maximumChunkSerializerVersion = serializerVersions.keys.maxOrNull(),
            chunkCountByDimension = chunkCoordinates.mapValues { it.value.size }.toSortedMap(),
            dimensionsPresent = chunkCoordinates.filterValues { it.isNotEmpty() }.keys.toSortedSet(),
            worldSpawn = if (metadata.spawnX != null && metadata.spawnY != null && metadata.spawnZ != null) {
                WorldCoordinates(metadata.spawnX.toDouble(), metadata.spawnY.toDouble(), metadata.spawnZ.toDouble())
            } else null,
            localPlayer = localPlayer ?: emptyLocalPlayer(),
            blockPalette = BlockPaletteInformation(
                subchunkFormatVersions = subchunkVersions.toSortedMap(),
                paletteVersions = paletteVersions.toSortedMap(),
                blockNameCounts = blockNames.toSortedMap(),
                uniqueBlockStateCount = canonicalStates.size,
                paletteEntryCount = paletteEntries,
                parseErrors = paletteErrors.toList(),
                canonicalStates = canonicalStates.values
                    .map { mutable ->
                        CanonicalBlockStateObservation(
                            canonicalIdentity = mutable.state.canonicalIdentity,
                            blockName = mutable.state.blockName,
                            properties = mutable.state.properties,
                            blockStateVersion = mutable.state.blockStateVersion,
                            originalRawNbtSha256 = mutable.rawNbtSha256,
                            sampleLocation = mutable.sampleLocation,
                            occurrenceCount = mutable.occurrenceCount,
                        )
                    }
                    .sortedBy { it.canonicalIdentity },
            ),
            blockEntityIdentifiers = blockEntities.toSortedMap(),
            actorEntityIdentifiers = actorEntities.toSortedMap(),
            behaviorPacks = behaviorPacks,
            resourcePacks = resourcePacks,
            unknownChunkRecordTypes = unknownChunkTags.toSortedMap(),
            levelDbTableErrors = snapshot.tableErrors,
            activeLevelDbLogs = snapshot.activeLogFiles,
            warnings = warnings,
            errors = errors,
            sections = sections,
            blockEntityObservations = blockEntityRecords
                .flatMap { it.entities }
                .map { observation ->
                    BlockEntityObservationSummary(
                        identifier = observation.identifier,
                        dimensionId = observation.dimensionId,
                        blockX = observation.blockX,
                        blockY = observation.blockY,
                        blockZ = observation.blockZ,
                        owningChunkX = observation.owningChunkX,
                        owningChunkZ = observation.owningChunkZ,
                        positionMatchesOwningChunk = observation.positionMatchesOwningChunk,
                        nbtKeys = observation.nbtKeys,
                        rawNbtSha256 = observation.rawNbt.sha256,
                    )
                },
            selectedLevelDbManifest = snapshot.selectedManifest,
            liveLevelDbTables = snapshot.liveTableFiles,
            ignoredLevelDbTables = snapshot.ignoredTableFiles,
            mergedLevelDbLogs = snapshot.mergedLogFiles,
        )
        BedrockWorldInspectionResult(
            report = report,
            localPlayerRecord = localPlayerBytes,
            blockEntityRecords = blockEntityRecords,
            actorRecords = actorRecords,
        )
    }

    private data class ChunkKey(val x: Int, val z: Int, val dimension: Int, val tag: Int, val subY: Int?)

    private data class ParsedSubchunkBlockState(
        val state: CanonicalBlockStateCodec.State,
        val rawNbtSha256: String,
        val location: BlockStateSourceLocation,
    )

    private data class MutableCanonicalState(
        val state: CanonicalBlockStateCodec.State,
        val rawNbtSha256: String,
        val sampleLocation: BlockStateSourceLocation,
        var occurrenceCount: Int,
    )

    private data class SubchunkResult(
        val subchunkVersion: Int,
        val paletteEntries: Long,
        val paletteVersions: List<Int>,
        val blockNames: List<String>,
    )

    private fun parseSubchunkAndDeduplicate(
        value: ByteArray,
        chunkKey: ChunkKey,
        canonicalStates: MutableMap<String, MutableCanonicalState>,
    ): SubchunkResult {
        require(value.size >= 3) { "Subchunk record is too short" }
        val subchunkVersion = value[0].toInt() and 0xff
        val storageCount = value[1].toInt() and 0xff
        var position = 3
        var entryCount = 0L
        val versions = mutableListOf<Int>()
        val names = mutableListOf<String>()

        repeat(storageCount) {
            require(position < value.size) { "Missing subchunk storage header" }
            val header = value[position++].toInt() and 0xff
            val bitsPerBlock = header ushr 1
            val words = if (bitsPerBlock == 0) {
                0
            } else {
                val blocksPerWord = 32 / bitsPerBlock
                require(blocksPerWord > 0) { "Invalid bits-per-block $bitsPerBlock" }
                (4096 + blocksPerWord - 1) / blocksPerWord
            }
            val wordBytes = words * 4
            require(position <= value.size - wordBytes) { "Subchunk bit array is truncated" }
            position += wordBytes
            val paletteLength = if (bitsPerBlock == 0) {
                1
            } else {
                require(position <= value.size - 4) { "Missing palette length" }
                readIntLE(value, position).also { position += 4 }
            }
            require(paletteLength in 0..65_536) { "Invalid palette length $paletteLength" }
            repeat(paletteLength) { paletteIndex ->
                val entryStart = position
                val parsed = BedrockNbt.parse(value, position)
                position += parsed.bytesConsumed
                val compound = BedrockNbt.compound(parsed)
                val canonical = CanonicalBlockStateCodec.fromCompound(compound)
                canonical.blockStateVersion?.let(versions::add)
                names += canonical.blockName

                val identity = canonical.canonicalIdentity
                val existing = canonicalStates[identity]
                if (existing == null) {
                    val rawNbtSha = CanonicalBlockStateCodec.sha256(value.copyOfRange(entryStart, position))
                    val location = BlockStateSourceLocation(
                        dimensionId = chunkKey.dimension,
                        chunkX = chunkKey.x,
                        chunkZ = chunkKey.z,
                        subchunkY = chunkKey.subY,
                        paletteIndex = paletteIndex,
                    )
                    canonicalStates[identity] = MutableCanonicalState(
                        state = canonical,
                        rawNbtSha256 = rawNbtSha,
                        sampleLocation = location,
                        occurrenceCount = 1,
                    )
                } else {
                    existing.occurrenceCount++
                }
                entryCount++
            }
        }
        require(position == value.size) {
            "${value.size - position} trailing subchunk bytes at ${chunkKey.x},${chunkKey.z},${chunkKey.subY}"
        }
        return SubchunkResult(subchunkVersion, entryCount, versions, names)
    }

    private fun parseLocalPlayer(bytes: ByteArray): ImportedPlayerData {
        val parsed = BedrockNbt.parse(bytes)
        require(parsed.bytesConsumed == bytes.size) { "~local_player has trailing bytes" }
        val compound = BedrockNbt.compound(parsed)
        val position = compound.numberList("Pos").takeIf { it.size >= 3 }?.let {
            WorldCoordinates(it[0], it[1], it[2])
        }
        val rotation = compound.numberList("Rotation").takeIf { it.size >= 2 }?.let {
            WorldRotation(yaw = it[0], pitch = it[1])
        }
        return ImportedPlayerData(
            available = true,
            recordSha256 = WorldFileIntegrity.sha256(bytes),
            position = position,
            rotation = rotation,
            dimensionId = compound.int("DimensionId") ?: compound.int("Dimension"),
            gameMode = compound.int("PlayerGameMode") ?: compound.int("playerGameType"),
            selectedHotbarSlot = compound.int("SelectedInventorySlot"),
            experienceLevel = compound.int("PlayerLevel"),
            experienceProgress = compound.number("PlayerLevelProgress"),
            inventory = parseItems(compound["Inventory"]),
            armor = parseItems(compound["Armor"]),
            offhand = parseItems(compound["Offhand"]),
            enderChest = parseItems(compound["EnderChestInventory"]),
            rawKeys = compound.keys.toSortedSet(),
        )
    }

    private fun parseItems(value: Any?): List<ImportedItemSummary> {
        val list = value as? List<*> ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element as? Map<*, *> ?: return@mapNotNull null
            val name = item["Name"] as? String ?: ""
            val count = (item["Count"] as? Number)?.toInt() ?: 0
            val slot = (item["Slot"] as? Number)?.toInt()
            val damage = (item["Damage"] as? Number)?.toInt()
            val tag = item["tag"] as? Map<*, *>
            ImportedItemSummary(
                slot = slot,
                name = name,
                count = count,
                damage = damage,
                hasCustomNbt = !tag.isNullOrEmpty() || item.containsKey("Block"),
                nbtKeys = tag?.keys?.mapNotNull { it as? String }?.toSortedSet().orEmpty(),
            )
        }
    }

    private fun parseConcatenatedCompounds(bytes: ByteArray): Result<List<Map<String, Any?>>> = runCatching {
        var position = 0
        val result = mutableListOf<Map<String, Any?>>()
        while (position < bytes.size) {
            val parsed = BedrockNbt.parse(bytes, position)
            require(parsed.bytesConsumed > 0) { "NBT parser made no progress" }
            result += BedrockNbt.compound(parsed)
            position += parsed.bytesConsumed
        }
        require(position == bytes.size) { "Block entity record has trailing bytes" }
        result
    }

    internal fun readPackReferences(file: File, packDirectory: File): List<PackReference> {
        if (!file.isFile) return emptyList()

        val text = file.bufferedReader().use { it.readText() }
        require(text.length <= MAX_PACK_REFERENCE_JSON_CHARS) {
            "Pack reference file is too large: ${file.name}"
        }

        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed JSON in ${file.name}: ${e.message}", e)
        }

        val results = mutableListOf<PackReference>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid pack reference at index $index in ${file.name}")

            val id = item.optString("pack_id").trim()
            if (id.isBlank()) {
                throw IllegalArgumentException("Missing pack_id at index $index in ${file.name}")
            }

            val versionArray = item.optJSONArray("version")
            val version = versionArray?.let { values ->
                (0 until values.length())
                    .joinToString(".") { values.get(it).toString() }
            } ?: item.optString("version").trim().takeIf { it.isNotEmpty() && !item.isNull("version") }

            val subpack = item.optString("subpack")
                .trim()
                .takeIf { it.isNotEmpty() && !item.isNull("subpack") }

            val filesPresent = if (!packDirectory.isDirectory) {
                false
            } else {
                packDirectory.walkTopDown()
                    .filter { it.isFile }
                    .any { candidate ->
                        candidate.path.contains(id, ignoreCase = true) ||
                            runCatching { candidate.readText().contains(id, ignoreCase = true) }.getOrDefault(false)
                    }
            }

            results += PackReference(
                packId = id,
                version = version,
                subpack = subpack,
                filesPresent = filesPresent,
            )
        }

        return results
    }

    private fun decodeChunkKey(key: ByteArray): ChunkKey? {
        return when {
            key.size in 9..10 && (key[8].toInt() and 0xff) in CHUNK_DISCRIMINATORS -> ChunkKey(
                x = readIntLE(key, 0),
                z = readIntLE(key, 4),
                dimension = DIMENSION_OVERWORLD,
                tag = key[8].toInt() and 0xff,
                subY = key.getOrNull(9)?.toInt(),
            )
            key.size in 13..14 && (key[12].toInt() and 0xff) in CHUNK_DISCRIMINATORS -> ChunkKey(
                x = readIntLE(key, 0),
                z = readIntLE(key, 4),
                dimension = readIntLE(key, 8),
                tag = key[12].toInt() and 0xff,
                subY = key.getOrNull(13)?.toInt(),
            )
            else -> null
        }
    }

    private fun packChunk(x: Int, z: Int): Long =
        (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

    private fun readIntLE(bytes: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(bytes, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int

    private fun Map<String, Any?>.string(name: String): String? = this[name] as? String
    private fun Map<String, Any?>.int(name: String): Int? = (this[name] as? Number)?.toInt()
    private fun Map<String, Any?>.number(name: String): Double? = (this[name] as? Number)?.toDouble()
    private fun Map<String, Any?>.numberList(name: String): List<Double> =
        (this[name] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }.orEmpty()

    private fun emptyLocalPlayer() = ImportedPlayerData(
        available = false,
        recordSha256 = null,
        position = null,
        rotation = null,
        dimensionId = null,
        gameMode = null,
        selectedHotbarSlot = null,
        experienceLevel = null,
        experienceProgress = null,
        inventory = emptyList(),
        armor = emptyList(),
        offhand = emptyList(),
        enderChest = emptyList(),
        rawKeys = emptySet(),
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.toHexPrefix(): String = take(16).joinToString("") { "%02x".format(it) }

    private companion object {
        val LOCAL_PLAYER_KEY = "~local_player".toByteArray(Charsets.UTF_8)
        val ACTOR_PREFIX = "actorprefix".toByteArray(Charsets.UTF_8)
        val CHUNK_DISCRIMINATORS = (43..65).toSet() + setOf(118, 119)
        val KNOWN_CHUNK_TAGS = setOf(43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59)
        const val TAG_VERSION = 44
        const val TAG_SUBCHUNK = 47
        const val TAG_BLOCK_ENTITY = 49
        const val DIMENSION_OVERWORLD = 0
        const val DIMENSION_END = 2
    }
}
