package com.example.world

import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Minimal deterministic NBT writer paired with [BedrockNbt].
 *
 * It is intentionally strict: mixed-type lists, null payloads and unsupported JVM values are rejected
 * instead of being guessed. Engine palette resources use big-endian NBT; Bedrock LevelDB records use
 * little-endian NBT.
 */
object BedrockNbtWriter {
    fun writeRoot(
        rootName: String,
        value: Any?,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): ByteArray {
        val output = Output(byteOrder)
        val rootType = tagType(value)
        require(rootType != TAG_END) { "NBT root cannot be TAG_End" }
        output.writeByte(rootType)
        output.writeString(rootName)
        output.writePayload(rootType, value, depth = 0)
        return output.toByteArray()
    }

    private class Output(private val byteOrder: ByteOrder) {
        private val bytes = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = bytes.toByteArray()

        fun writeByte(value: Int) {
            bytes.write(value and 0xff)
        }

        private fun writeShort(value: Int) {
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                writeByte(value)
                writeByte(value ushr 8)
            } else {
                writeByte(value ushr 8)
                writeByte(value)
            }
        }

        private fun writeInt(value: Int) {
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                writeByte(value)
                writeByte(value ushr 8)
                writeByte(value ushr 16)
                writeByte(value ushr 24)
            } else {
                writeByte(value ushr 24)
                writeByte(value ushr 16)
                writeByte(value ushr 8)
                writeByte(value)
            }
        }

        private fun writeLong(value: Long) {
            if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                writeInt(value.toInt())
                writeInt((value ushr 32).toInt())
            } else {
                writeInt((value ushr 32).toInt())
                writeInt(value.toInt())
            }
        }

        fun writeString(value: String) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            require(encoded.size <= 0xffff) { "NBT string is too long: ${encoded.size} bytes" }
            writeShort(encoded.size)
            bytes.write(encoded)
        }

        fun writePayload(type: Int, value: Any?, depth: Int) {
            require(depth <= MAX_DEPTH) { "NBT nesting exceeds $MAX_DEPTH" }
            when (type) {
                TAG_BYTE -> writeByte((value as Byte).toInt())
                TAG_SHORT -> writeShort((value as Short).toInt())
                TAG_INT -> writeInt(value as Int)
                TAG_LONG -> writeLong(value as Long)
                TAG_FLOAT -> writeInt((value as Float).toRawBits())
                TAG_DOUBLE -> writeLong((value as Double).toRawBits())
                TAG_BYTE_ARRAY -> {
                    val array = value as ByteArray
                    writeLength(array.size, "byte array")
                    bytes.write(array)
                }
                TAG_STRING -> writeString(value as String)
                TAG_LIST -> {
                    val list = value as List<*>
                    require(list.size <= MAX_COLLECTION_LENGTH) { "list is unreasonably large: ${list.size}" }
                    val elementType = list.firstOrNull()?.let(::tagType) ?: TAG_END
                    if (elementType == TAG_END) {
                        require(list.isEmpty()) { "NBT list contains null values" }
                    }
                    writeByte(elementType)
                    writeInt(list.size)
                    list.forEachIndexed { index, element ->
                        val actualType = tagType(element)
                        require(actualType == elementType) {
                            "NBT list has mixed types at index $index: expected $elementType but found $actualType"
                        }
                        writePayload(elementType, element, depth + 1)
                    }
                }
                TAG_COMPOUND -> {
                    val compound = value as Map<*, *>
                    require(compound.size <= MAX_COLLECTION_LENGTH) {
                        "NBT compound is unreasonably large: ${compound.size}"
                    }
                    compound.forEach { (rawName, child) ->
                        val name = rawName as? String ?: error("NBT compound key is not a string")
                        val childType = tagType(child)
                        require(childType != TAG_END) { "NBT compound value '$name' is null" }
                        writeByte(childType)
                        writeString(name)
                        writePayload(childType, child, depth + 1)
                    }
                    writeByte(TAG_END)
                }
                TAG_INT_ARRAY -> {
                    val array = value as IntArray
                    writeLength(array.size, "int array")
                    array.forEach(::writeInt)
                }
                TAG_LONG_ARRAY -> {
                    val array = value as LongArray
                    writeLength(array.size, "long array")
                    array.forEach(::writeLong)
                }
                else -> error("Unsupported NBT tag type $type")
            }
        }

        private fun writeLength(size: Int, kind: String) {
            require(size in 0..MAX_COLLECTION_LENGTH) { "$kind is unreasonably large: $size" }
            writeInt(size)
        }
    }

    private fun tagType(value: Any?): Int = when (value) {
        null -> TAG_END
        is Byte -> TAG_BYTE
        is Short -> TAG_SHORT
        is Int -> TAG_INT
        is Long -> TAG_LONG
        is Float -> TAG_FLOAT
        is Double -> TAG_DOUBLE
        is ByteArray -> TAG_BYTE_ARRAY
        is String -> TAG_STRING
        is List<*> -> TAG_LIST
        is Map<*, *> -> TAG_COMPOUND
        is IntArray -> TAG_INT_ARRAY
        is LongArray -> TAG_LONG_ARRAY
        else -> error("Unsupported NBT value type ${value::class.java.name}")
    }

    private const val MAX_DEPTH = 128
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
