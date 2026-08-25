package com.example.world

import java.nio.ByteOrder
import java.nio.charset.Charset

/** Read-only NBT parser. Bedrock records use little-endian; engine palette resources use big-endian. */
object BedrockNbt {
    data class Parsed(
        val rootName: String,
        val value: Any?,
        val bytesConsumed: Int,
    )

    fun parse(
        bytes: ByteArray,
        offset: Int = 0,
        byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): Parsed {
        require(offset in 0..bytes.size) { "Invalid NBT offset" }
        val cursor = Cursor(bytes, offset, byteOrder)
        val type = cursor.readUnsignedByte()
        require(type != TAG_END) { "NBT root cannot be TAG_End" }
        val name = cursor.readString()
        val value = cursor.readPayload(type, 0)
        return Parsed(name, value, cursor.position - offset)
    }

    @Suppress("UNCHECKED_CAST")
    fun compound(parsed: Parsed): Map<String, Any?> =
        parsed.value as? Map<String, Any?> ?: error("NBT root is not a compound")

    private class Cursor(
        private val bytes: ByteArray,
        start: Int,
        private val byteOrder: ByteOrder,
    ) {
        var position: Int = start
            private set

        fun readUnsignedByte(): Int {
            requireAvailable(1)
            return bytes[position++].toInt() and 0xff
        }

        private fun readByte(): Byte = readUnsignedByte().toByte()

        private fun readShort(): Short {
            requireAvailable(2)
            val first = bytes[position].toInt() and 0xff
            val second = bytes[position + 1].toInt() and 0xff
            position += 2
            val value = if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
            return value.toShort()
        }

        private fun readUnsignedShort(): Int = readShort().toInt() and 0xffff

        private fun readInt(): Int {
            requireAvailable(4)
            val a = bytes[position].toInt() and 0xff
            val b = bytes[position + 1].toInt() and 0xff
            val c = bytes[position + 2].toInt() and 0xff
            val d = bytes[position + 3].toInt() and 0xff
            position += 4
            return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                a or (b shl 8) or (c shl 16) or (d shl 24)
            } else {
                (a shl 24) or (b shl 16) or (c shl 8) or d
            }
        }

        private fun readLong(): Long {
            val first = readInt().toLong() and 0xffffffffL
            val second = readInt().toLong() and 0xffffffffL
            return if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                first or (second shl 32)
            } else {
                (first shl 32) or second
            }
        }

        private fun readFloat(): Float = Float.fromBits(readInt())
        private fun readDouble(): Double = Double.fromBits(readLong())

        fun readString(): String {
            val length = readUnsignedShort()
            requireAvailable(length)
            return String(bytes, position, length, UTF8).also { position += length }
        }

        fun readPayload(type: Int, depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "NBT nesting exceeds $MAX_DEPTH" }
            return when (type) {
                TAG_BYTE -> readByte()
                TAG_SHORT -> readShort()
                TAG_INT -> readInt()
                TAG_LONG -> readLong()
                TAG_FLOAT -> readFloat()
                TAG_DOUBLE -> readDouble()
                TAG_BYTE_ARRAY -> {
                    val length = readLength("byte array")
                    requireAvailable(length)
                    bytes.copyOfRange(position, position + length).also { position += length }
                }
                TAG_STRING -> readString()
                TAG_LIST -> {
                    val elementType = readUnsignedByte()
                    val length = readLength("list")
                    List(length) { readPayload(elementType, depth + 1) }
                }
                TAG_COMPOUND -> {
                    val result = linkedMapOf<String, Any?>()
                    while (true) {
                        val childType = readUnsignedByte()
                        if (childType == TAG_END) break
                        val childName = readString()
                        result[childName] = readPayload(childType, depth + 1)
                    }
                    result
                }
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
        }

        private fun readLength(kind: String): Int {
            val length = readInt()
            require(length >= 0) { "Negative $kind length" }
            require(length <= MAX_COLLECTION_LENGTH) { "$kind is unreasonably large: $length" }
            return length
        }

        private fun requireAvailable(count: Int) {
            require(count >= 0 && position <= bytes.size - count) { "Unexpected end of NBT" }
        }
    }

    private val UTF8: Charset = Charsets.UTF_8
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
