package com.example.world

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Bounded JVM class-file parser used only for static inspection.
 *
 * It never loads classes. Malformed entries are reported structurally instead
 * of being silently swallowed.
 */
object ClassFileUtf8Reader {
    const val MAX_CLASS_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_UTF8_BYTES = 64 * 1024
    const val MAX_CONSTANT_POOL_ENTRIES = 65_535
    private const val MAX_CODE_BYTES = 2 * 1024 * 1024

    data class ScanResult(
        val stringsByClass: Map<String, Set<String>>,
        val errors: List<String>,
        val bytesScanned: Long,
    ) {
        val allStrings: Set<String>
            get() = stringsByClass.values.flatten().toSet()
    }

    data class StaticInitializerScanResult(
        val valuesByClass: Map<String, Map<String, Int>>,
        val errors: List<String>,
        val bytesScanned: Long,
    )

    fun scanJar(
        jarFile: File,
        classNameFilter: (String) -> Boolean = { true },
    ): ScanResult {
        require(jarFile.isFile && jarFile.length() > 0L) {
            "Class scan artifact is missing or empty: ${jarFile.absolutePath}"
        }

        val stringsByClass = linkedMapOf<String, Set<String>>()
        val errors = mutableListOf<String>()
        var totalBytes = 0L

        ZipFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class") || !classNameFilter(entry.name)) {
                    continue
                }

