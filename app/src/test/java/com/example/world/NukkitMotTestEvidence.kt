package com.example.world

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exact-artifact lookup evidence used by synthetic Nukkit-MOT JAR fixtures. */
object NukkitMotTestEvidence {
    const val TEST_DATA_BITS = 13

    private val classes: Map<String, List<String>> = mapOf(
        "cn/nukkit/level/format/leveldb/BlockStateMapping.class" to listOf(
            "No runtime2legacy mapping for ",
            "runtimeToLegacyId",
            "runtimeToLegacyData",
        ),
        "cn/nukkit/level/format/leveldb/NukkitLegacyMapper.class" to listOf(
            "leveldb_palette.nbt",
            "registerState",
            "getLegacyFullId",
        ),
        "cn/nukkit/level/BlockPalette.class" to listOf(
            "runtime_block_states_",
            "runtimeIdToLegacy",
            "getLegacyFullId",
        ),
    )

    fun writeRuntimeLookupClasses(zip: ZipOutputStream) {
        classes.forEach { (path, strings) ->
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            zip.write(minimalConstantPoolOnlyClass(strings))
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry("cn/nukkit/block/Block.class").apply { time = 0L })
        zip.write(blockClassWithDynamicDataBits(TEST_DATA_BITS))
        zip.closeEntry()
    }

    /**
     * Existing UTF-8 tests only require a bounded constant pool and deliberately
     * do not parse the remainder of these synthetic lookup classes.
     */
    private fun minimalConstantPoolOnlyClass(strings: List<String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0xCAFEBABE.toInt())
                data.writeShort(0)
                data.writeShort(52)
                data.writeShort(strings.size + 1)
                strings.forEach { value ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 65_535)
                    data.writeByte(1)
                    data.writeShort(bytes.size)
                    data.write(bytes)
                }
            }
        }.toByteArray()

    /**
     * Minimal valid class containing:
     *
     *   public static final int DATA_BITS = Utils.dynamic(dataBits);
     *
     * The production scanner must recover the value from <clinit> bytecode,
     * not from a ConstantValue attribute.
     */
    fun blockClassWithDynamicDataBits(dataBits: Int): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(0xCAFEBABE.toInt())
                data.writeShort(0)
                data.writeShort(52)

                // Constant pool entries 1..17.
                data.writeShort(18)
                writeUtf8(data, "cn/nukkit/block/Block")        // 1
                writeClass(data, 1)                              // 2
                writeUtf8(data, "java/lang/Object")             // 3
                writeClass(data, 3)                              // 4
                writeUtf8(data, "DATA_BITS")                    // 5
                writeUtf8(data, "I")                            // 6
                writeUtf8(data, "<clinit>")                     // 7
                writeUtf8(data, "()V")                          // 8
                writeUtf8(data, "Code")                         // 9
                writeUtf8(data, "cn/nukkit/utils/Utils")        // 10
                writeClass(data, 10)                             // 11
                writeUtf8(data, "dynamic")                      // 12
                writeUtf8(data, "(I)I")                         // 13
                writeNameAndType(data, 12, 13)                   // 14
                writeMethodRef(data, 11, 14)                     // 15
                writeNameAndType(data, 5, 6)                     // 16
                writeFieldRef(data, 2, 16)                       // 17

                data.writeShort(0x0021) // public + super
                data.writeShort(2)      // this class
                data.writeShort(4)      // super class
                data.writeShort(0)      // interfaces

                data.writeShort(1)      // fields
                data.writeShort(0x0019) // public static final
                data.writeShort(5)      // DATA_BITS
                data.writeShort(6)      // I
                data.writeShort(0)      // field attributes

                data.writeShort(1)      // methods
                data.writeShort(0x0008) // static
                data.writeShort(7)      // <clinit>
                data.writeShort(8)      // ()V
                data.writeShort(1)      // attributes
                data.writeShort(9)      // Code

                val code = ByteArrayOutputStream().also { codeOut ->
                    DataOutputStream(codeOut).use { codeData ->
                        when (dataBits) {
                            in -128..127 -> {
                                codeData.writeByte(0x10) // bipush
                                codeData.writeByte(dataBits)
                            }
                            in Short.MIN_VALUE..Short.MAX_VALUE -> {
                                codeData.writeByte(0x11) // sipush
                                codeData.writeShort(dataBits)
                            }
                            else -> error("Synthetic DATA_BITS is outside supported fixture range")
                        }
                        codeData.writeByte(0xb8) // invokestatic
                        codeData.writeShort(15)
                        codeData.writeByte(0xb3) // putstatic
                        codeData.writeShort(17)
                        codeData.writeByte(0xb1) // return
                    }
                }.toByteArray()

                val codeAttributeLength = 2 + 2 + 4 + code.size + 2 + 2
                data.writeInt(codeAttributeLength)
                data.writeShort(1) // max stack
                data.writeShort(0) // max locals
                data.writeInt(code.size)
                data.write(code)
                data.writeShort(0) // exception table
                data.writeShort(0) // code attributes

                data.writeShort(0) // class attributes
            }
        }.toByteArray()

    private fun writeUtf8(data: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        data.writeByte(1)
        data.writeShort(bytes.size)
        data.write(bytes)
    }

    private fun writeClass(data: DataOutputStream, nameIndex: Int) {
        data.writeByte(7)
        data.writeShort(nameIndex)
    }

    private fun writeNameAndType(data: DataOutputStream, nameIndex: Int, descriptorIndex: Int) {
        data.writeByte(12)
        data.writeShort(nameIndex)
        data.writeShort(descriptorIndex)
    }

    private fun writeMethodRef(data: DataOutputStream, classIndex: Int, nameAndTypeIndex: Int) {
        data.writeByte(10)
        data.writeShort(classIndex)
        data.writeShort(nameAndTypeIndex)
    }

    private fun writeFieldRef(data: DataOutputStream, classIndex: Int, nameAndTypeIndex: Int) {
        data.writeByte(9)
        data.writeShort(classIndex)
        data.writeShort(nameAndTypeIndex)
    }
}
