package com.example.world

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BedrockLevelDbReaderTest {
    @Test fun manifestSelectsLiveTableAndActiveLogIsMergedBySequence() {
        val root = Files.createTempDirectory("minehost-leveldb-snapshot").toFile()
        try {
            val first = internalKey("alpha", 10, 1) to "old-alpha".toByteArray()
            val second = internalKey("beta", 9, 1) to "old-beta".toByteArray()
            val liveTable = File(root, "000001.ldb")
            writeTable(liveTable, listOf(first, second))
            writeTable(File(root, "000099.ldb"), listOf(internalKey("obsolete", 100, 1) to byteArrayOf(9)))

            val manifestName = "MANIFEST-000003"
            File(root, "CURRENT").writeText("$manifestName\n")
            writeLogRecord(
                File(root, manifestName),
                versionEdit(
                    logNumber = 2,
                    tableNumber = 1,
                    tableSize = liveTable.length(),
                    smallest = first.first,
                    largest = second.first,
                ),
            )
            writeLogRecord(
                File(root, "000002.log"),
                writeBatch(
                    sequence = 20,
                    operations = listOf(
                        Write("alpha".toByteArray(), "new-alpha".toByteArray()),
                        Write("beta".toByteArray(), null),
                    ),
                ),
            )

            val snapshot = BedrockLevelDbReader.readLatest(root)
            assertEquals(manifestName, snapshot.selectedManifest)
            assertEquals(listOf("000001.ldb"), snapshot.liveTableFiles)
            assertEquals(listOf("000099.ldb"), snapshot.ignoredTableFiles)
            assertEquals(listOf("000002.log"), snapshot.activeLogFiles)
            assertEquals(listOf("000002.log"), snapshot.mergedLogFiles)
            assertTrue(snapshot.tableErrors.isEmpty())
            assertEquals(4L, snapshot.physicalEntryCount)
            assertEquals(1, snapshot.deletedLatestKeyCount)
            assertEquals(1, snapshot.liveRecords.size)
            assertArrayEquals("alpha".toByteArray(), snapshot.liveRecords.single().key)
            assertArrayEquals("new-alpha".toByteArray(), snapshot.liveRecords.single().value)
            assertEquals(20L, snapshot.liveRecords.single().sequence)
            assertEquals("000002.log", snapshot.liveRecords.single().sourceFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun diskPartitionedVisitorReturnsLatestRecordsAndCleansTemporaryIndex() {
        val root = Files.createTempDirectory("minehost-leveldb-visitor").toFile()
        val temporaryIndex = File(root.parentFile, "${root.name}-index")
        try {
            val first = internalKey("alpha", 10, 1) to "old-alpha".toByteArray()
            val second = internalKey("beta", 9, 1) to "old-beta".toByteArray()
            val liveTable = File(root, "000001.ldb")
            writeTable(liveTable, listOf(first, second))
            val manifestName = "MANIFEST-000003"
            File(root, "CURRENT").writeText("$manifestName\n")
            writeLogRecord(
                File(root, manifestName),
                versionEdit(2, 1, liveTable.length(), first.first, second.first),
            )
            writeLogRecord(
                File(root, "000002.log"),
                writeBatch(
                    sequence = 20,
                    operations = listOf(
                        Write("alpha".toByteArray(), "new-alpha".toByteArray()),
                        Write("beta".toByteArray(), null),
                    ),
                ),
            )

            val visited = mutableListOf<BedrockLevelDbReader.Record>()
            val progress = mutableListOf<BedrockLevelDbProgress>()
            val summary = BedrockLevelDbReader.visitLatestRecords(
                root,
                temporaryIndex,
                object : BedrockLevelDbRecordVisitor {
                    override fun visit(record: BedrockLevelDbReader.Record): Boolean {
                        visited += record
                        return true
                    }

                    override fun onProgress(value: BedrockLevelDbProgress) {
                        progress += value
                    }
                },
            )

            assertEquals(1L, summary.liveRecordCount)
            assertEquals(1L, summary.deletedLatestKeyCount)
            assertEquals(1, visited.size)
            assertArrayEquals("alpha".toByteArray(), visited.single().key)
            assertArrayEquals("new-alpha".toByteArray(), visited.single().value)
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.last().recordsProcessed >= summary.physicalEntryCount)
            assertTrue(!temporaryIndex.exists())
        } finally {
            root.deleteRecursively()
            temporaryIndex.deleteRecursively()
        }
    }

    @Test fun corruptedActiveLogChecksumFailsClosed() {
        val root = Files.createTempDirectory("minehost-leveldb-corrupt-log").toFile()
        try {
            val entry = internalKey("alpha", 10, 1) to "old-alpha".toByteArray()
            val table = File(root, "000001.ldb")
            writeTable(table, listOf(entry))
            val manifestName = "MANIFEST-000003"
            File(root, "CURRENT").writeText("$manifestName\n")
            writeLogRecord(
                File(root, manifestName),
                versionEdit(2, 1, table.length(), entry.first, entry.first),
            )
            val log = File(root, "000002.log")
            writeLogRecord(log, writeBatch(20, listOf(Write("alpha".toByteArray(), "new".toByteArray()))))
            val bytes = log.readBytes()
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
            log.writeBytes(bytes)

            val snapshot = BedrockLevelDbReader.readLatest(root)
            assertEquals(listOf("000002.log"), snapshot.activeLogFiles)
            assertTrue(snapshot.mergedLogFiles.isEmpty())
            assertTrue(snapshot.tableErrors.any { it.contains("checksum mismatch") })
            assertArrayEquals("old-alpha".toByteArray(), snapshot.liveRecords.single().value)
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Write(val key: ByteArray, val value: ByteArray?)

    private fun versionEdit(
        logNumber: Long,
        tableNumber: Long,
        tableSize: Long,
        smallest: ByteArray,
        largest: ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { out ->
        out.write(varint(2)); out.write(varint(logNumber))
        out.write(varint(3)); out.write(varint(tableNumber + 3))
        out.write(varint(4)); out.write(varint(10))
        out.write(varint(7)); out.write(varint(0)); out.write(varint(tableNumber)); out.write(varint(tableSize))
        out.write(slice(smallest)); out.write(slice(largest))
    }.toByteArray()

    private fun writeBatch(sequence: Long, operations: List<Write>): ByteArray = ByteArrayOutputStream().also { out ->
        out.write(longLe(sequence))
        out.write(intLe(operations.size))
        operations.forEach { operation ->
            if (operation.value == null) {
                out.write(0)
                out.write(slice(operation.key))
            } else {
                out.write(1)
                out.write(slice(operation.key))
                out.write(slice(operation.value))
            }
        }
    }.toByteArray()

    private fun writeLogRecord(file: File, payload: ByteArray) {
        val type = 1
        val checksum = mask(crc32c(byteArrayOf(type.toByte()), payload))
        file.outputStream().use { out ->
            out.write(intLe(checksum))
            out.write(byteArrayOf((payload.size and 0xff).toByte(), ((payload.size ushr 8) and 0xff).toByte()))
            out.write(type)
            out.write(payload)
        }
    }

    private fun writeTable(file: File, entries: List<Pair<ByteArray, ByteArray>>) {
        val out = ByteArrayOutputStream()
        val data = restartBlock(entries)
        val dataHandle = appendBlock(out, data)
        val metaHandle = appendBlock(out, restartBlock(emptyList()))
        val indexValue = varint(dataHandle.first.toLong()) + varint(dataHandle.second.toLong())
        val index = restartBlock(listOf(entries.last().first to indexValue))
        val indexHandle = appendBlock(out, index)
        val footer = ByteArrayOutputStream()
        footer.write(varint(metaHandle.first.toLong())); footer.write(varint(metaHandle.second.toLong()))
        footer.write(varint(indexHandle.first.toLong())); footer.write(varint(indexHandle.second.toLong()))
        while (footer.size() < 40) footer.write(0)
        footer.write(longLe(-2646017456237118633L))
        out.write(footer.toByteArray())
        file.writeBytes(out.toByteArray())
    }

    private fun appendBlock(out: ByteArrayOutputStream, payload: ByteArray): Pair<Int, Int> {
        val offset = out.size()
        val compression = 0
        out.write(payload)
        out.write(compression)
        out.write(intLe(mask(crc32c(payload, byteArrayOf(compression.toByte())))))
        return offset to payload.size
    }

    private fun restartBlock(entries: List<Pair<ByteArray, ByteArray>>): ByteArray = ByteArrayOutputStream().also { out ->
        val restarts = mutableListOf<Int>()
        entries.forEach { (key, value) ->
            restarts += out.size()
            out.write(varint(0))
            out.write(varint(key.size.toLong()))
            out.write(varint(value.size.toLong()))
            out.write(key)
            out.write(value)
        }
        if (restarts.isEmpty()) restarts += 0
        restarts.forEach { out.write(intLe(it)) }
        out.write(intLe(restarts.size))
    }.toByteArray()

    private fun internalKey(userKey: String, sequence: Long, type: Int): ByteArray =
        userKey.toByteArray() + longLe((sequence shl 8) or type.toLong())

    private fun slice(bytes: ByteArray): ByteArray = varint(bytes.size.toLong()) + bytes

    private fun varint(value: Long): ByteArray = ByteArrayOutputStream().also { out ->
        var remaining = value
        while (remaining >= 0x80) {
            out.write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
    }.toByteArray()

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte(),
    )

    private fun longLe(value: Long): ByteArray = ByteArray(8) { index -> (value ushr (index * 8)).toByte() }

    private fun crc32c(vararg parts: ByteArray): Int {
        var crc = -1
        parts.forEach { part ->
            part.forEach { byte ->
                val index = (crc xor (byte.toInt() and 0xff)) and 0xff
                crc = CRC_TABLE[index] xor (crc ushr 8)
            }
        }
        return crc.inv()
    }

    private fun mask(crc: Int): Int = ((crc ushr 15) or (crc shl 17)) - 1_568_478_504

    private val CRC_TABLE = IntArray(256).also { table ->
        for (index in table.indices) {
            var value = index
            repeat(8) { value = if ((value and 1) != 0) -2_097_792_136 xor (value ushr 1) else value ushr 1 }
            table[index] = value
        }
    }
}
