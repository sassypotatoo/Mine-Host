package com.example.world

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class ClassFileUtf8ReaderTest {

    @Test
    fun testClassFileUtf8Reader_extractsStrings() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeInt(0xCAFEBABE.toInt()) // magic
        dos.writeShort(0) // minor
        dos.writeShort(52) // major
        dos.writeShort(3) // constant pool count (1-indexed, so 2 entries)

        // Entry 1: Utf8
        dos.writeByte(1)
        val testStr = "BlockEntitySporeBlossom"
        dos.writeShort(testStr.length)
        dos.writeBytes(testStr)

        // Entry 2: Utf8
        dos.writeByte(1)
        val testStr2 = "BlockEntityVault"
        dos.writeShort(testStr2.length)
        dos.writeBytes(testStr2)

        val classBytes = baos.toByteArray()
        val extracted = ClassFileUtf8Reader.extractUtf8StringsFromClassStream(classBytes.inputStream())

        assertTrue(extracted.contains("BlockEntitySporeBlossom"))
        assertTrue(extracted.contains("BlockEntityVault"))
    }
    @Test
    fun staticInitializerReaderProvesDynamicDataBits() {
        val values = ClassFileUtf8Reader.readStaticInitializerInts(
            NukkitMotTestEvidence.blockClassWithDynamicDataBits(13),
        )

        org.junit.Assert.assertEquals(13, values["DATA_BITS"])
    }

}
