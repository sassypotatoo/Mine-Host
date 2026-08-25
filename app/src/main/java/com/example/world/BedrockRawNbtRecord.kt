package com.example.world

/** One independently encoded root NBT value retained byte-for-byte. */
data class BedrockRawNbtElement(
    val rootName: String,
    val compound: Map<String, Any?>,
    val rawBytes: ByteArray,
    val sha256: String,
) {
    fun writeLossless(): ByteArray = rawBytes.copyOf()

    fun verifyLosslessRoundTrip(): Result<Unit> = runCatching {
        val written = writeLossless()
        require(rawBytes.contentEquals(written)) { "Raw NBT element changed during passthrough" }
        require(WorldFileIntegrity.sha256(written) == sha256) { "Raw NBT element hash changed during passthrough" }
        val parsed = BedrockNbt.parse(written)
        require(parsed.bytesConsumed == written.size) { "Raw NBT element has trailing bytes" }
        require(BedrockNbt.compound(parsed) == compound) { "Raw NBT element changed semantically during passthrough" }
    }
}

/**
 * Lossless NBT passthrough used when MineHost can inspect a record but has no proven
 * engine-native writer for every field. Unknown fields remain byte-for-byte intact.
 */
data class BedrockRawNbtRecord(
    val rawBytes: ByteArray,
    val rootNames: List<String>,
    val identifiers: List<String>,
    val sha256: String,
    /** Individual concatenated roots, each retaining its exact source slice. */
    val elements: List<BedrockRawNbtElement> = emptyList(),
) {
    fun writeLossless(): ByteArray = rawBytes.copyOf()

    fun verifyLosslessRoundTrip(): Result<Unit> = runCatching {
        val written = writeLossless()
        require(rawBytes.contentEquals(written)) { "Raw NBT bytes changed during passthrough" }
        require(WorldFileIntegrity.sha256(written) == sha256) { "Raw NBT hash changed during passthrough" }
        val reparsed = parseConcatenated(written)
        require(reparsed.size == elements.size) { "Raw NBT root count changed during passthrough" }
        elements.forEach { it.verifyLosslessRoundTrip().getOrThrow() }
    }

    companion object {
        fun fromConcatenated(bytes: ByteArray): BedrockRawNbtRecord {
            val parsed = parseConcatenated(bytes)
            return BedrockRawNbtRecord(
                rawBytes = bytes.copyOf(),
                rootNames = parsed.map { it.rootName },
                identifiers = parsed.mapNotNull { element ->
                    element.compound["id"] as? String ?: element.compound["identifier"] as? String
                },
                sha256 = WorldFileIntegrity.sha256(bytes),
                elements = parsed,
            )
        }

        private fun parseConcatenated(bytes: ByteArray): List<BedrockRawNbtElement> {
            var position = 0
            val result = mutableListOf<BedrockRawNbtElement>()
            while (position < bytes.size) {
                val start = position
                val parsed = BedrockNbt.parse(bytes, position)
                require(parsed.bytesConsumed > 0) { "NBT parser made no progress" }
                position += parsed.bytesConsumed
                require(position <= bytes.size) { "NBT parser consumed beyond record boundary" }
                val slice = bytes.copyOfRange(start, position)
                result += BedrockRawNbtElement(
                    rootName = parsed.rootName,
                    compound = BedrockNbt.compound(parsed),
                    rawBytes = slice,
                    sha256 = WorldFileIntegrity.sha256(slice),
                )
            }
            require(position == bytes.size) { "NBT record has trailing bytes" }
            return result
        }
    }
}

data class BedrockBlockEntityObservation(
    val dimensionId: Int,
    val owningChunkX: Int,
    val owningChunkZ: Int,
    val identifier: String?,
    val blockX: Int?,
    val blockY: Int?,
    val blockZ: Int?,
    /** Null when coordinates are absent; false means the LevelDB key and NBT position disagree. */
    val positionMatchesOwningChunk: Boolean?,
    val nbtKeys: Set<String>,
    val rawNbt: BedrockRawNbtElement,
)

data class BedrockBlockEntityRecord(
    val dimensionId: Int,
    val chunkX: Int,
    val chunkZ: Int,
    val nbt: BedrockRawNbtRecord,
    val entities: List<BedrockBlockEntityObservation> = emptyList(),
)

data class BedrockActorRecord(
    val keySha256: String,
    val identifier: String?,
    val nbt: BedrockRawNbtRecord,
)
