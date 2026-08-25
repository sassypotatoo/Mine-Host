package com.example.world

import java.security.MessageDigest

/** Location of one persistent palette entry in the imported Bedrock world. */
data class BlockStateSourceLocation(
    val dimensionId: Int,
    val chunkX: Int,
    val chunkZ: Int,
    val subchunkY: Int?,
    val paletteIndex: Int,
)

enum class CanonicalNbtType {
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING,
    BYTE_ARRAY,
    INT_ARRAY,
    LONG_ARRAY,
    LIST,
    COMPOUND,
    NULL,
    UNKNOWN,
}

data class CanonicalStateProperty(
    val name: String,
    val type: CanonicalNbtType,
    /** Deterministic exact representation. Floating-point values use their raw bit pattern. */
    val encodedValue: String,
)

data class CanonicalBlockStateObservation(
    val canonicalIdentity: String,
    val blockName: String,
    val properties: List<CanonicalStateProperty>,
    val blockStateVersion: Int?,
    val originalRawNbtSha256: String,
    val sampleLocation: BlockStateSourceLocation,
    val occurrenceCount: Int,
)

/** Shared canonicalization used for imported palettes and engine artifact palettes. */
object CanonicalBlockStateCodec {
    data class State(
        val canonicalIdentity: String,
        val blockName: String,
        val properties: List<CanonicalStateProperty>,
        val blockStateVersion: Int?,
    ) {
        val semanticIdentity: String get() = CanonicalBlockStateCodec.semanticIdentity(blockName, properties)
    }

    fun semanticIdentity(blockName: String, properties: List<CanonicalStateProperty>): String {
        return buildString {
            append(blockName)
            for (property in properties) {
                append('|')
                append(property.name.length)
                append(':')
                append(property.name)
                append('=')
                append(property.type.name)
                append(':')
                append(property.encodedValue.length)
                append(':')
                append(property.encodedValue)
            }
        }
    }

    fun semanticIdentityFromCanonical(canonicalIdentity: String): String {
        val versionIndex = canonicalIdentity.indexOf("|version=")
        if (versionIndex == -1) return canonicalIdentity
        val nextPipe = canonicalIdentity.indexOf('|', versionIndex + 1)
        if (nextPipe == -1) {
            return canonicalIdentity.substring(0, versionIndex)
        }
        return canonicalIdentity.substring(0, versionIndex) + canonicalIdentity.substring(nextPipe)
    }

    fun fromCompound(compound: Map<String, Any?>): State {
        val name = (compound["name"] ?: compound["Name"]) as? String
            ?: error("Block-state compound has no namespaced name")
        require(name.contains(':')) { "Block-state name is not namespaced: $name" }
        val states = (compound["states"] ?: compound["States"] ?: emptyMap<String, Any?>())
            as? Map<*, *> ?: error("Block-state 'states' is not a compound for $name")
        val properties = states.entries.map { entry ->
            val key = entry.key as? String ?: error("Block-state property key is not a string for $name")
            val encoded = encodeTyped(entry.value)
            CanonicalStateProperty(key, encoded.first, encoded.second)
        }.sortedBy { it.name }
        val version = ((compound["version"] ?: compound["Version"]) as? Number)?.toInt()
        val identity = buildString {
            append(name)
            append("|version=")
            append(version?.toString() ?: "null")
            for (property in properties) {
                append('|')
                append(property.name.length)
                append(':')
                append(property.name)
                append('=')
                append(property.type.name)
                append(':')
                append(property.encodedValue.length)
                append(':')
                append(property.encodedValue)
            }
        }
        return State(identity, name, properties, version)
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun encodeTyped(value: Any?): Pair<CanonicalNbtType, String> = when (value) {
        null -> CanonicalNbtType.NULL to ""
        is Byte -> CanonicalNbtType.BYTE to value.toString()
        is Short -> CanonicalNbtType.SHORT to value.toString()
        is Int -> CanonicalNbtType.INT to value.toString()
        is Long -> CanonicalNbtType.LONG to value.toString()
        is Float -> CanonicalNbtType.FLOAT to value.toRawBits().toUInt().toString(16)
        is Double -> CanonicalNbtType.DOUBLE to value.toRawBits().toULong().toString(16)
        is String -> CanonicalNbtType.STRING to value
        is ByteArray -> CanonicalNbtType.BYTE_ARRAY to value.joinToString("") { "%02x".format(it) }
        is IntArray -> CanonicalNbtType.INT_ARRAY to value.joinToString(",")
        is LongArray -> CanonicalNbtType.LONG_ARRAY to value.joinToString(",")
        is List<*> -> CanonicalNbtType.LIST to value.joinToString(prefix = "[", postfix = "]") {
            val item = encodeTyped(it)
            "${item.first.name}:${item.second.length}:${item.second}"
        }
        is Map<*, *> -> CanonicalNbtType.COMPOUND to value.entries
            .map { entry ->
                val key = entry.key as? String ?: error("NBT compound key is not a string")
                val item = encodeTyped(entry.value)
                Triple(key, item.first, item.second)
            }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { (key, type, encoded) ->
                "${key.length}:$key=${type.name}:${encoded.length}:$encoded"
            }
        else -> CanonicalNbtType.UNKNOWN to "${value::class.java.name}:${value}"
    }
}
