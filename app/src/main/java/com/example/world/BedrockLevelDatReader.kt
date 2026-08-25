package com.example.world

import java.io.File
import java.nio.charset.Charset

/**
 * Read-only parser for Bedrock Edition level.dat files.
 *
 * Bedrock stores an 8-byte little-endian header followed by little-endian NBT.
 * This reader never opens or mutates the world's LevelDB database.
 */
data class BedrockLevelMetadata(
    val levelDatVersion: Int,
    val declaredPayloadLength: Int,
    val levelName: String?,
    val storageVersion: Int?,
    val networkVersion: Int?,
    val lastOpenedWithVersion: List<Int>,
    val minimumCompatibleClientVersion: List<Int>,
    val spawnX: Int?,
    val spawnY: Int?,
    val spawnZ: Int?,
    val generator: Int?,
    val randomSeed: Long?
) {
    val displayVersion: String?
        get() = lastOpenedWithVersion
            .takeIf { it.size >= 3 }
            ?.take(3)
            ?.joinToString(".")

    fun wasLastOpenedAtLeast(major: Int, minor: Int, patch: Int): Boolean {
        val actual = listOf(
            lastOpenedWithVersion.getOrElse(0) { 0 },
            lastOpenedWithVersion.getOrElse(1) { 0 },
            lastOpenedWithVersion.getOrElse(2) { 0 }
        )
        val required = listOf(major, minor, patch)
        for (index in actual.indices) {
            if (actual[index] != required[index]) return actual[index] > required[index]
        }
        return true
    }
}

object BedrockLevelDatReader {
    private val UTF8: Charset = Charsets.UTF_8

    fun read(file: File): Result<BedrockLevelMetadata> = runCatching {
        require(file.isFile) { "level.dat is missing" }
        val bytes = file.readBytes()
        require(bytes.size >= HEADER_SIZE + 3) { "level.dat is too small" }

        val header = LittleEndianCursor(bytes)
        val levelDatVersion = header.readInt()
        val declaredPayloadLength = header.readInt()
        require(declaredPayloadLength >= 0) { "level.dat declares a negative payload length" }
        require(declaredPayloadLength <= bytes.size - HEADER_SIZE) {
            "level.dat payload is truncated"
        }

        val payload = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + declaredPayloadLength)
        val nbt = LittleEndianCursor(payload)
        val rootType = nbt.readUnsignedByte()
        require(rootType == TAG_COMPOUND) { "level.dat NBT root is not a compound" }
        nbt.readString() // Root name is normally blank.
        val root = nbt.readCompoundPayload()

        BedrockLevelMetadata(
            levelDatVersion = levelDatVersion,
            declaredPayloadLength = declaredPayloadLength,
            levelName = root.string("LevelName"),
            storageVersion = root.int("StorageVersion"),
            networkVersion = root.int("NetworkVersion"),
            lastOpenedWithVersion = root.intList("lastOpenedWithVersion"),
            minimumCompatibleClientVersion = root.intList("MinimumCompatibleClientVersion"),
            spawnX = root.int("SpawnX"),
            spawnY = root.int("SpawnY"),
            spawnZ = root.int("SpawnZ"),
            generator = root.int("Generator"),
            randomSeed = root.long("RandomSeed")
        )
    }

    private fun Map<String, Any?>.string(name: String): String? = this[name] as? String

    private fun Map<String, Any?>.int(name: String): Int? = when (val value = this[name]) {
        is Byte -> value.toInt()
        is Short -> value.toInt()
        is Int -> value
        is Long -> value.toInt()
        else -> null
    }

    private fun Map<String, Any?>.long(name: String): Long? = when (val value = this[name]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun Map<String, Any?>.intList(name: String): List<Int> {
        val value = this[name]
        return when (value) {
            is IntArray -> value.toList()
            is List<*> -> value.mapNotNull {
                when (it) {
                    is Byte -> it.toInt()
                    is Short -> it.toInt()
                    is Int -> it
                    is Long -> it.toInt()
                    else -> null
                }
            }
            else -> emptyList()
        }
    }

    private class LittleEndianCursor(private val bytes: ByteArray) {
        private var position = 0

        fun readUnsignedByte(): Int {
            requireAvailable(1)
            return bytes[position++].toInt() and 0xff
        }

        private fun readByte(): Byte = readUnsignedByte().toByte()

        private fun readShort(): Short {
            requireAvailable(2)
            val value = (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8)
            position += 2
            return value.toShort()
        }

        private fun readUnsignedShort(): Int = readShort().toInt() and 0xffff

        fun readInt(): Int {
            requireAvailable(4)
            val value = (bytes[position].toInt() and 0xff) or
                ((bytes[position + 1].toInt() and 0xff) shl 8) or
                ((bytes[position + 2].toInt() and 0xff) shl 16) or
                ((bytes[position + 3].toInt() and 0xff) shl 24)
            position += 4
            return value
        }

        private fun readLong(): Long {
            val low = readInt().toLong() and 0xffffffffL
            val high = readInt().toLong() and 0xffffffffL
            return low or (high shl 32)
        }

        fun readString(): String {
            val length = readUnsignedShort()
            requireAvailable(length)
            return String(bytes, position, length, UTF8).also { position += length }
        }

        fun readCompoundPayload(): Map<String, Any?> {
            val result = linkedMapOf<String, Any?>()
            while (true) {
                val type = readUnsignedByte()
                if (type == TAG_END) return result
                val name = readString()
                result[name] = readPayload(type)
            }
        }

        private fun readPayload(type: Int): Any? = when (type) {
            TAG_BYTE -> readByte()
            TAG_SHORT -> readShort()
            TAG_INT -> readInt()
            TAG_LONG -> readLong()
            TAG_FLOAT -> Float.fromBits(readInt())
            TAG_DOUBLE -> Double.fromBits(readLong())
            TAG_BYTE_ARRAY -> {
                val length = readLength("byte array")
                requireAvailable(length)
                bytes.copyOfRange(position, position + length).also { position += length }
            }
            TAG_STRING -> readString()
            TAG_LIST -> {
                val elementType = readUnsignedByte()
                val length = readLength("list")
                List(length) { readPayload(elementType) }
            }
            TAG_COMPOUND -> readCompoundPayload()
            TAG_INT_ARRAY -> {
                val length = readLength("int array")
                IntArray(length) { readInt() }
            }
            TAG_LONG_ARRAY -> {
                val length = readLength("long array")
                LongArray(length) { readLong() }
            }
            else -> error("Unsupported NBT tag type $type")
        }

        private fun readLength(kind: String): Int {
            val length = readInt()
            require(length >= 0) { "Negative $kind length" }
            require(length <= MAX_COLLECTION_LENGTH) { "$kind is unreasonably large" }
            return length
        }

        private fun requireAvailable(count: Int) {
            require(count >= 0 && position <= bytes.size - count) { "Unexpected end of level.dat" }
        }
    }

    private const val HEADER_SIZE = 8
    private const val MAX_COLLECTION_LENGTH = 16_000_000
    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12
}
