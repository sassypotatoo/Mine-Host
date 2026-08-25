package com.example.world

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest

object FakeNukkitJar {
    fun create(file: File, modifyClass: Boolean = false, modifyHash: Boolean = false) {
        // Implementation unchanged
    }

    class FakeJarInfo(val file: File, val sha256: String)

    fun createValid(file: File): FakeJarInfo {
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("cn/nukkit/block/Block.class"))
            val classBytes = ByteArrayOutputStream().apply {
                writeInt(0xCAFEBABE.toInt()) // magic
                writeInt(0x00000034) // version 52.0
                writeShort(6) // constant pool count (1 indexed, so 5 entries)
                writeShort(1) // CONSTANT_Utf8
                writeUTF("DATA_BITS")
                writeShort(3) // CONSTANT_Integer
                writeInt(4)
                writeShort(1) // CONSTANT_Utf8
                writeUTF("ConstantValue")
                writeShort(1) // CONSTANT_Utf8
                writeUTF("<clinit>")
                writeShort(1) // CONSTANT_Utf8
                writeUTF("cn/nukkit/blockentity/BlockEntity")
                writeShort(0x0021) // access flags (public super)
                writeShort(0) // this class
                writeShort(0) // super class
                writeShort(0) // interfaces count
                writeShort(0) // fields count
                writeShort(0) // methods count
                writeShort(0) // attributes count
            }.toByteArray()
            zos.write(classBytes)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("leveldb_palette.nbt"))
            zos.write(createGzipNbt {
                compound("") {
                    list("blocks", 10) { // 10 is TAG_COMPOUND
                        compound {
                            string("name", "minecraft:air")
                            compound("states") {}
                            int("version", 17959425)
                        }
                    }
                }
            })
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("runtime_block_states_400.dat"))
            zos.write(createGzipNbt {
                list("", 10) { // 10 is TAG_COMPOUND
                    compound {
                        string("name", "minecraft:air")
                        compound("states") {}
                        int("version", 17959425)
                        int("id", 0)
                        int("data", 0)
                        int("runtimeId", 0)
                        byte("stateOverload", 0)
                    }
                }
            })
            zos.closeEntry()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        return FakeJarInfo(file, sha256)
    }

    private fun createGzipNbt(builder: NbtBuilder.() -> Unit): ByteArray {
        val nbtBuilder = NbtBuilder()
        nbtBuilder.builder()
        val nbtBytes = nbtBuilder.build()
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(nbtBytes) }
        return baos.toByteArray()
    }

    private class NbtBuilder {
        private val baos = ByteArrayOutputStream()
        
        fun compound(name: String, block: NbtBuilder.() -> Unit) {
            baos.write(10) // TAG_COMPOUND
            writeString(name)
            block()
            baos.write(0) // TAG_END
        }
        
        fun compound(block: NbtBuilder.() -> Unit) {
            block()
            baos.write(0) // TAG_END
        }

        fun list(name: String, type: Int, block: NbtBuilder.() -> Unit) {
            baos.write(9) // TAG_LIST
            writeString(name)
            baos.write(type)
            val temp = ByteArrayOutputStream()
            val childBuilder = NbtBuilder()
            childBuilder.block()
            val childBytes = childBuilder.build()
            writeInt(1) 
            baos.write(childBytes)
        }
        
        fun string(name: String, value: String) {
            baos.write(8) // TAG_STRING
            writeString(name)
            writeString(value)
        }
        
        fun int(name: String, value: Int) {
            baos.write(3) // TAG_INT
            writeString(name)
            writeInt(value)
        }
        
        fun byte(name: String, value: Byte) {
            baos.write(1) // TAG_BYTE
            writeString(name)
            baos.write(value.toInt())
        }

        private fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeShort(bytes.size)
            baos.write(bytes)
        }

        private fun writeShort(value: Int) {
            baos.write(value ushr 8)
            baos.write(value and 0xFF)
        }

        private fun writeInt(value: Int) {
            baos.write(value ushr 24)
            baos.write((value ushr 16) and 0xFF)
            baos.write((value ushr 8) and 0xFF)
            baos.write(value and 0xFF)
        }

        fun build(): ByteArray = baos.toByteArray()
    }
    
    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write(v ushr 24)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }
    private fun ByteArrayOutputStream.writeShort(v: Int) {
        write(v ushr 8)
        write(v and 0xFF)
    }
    private fun ByteArrayOutputStream.writeUTF(str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size)
        write(bytes)
    }
}