                val bytes = readClassEntry(jar, entry.name, entry.size, errors) ?: continue
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    errors += "Class scan exceeded total limit of $MAX_TOTAL_BYTES bytes"
                    break
                }

                runCatching { readClass(bytes) }
                    .onSuccess { stringsByClass[entry.name] = it }
                    .onFailure { error ->
                        errors += "${entry.name}: ${error.message ?: error::class.java.simpleName}"
                    }
            }
        }

        return ScanResult(
            stringsByClass = stringsByClass,
            errors = errors,
            bytesScanned = totalBytes,
        )
    }

    /**
     * Reads integer values assigned to static int fields in a class initializer.
     *
     * The supported proof pattern intentionally stays narrow:
     *
     *   int constant -> optional Utils.dynamic(int) -> putstatic intField
     *
     * This is sufficient to prove Nukkit-MOT's Block.DATA_BITS without executing
     * engine code. Anything more complicated remains unresolved and fails closed.
     */
    fun scanStaticInitializerInts(
        jarFile: File,
        classNameFilter: (String) -> Boolean,
    ): StaticInitializerScanResult {
        require(jarFile.isFile && jarFile.length() > 0L) {
            "Class scan artifact is missing or empty: ${jarFile.absolutePath}"
        }

        val valuesByClass = linkedMapOf<String, Map<String, Int>>()
        val errors = mutableListOf<String>()
        var totalBytes = 0L

        ZipFile(jarFile).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".class") || !classNameFilter(entry.name)) {
                    continue
                }

                val bytes = readClassEntry(jar, entry.name, entry.size, errors) ?: continue
                totalBytes += bytes.size
                if (totalBytes > MAX_TOTAL_BYTES) {
                    errors += "Static initializer scan exceeded total limit of $MAX_TOTAL_BYTES bytes"
                    break
                }

                runCatching { readStaticInitializerInts(bytes) }
                    .onSuccess { valuesByClass[entry.name] = it }
                    .onFailure { error ->
                        errors += "${entry.name}: ${error.message ?: error::class.java.simpleName}"
                    }
            }
        }

        return StaticInitializerScanResult(
            valuesByClass = valuesByClass,
            errors = errors,
            bytesScanned = totalBytes,
        )
    }

    /** Compatibility API retained for existing callers. */
    fun extractUtf8StringsFromJar(
        jarFile: File,
        classNameFilter: (String) -> Boolean = { true },
    ): Set<String> = scanJar(jarFile, classNameFilter).allStrings

    fun extractUtf8StringsFromClassStream(input: InputStream): Set<String> =
        readClass(readBounded(input, MAX_CLASS_BYTES + 1))

    fun readClass(bytes: ByteArray): Set<String> {
        require(bytes.size <= MAX_CLASS_BYTES) {
            "Class exceeds $MAX_CLASS_BYTES bytes"
        }

        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) {
            "Invalid class-file magic"
        }

        input.readUnsignedShort() // minor
        input.readUnsignedShort() // major

        val count = input.readUnsignedShort()
        require(count in 1..MAX_CONSTANT_POOL_ENTRIES) {
            "Invalid constant-pool count $count"
        }

        val strings = linkedSetOf<String>()
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> {
                    val length = input.readUnsignedShort()
                    require(length <= MAX_UTF8_BYTES) {
                        "UTF-8 constant exceeds $MAX_UTF8_BYTES bytes"
                    }
                    val data = ByteArray(length)
                    input.readFully(data)
                    strings += data.toString(Charsets.UTF_8)
                }
                3, 4 -> skipFully(input, 4)
                5, 6 -> {
                    skipFully(input, 8)
                    index++
                }
                7, 8, 16, 19, 20 -> skipFully(input, 2)
                9, 10, 11, 12, 17, 18 -> skipFully(input, 4)
                15 -> skipFully(input, 3)
                else -> error("Unsupported constant-pool tag $tag")
            }
            index++
        }
        return strings
    }

    fun readStaticInitializerInts(bytes: ByteArray): Map<String, Int> {
        require(bytes.size <= MAX_CLASS_BYTES) {
            "Class exceeds $MAX_CLASS_BYTES bytes"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid class-file magic" }
        input.readUnsignedShort()
        input.readUnsignedShort()

        val pool = readConstantPool(input)
        input.readUnsignedShort() // access
        val thisClassIndex = input.readUnsignedShort()
        input.readUnsignedShort() // super
        val currentClass = resolveClassName(pool, thisClassIndex)

        repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
        repeat(input.readUnsignedShort()) { skipMember(input) }

        val assignments = linkedMapOf<String, Int>()
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort() // method access
            val methodName = utf8(pool, input.readUnsignedShort())
            input.readUnsignedShort() // descriptor
            val attributeCount = input.readUnsignedShort()
            repeat(attributeCount) {
                val attributeName = utf8(pool, input.readUnsignedShort())
                val attributeLength = readUnsignedInt(input)
                require(attributeLength <= bytes.size.toLong()) { "Method attribute is too large" }
                val attributeBytes = ByteArray(attributeLength.toInt())
                input.readFully(attributeBytes)
                if (methodName == "<clinit>" && attributeName == "Code") {
                    assignments += readInitializerCode(attributeBytes, pool, currentClass)
                }
            }
        }
        return assignments
    }

    private sealed interface CpEntry
    private data class CpUtf8(val value: String) : CpEntry
    private data class CpInteger(val value: Int) : CpEntry
    private data class CpClass(val nameIndex: Int) : CpEntry
    private data class CpRef(val classIndex: Int, val nameAndTypeIndex: Int) : CpEntry
    private data class CpNameAndType(val nameIndex: Int, val descriptorIndex: Int) : CpEntry
    private data object CpOther : CpEntry

    private data class MemberReference(
        val owner: String,
        val name: String,
        val descriptor: String,
    )

    private fun readConstantPool(input: DataInputStream): Array<CpEntry?> {
        val count = input.readUnsignedShort()
        require(count in 1..MAX_CONSTANT_POOL_ENTRIES) {
            "Invalid constant-pool count $count"
        }
        val pool = arrayOfNulls<CpEntry>(count)
        var index = 1
        while (index < count) {
            pool[index] = when (val tag = input.readUnsignedByte()) {
                1 -> {
                    val length = input.readUnsignedShort()
                    require(length <= MAX_UTF8_BYTES) {
                        "UTF-8 constant exceeds $MAX_UTF8_BYTES bytes"
                    }
                    val data = ByteArray(length)
                    input.readFully(data)
                    CpUtf8(data.toString(Charsets.UTF_8))
                }
                3 -> CpInteger(input.readInt())
                4 -> {
                    input.readInt()
                    CpOther
                }
                5, 6 -> {
                    input.readLong()
                    index++
                    CpOther
                }
                7 -> CpClass(input.readUnsignedShort())
                8, 16, 19, 20 -> {
                    input.readUnsignedShort()
                    CpOther
                }
                9, 10, 11 -> CpRef(input.readUnsignedShort(), input.readUnsignedShort())
                12 -> CpNameAndType(input.readUnsignedShort(), input.readUnsignedShort())
                15 -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    CpOther
                }
                17, 18 -> {
                    input.readUnsignedShort()
                    input.readUnsignedShort()
                    CpOther
                }
                else -> error("Unsupported constant-pool tag $tag")
            }
            index++
        }
        return pool
    }

    private fun readInitializerCode(
        attributeBytes: ByteArray,
        pool: Array<CpEntry?>,
        currentClass: String,
    ): Map<String, Int> {
        val input = DataInputStream(ByteArrayInputStream(attributeBytes))
        input.readUnsignedShort() // max stack
        input.readUnsignedShort() // max locals
        val codeLength = input.readInt()
        require(codeLength in 0..MAX_CODE_BYTES) { "Initializer bytecode exceeds limit" }
        val code = ByteArray(codeLength)
        input.readFully(code)

        val assignments = linkedMapOf<String, Int>()
        var pc = 0
        var lastInt: Int? = null
        while (pc < code.size) {
            val opcode = u1(code, pc)
            when (opcode) {
                0x02 -> lastInt = -1
                in 0x03..0x08 -> lastInt = opcode - 0x03
                0x10 -> lastInt = code.getOrNull(pc + 1)?.toInt()
                0x11 -> lastInt = signedShort(code, pc + 1)
                0x12 -> lastInt = (pool.getOrNull(u1(code, pc + 1)) as? CpInteger)?.value
                0x13 -> lastInt = (pool.getOrNull(u2(code, pc + 1)) as? CpInteger)?.value
                0xb8 -> {
                    val method = resolveMember(pool, u2(code, pc + 1))
                    if (method?.owner != "cn/nukkit/utils/Utils" ||
                        method.name != "dynamic" || method.descriptor != "(I)I"
                    ) {
                        lastInt = null
                    }
                }
                0xb3 -> {
                    val field = resolveMember(pool, u2(code, pc + 1))
                    if (field != null && field.owner == currentClass && field.descriptor == "I" && lastInt != null) {
                        assignments[field.name] = lastInt
                    }
                    lastInt = null
                }
                0x00, 0x59 -> Unit // nop / dup; preserve the narrow proof value
                else -> lastInt = null
            }
            pc += instructionLength(code, pc)
        }
        return assignments
    }

    private fun instructionLength(code: ByteArray, pc: Int): Int {
        val opcode = u1(code, pc)
        val fixed = when (opcode) {
            0x10, 0x12, in 0x15..0x19, in 0x36..0x3a, 0xa9, 0xbc -> 2
            0x11, 0x13, 0x14, 0x84,
            in 0x99..0xa8, in 0xb2..0xb8,
            0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3
            0xc5 -> 4
            0xb9, 0xba, 0xc8, 0xc9 -> 5
            0xaa -> {
                val padding = (4 - ((pc + 1) and 3)) and 3
                val base = pc + 1 + padding
                require(base + 12 <= code.size) { "Truncated tableswitch" }
                val low = signedInt(code, base + 4)
                val high = signedInt(code, base + 8)
                val count = high.toLong() - low.toLong() + 1L
                require(count in 0..100_000) { "Invalid tableswitch range" }
                1 + padding + 12 + count.toInt() * 4
            }
            0xab -> {
                val padding = (4 - ((pc + 1) and 3)) and 3
                val base = pc + 1 + padding
                require(base + 8 <= code.size) { "Truncated lookupswitch" }
                val pairs = signedInt(code, base + 4)
                require(pairs in 0..100_000) { "Invalid lookupswitch pair count" }
                1 + padding + 8 + pairs * 8
            }
            0xc4 -> {
                require(pc + 1 < code.size) { "Truncated wide instruction" }
                if (u1(code, pc + 1) == 0x84) 6 else 4
            }
            else -> 1
        }
        require(fixed > 0 && pc + fixed <= code.size) {
            "Truncated bytecode instruction 0x${opcode.toString(16)} at $pc"
        }
        return fixed
    }

    private fun resolveMember(pool: Array<CpEntry?>, index: Int): MemberReference? {
        val ref = pool.getOrNull(index) as? CpRef ?: return null
        val owner = resolveClassName(pool, ref.classIndex)
        val nameAndType = pool.getOrNull(ref.nameAndTypeIndex) as? CpNameAndType ?: return null
        return MemberReference(
            owner = owner,
            name = utf8(pool, nameAndType.nameIndex),
            descriptor = utf8(pool, nameAndType.descriptorIndex),
        )
    }

    private fun resolveClassName(pool: Array<CpEntry?>, index: Int): String {
        val classRef = pool.getOrNull(index) as? CpClass
            ?: error("Invalid class constant #$index")
        return utf8(pool, classRef.nameIndex)
    }

    private fun utf8(pool: Array<CpEntry?>, index: Int): String =
        (pool.getOrNull(index) as? CpUtf8)?.value
            ?: error("Invalid UTF-8 constant #$index")

    private fun skipMember(input: DataInputStream) {
        input.readUnsignedShort()
        input.readUnsignedShort()
        input.readUnsignedShort()
        repeat(input.readUnsignedShort()) {
            input.readUnsignedShort()
            val length = readUnsignedInt(input)
            require(length <= Int.MAX_VALUE) { "Member attribute is too large" }
            skipFully(input, length.toInt())
        }
    }

    private fun readClassEntry(
        jar: ZipFile,
        entryName: String,
        declaredSize: Long,
        errors: MutableList<String>,
    ): ByteArray? {
        if (declaredSize > MAX_CLASS_BYTES) {
            errors += "$entryName: declared class size $declaredSize exceeds $MAX_CLASS_BYTES"
            return null
        }
        val entry = jar.getEntry(entryName) ?: return null
        val bytes = jar.getInputStream(entry).use { input ->
            readBounded(input, MAX_CLASS_BYTES + 1)
        }
        if (bytes.size > MAX_CLASS_BYTES) {
            errors += "$entryName: class exceeds $MAX_CLASS_BYTES bytes"
            return null
        }
        return bytes
    }

    private fun readUnsignedInt(input: DataInputStream): Long = input.readInt().toLong() and 0xffffffffL

    private fun u1(bytes: ByteArray, offset: Int): Int {
        require(offset in bytes.indices) { "Unexpected end of bytecode" }
        return bytes[offset].toInt() and 0xff
    }

    private fun u2(bytes: ByteArray, offset: Int): Int =
        (u1(bytes, offset) shl 8) or u1(bytes, offset + 1)

    private fun signedShort(bytes: ByteArray, offset: Int): Int {
        val value = u2(bytes, offset)
        return if (value and 0x8000 != 0) value - 0x10000 else value
    }

    private fun signedInt(bytes: ByteArray, offset: Int): Int =
        (u1(bytes, offset) shl 24) or
            (u1(bytes, offset + 1) shl 16) or
            (u1(bytes, offset + 2) shl 8) or
            u1(bytes, offset + 3)

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            require(total <= limit) { "Input exceeds $limit bytes" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun skipFully(input: DataInputStream, byteCount: Int) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            require(skipped > 0) { "Unexpected end of class file" }
            remaining -= skipped
        }
    }
}
