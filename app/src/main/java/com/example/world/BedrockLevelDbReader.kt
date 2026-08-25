package com.example.world

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Read-only Bedrock LevelDB snapshot reconstruction.
 *
 * CURRENT selects the active MANIFEST. The MANIFEST selects live SSTables and active logs.
 * Internal sequence numbers and deletion records are then applied across both sources.
 * The database is never opened through a native LevelDB implementation, compacted, or repaired.
 */
object BedrockLevelDbReader {
    data class Record(
        val key: ByteArray,
        val value: ByteArray,
        val sequence: Long,
        val sourceFile: String,
    )

    data class Snapshot(
        val liveRecords: List<Record>,
        val physicalEntryCount: Long,
        val deletedLatestKeyCount: Int,
        val tableFiles: Int,
        val tableErrors: List<String>,
        val activeLogFiles: List<String>,
        val compressionTypes: Map<Int, Int>,
        val selectedManifest: String? = null,
        val liveTableFiles: List<String> = emptyList(),
        val ignoredTableFiles: List<String> = emptyList(),
        val mergedLogFiles: List<String> = emptyList(),
    )

    /** Metadata returned by the disk-partitioned snapshot visitor. */
    data class SnapshotSummary(
        val liveRecordCount: Long,
        val physicalEntryCount: Long,
        val deletedLatestKeyCount: Long,
        val tableFiles: Int,
        val tableErrors: List<String>,
        val activeLogFiles: List<String>,
        val compressionTypes: Map<Int, Int>,
        val selectedManifest: String? = null,
        val liveTableFiles: List<String> = emptyList(),
        val ignoredTableFiles: List<String> = emptyList(),
        val mergedLogFiles: List<String> = emptyList(),
    )

    /**
     * Visits the latest live records using a temporary disk-partitioned index.
     * The whole database's values are never retained in Android heap at once.
     */
    fun visitLatestRecords(
        dbDirectory: File,
        temporaryIndexDirectory: File,
        visitor: BedrockLevelDbRecordVisitor,
    ): SnapshotSummary {
        require(dbDirectory.isDirectory) { "Bedrock db directory is missing" }
        val dbCanonical = dbDirectory.canonicalFile
        val tempCanonical = temporaryIndexDirectory.canonicalFile
        require(!tempCanonical.toPath().startsWith(dbCanonical.toPath())) {
            "Temporary LevelDB index must not be created inside the imported world database"
        }
        if (tempCanonical.exists()) tempCanonical.deleteRecursively()
        require(tempCanonical.mkdirs()) { "Unable to create temporary LevelDB index" }

        val errors = mutableListOf<String>()
        val compression = linkedMapOf<Int, Int>()
        var physical = 0L
        var selectedManifest: String? = null
        var liveTableNumbers = emptySet<Long>()
        var logNumber: Long? = null
        var previousLogNumber: Long? = null

        val allTables = dbDirectory.listFiles().orEmpty()
            .filter { it.isFile && (it.extension.equals("ldb", true) || it.extension.equals("sst", true)) }
            .sortedBy { it.name }

        runCatching {
            val current = File(dbDirectory, "CURRENT")
            require(current.isFile) { "CURRENT is missing" }
            val manifestName = current.readText(Charsets.UTF_8).trim()
            require(MANIFEST_NAME.matches(manifestName)) {
                "CURRENT contains an unsafe or invalid manifest name: $manifestName"
            }
            val manifest = File(dbDirectory, manifestName)
            require(manifest.isFile) { "Selected manifest is missing: $manifestName" }
            selectedManifest = manifestName
            val state = readManifest(manifest)
            liveTableNumbers = state.liveTables
            logNumber = state.logNumber
            previousLogNumber = state.previousLogNumber
            // Newly created or empty worlds might have no SSTables yet, only logs.
            // Do not treat empty table set as a fatal manifest error during adoption.
        }.onFailure { error ->
            errors += "manifest: ${error.message ?: error::class.java.simpleName}"
        }

        val selectedTables = if (errors.none { it.startsWith("manifest:") }) {
            liveTableNumbers.mapNotNull { number ->
                val candidates = listOf(
                    File(dbDirectory, "%06d.ldb".format(number)),
                    File(dbDirectory, "%06d.sst".format(number)),
                )
                candidates.firstOrNull { it.isFile } ?: run {
                    errors += "manifest: live table $number is missing"
                    null
                }
            }.sortedBy { it.name }
        } else {
            emptyList()
        }
        val selectedNames = selectedTables.map { it.name }.toSet()
        val ignoredTables = allTables.map { it.name }.filterNot { it in selectedNames }.sorted()

        val allLogsByNumber = dbDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("log", true) && it.length() > 0L }
            .mapNotNull { file -> file.nameWithoutExtension.toLongOrNull()?.let { it to file } }
            .toMap()
        val activeLogNumbers = linkedSetOf<Long>().apply {
            previousLogNumber?.takeIf { it > 0L }?.let(::add)
            logNumber?.takeIf { it > 0L }?.let { current ->
                allLogsByNumber.keys.filter { it >= current }.sorted().forEach(::add)
            }
        }
        val activeLogs = activeLogNumbers.mapNotNull(allLogsByNumber::get).sortedBy { it.name }
        val totalSourceBytes = (selectedTables + activeLogs).sumOf(File::length)
        var sourceBytesProcessed = 0L
        var sourceFilesProcessed = 0
        val mergedLogs = mutableListOf<String>()
        val store = DiskBucketStore(tempCanonical)

        try {
            for (table in selectedTables) {
                checkCancellation()
                try {
                    readTable(table, compression) { entry ->
                        physical++
                        store.append(decodeInternalEntry(entry.key, entry.value, table.name))
                    }
                } catch (error: Throwable) {
                    errors += "${table.name}: ${error.message ?: error::class.java.simpleName}"
                }
                sourceBytesProcessed += table.length()
                sourceFilesProcessed++
                visitor.onProgress(
                    BedrockLevelDbProgress(
                        filesProcessed = sourceFilesProcessed,
                        totalFiles = selectedTables.size + activeLogs.size,
                        bytesProcessed = sourceBytesProcessed,
                        totalBytes = totalSourceBytes,
                        recordsProcessed = physical,
                        currentFile = table.name,
                    )
                )
            }

            for (log in activeLogs) {
                checkCancellation()
                runCatching {
                    var operations = 0L
                    forEachPhysicalLogRecord(log) { batch ->
                        checkCancellation()
                        operations += forEachWriteBatchOperation(batch, log.name) { write ->
                            store.append(
                                InternalRecord(
                                    key = write.key,
                                    value = write.value ?: ByteArray(0),
                                    sequence = write.sequence,
                                    valueType = write.valueType,
                                    sourceFile = log.name,
                                )
                            )
                        }
                    }
                    physical += operations
                    mergedLogs += log.name
                }.onFailure { error ->
                    errors += "${log.name}: ${error.message ?: error::class.java.simpleName}"
                }
                sourceBytesProcessed += log.length()
                sourceFilesProcessed++
                visitor.onProgress(
                    BedrockLevelDbProgress(
                        filesProcessed = sourceFilesProcessed,
                        totalFiles = selectedTables.size + activeLogs.size,
                        bytesProcessed = sourceBytesProcessed,
                        totalBytes = totalSourceBytes,
                        recordsProcessed = physical,
                        currentFile = log.name,
                    )
                )
            }

            val counts = store.visitLatest(
                visitor = visitor,
                baseProgress = BedrockLevelDbProgress(
                    filesProcessed = sourceFilesProcessed,
                    totalFiles = sourceFilesProcessed + store.nonEmptyBucketCount(),
                    bytesProcessed = sourceBytesProcessed,
                    totalBytes = totalSourceBytes,
                    recordsProcessed = physical,
                    currentFile = null,
                ),
            )

            return SnapshotSummary(
                liveRecordCount = counts.live,
                physicalEntryCount = physical,
                deletedLatestKeyCount = counts.deleted,
                tableFiles = selectedTables.size,
                tableErrors = errors,
                activeLogFiles = activeLogs.map { it.name },
                compressionTypes = compression.toMap(),
                selectedManifest = selectedManifest,
                liveTableFiles = selectedTables.map { it.name },
                ignoredTableFiles = ignoredTables,
                mergedLogFiles = mergedLogs,
            )
        } finally {
            store.close()
            tempCanonical.deleteRecursively()
        }
    }

    fun visitLatest(dbDirectory: File, visitor: BedrockLevelDbRecordVisitor) {
        val parent = dbDirectory.parentFile?.parentFile ?: dbDirectory.parentFile
            ?: error("Bedrock database has no safe parent for a temporary index")
        val temporary = File(parent, ".minehost-leveldb-index-${UUID.randomUUID()}")
        visitLatestRecords(dbDirectory, temporary, visitor)
    }

    fun readLatest(dbDirectory: File): Snapshot {
        require(dbDirectory.isDirectory) { "Bedrock db directory is missing" }
        val errors = mutableListOf<String>()
        val compression = linkedMapOf<Int, Int>()
        val latest = linkedMapOf<ByteKey, InternalRecord>()
        var physical = 0L
        var retainedValueBytes = 0L

        val allTables = dbDirectory.listFiles().orEmpty()
            .filter { it.isFile && (it.extension.equals("ldb", true) || it.extension.equals("sst", true)) }
            .sortedBy { it.name }

        var selectedManifest: String? = null
        var liveTableNumbers = emptySet<Long>()
        var logNumber: Long? = null
        var previousLogNumber: Long? = null

        runCatching {
            val current = File(dbDirectory, "CURRENT")
            require(current.isFile) { "CURRENT is missing" }
            val manifestName = current.readText(Charsets.UTF_8).trim()
            require(MANIFEST_NAME.matches(manifestName)) { "CURRENT contains an unsafe or invalid manifest name: $manifestName" }
            val manifest = File(dbDirectory, manifestName)
            require(manifest.isFile) { "Selected manifest is missing: $manifestName" }
            selectedManifest = manifestName
            val state = readManifest(manifest)
            liveTableNumbers = state.liveTables
            logNumber = state.logNumber
            previousLogNumber = state.previousLogNumber
            // Newly created or empty worlds might have no SSTables yet, only logs.
        }.onFailure { error ->
            errors += "manifest: ${error.message ?: error::class.java.simpleName}"
        }

        val selectedTables = if (errors.none { it.startsWith("manifest:") }) {
            liveTableNumbers.mapNotNull { number ->
                val candidates = listOf(
                    File(dbDirectory, "%06d.ldb".format(number)),
                    File(dbDirectory, "%06d.sst".format(number)),
                )
                candidates.firstOrNull { it.isFile } ?: run {
                    errors += "manifest: live table $number is missing"
                    null
                }
            }.sortedBy { it.name }
        } else {
            emptyList()
        }
        val selectedNames = selectedTables.map { it.name }.toSet()
        val ignoredTables = allTables.map { it.name }.filterNot { it in selectedNames }.sorted()

        for (table in selectedTables) {
            try {
                readTable(table, compression) { entry ->
                    physical++
                    retainedValueBytes += applyInternalEntry(
                        latest,
                        entry.key,
                        entry.value,
                        table.name,
                    )
                    enforceSnapshotMemoryBounds(latest.size, retainedValueBytes)
                }
            } catch (error: Throwable) {
                errors += "${table.name}: ${error.message ?: error::class.java.simpleName}"
            }
        }

        val allLogsByNumber = dbDirectory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("log", true) && it.length() > 0L }
            .mapNotNull { file -> file.nameWithoutExtension.toLongOrNull()?.let { it to file } }
            .toMap()
        val activeLogNumbers = linkedSetOf<Long>().apply {
            previousLogNumber?.takeIf { it > 0L }?.let(::add)
            logNumber?.takeIf { it > 0L }?.let { current ->
                allLogsByNumber.keys.filter { it >= current }.sorted().forEach(::add)
            }
        }
        val activeLogs = activeLogNumbers.mapNotNull(allLogsByNumber::get).sortedBy { it.name }
        val mergedLogs = mutableListOf<String>()
        for (log in activeLogs) {
            runCatching {
                var operations = 0
                forEachPhysicalLogRecord(log) { batch ->
                    operations += forEachWriteBatchOperation(batch, log.name) { write ->
                        val key = ByteKey(write.key)
                        val previous = latest[key]
                        if (previous == null || write.sequence > previous.sequence) {
                            val newValue = write.value ?: ByteArray(0)
                            latest[key] = InternalRecord(
                                key = write.key,
                                value = newValue,
                                sequence = write.sequence,
                                valueType = write.valueType,
                                sourceFile = log.name,
                            )
                            retainedValueBytes += newValue.size - (previous?.value?.size ?: 0)
                            enforceSnapshotMemoryBounds(latest.size, retainedValueBytes)
                        }
                    }
                }
                physical += operations
                mergedLogs += log.name
            }.onFailure { error ->
                errors += "${log.name}: ${error.message ?: error::class.java.simpleName}"
            }
        }

        val live = latest.values.asSequence()
            .filter { it.valueType == VALUE_TYPE }
            .map { Record(it.key, it.value, it.sequence, it.sourceFile) }
            .toList()
        val deleted = latest.values.count { it.valueType == DELETION_TYPE }
        return Snapshot(
            liveRecords = live,
            physicalEntryCount = physical,
            deletedLatestKeyCount = deleted,
            tableFiles = selectedTables.size,
            tableErrors = errors,
            activeLogFiles = activeLogs.map { it.name },
            compressionTypes = compression.toMap(),
            selectedManifest = selectedManifest,
            liveTableFiles = selectedTables.map { it.name },
            ignoredTableFiles = ignoredTables,
            mergedLogFiles = mergedLogs,
        )
    }


    private fun decodeInternalEntry(
        internalKey: ByteArray,
        value: ByteArray,
        sourceFile: String,
    ): InternalRecord {
        require(internalKey.size >= INTERNAL_TRAILER_BYTES) {
            "$sourceFile contains a truncated internal key"
        }
        val trailer = readLongLE(internalKey, internalKey.size - INTERNAL_TRAILER_BYTES)
        val sequence = trailer ushr 8
        val valueType = (trailer and 0xffL).toInt()
        require(valueType == VALUE_TYPE || valueType == DELETION_TYPE) {
            "$sourceFile contains unsupported internal value type $valueType"
        }
        return InternalRecord(
            key = internalKey.copyOf(internalKey.size - INTERNAL_TRAILER_BYTES),
            value = value,
            sequence = sequence,
            valueType = valueType,
            sourceFile = sourceFile,
        )
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("LevelDB inspection was cancelled")
        }
    }

    private data class BucketVisitCounts(val live: Long, val deleted: Long)

    private class DiskBucketStore(private val root: File) : AutoCloseable {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val openOutputs = object : LinkedHashMap<Int, DataOutputStream>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, DataOutputStream>?,
            ): Boolean {
                val remove = size > MAX_OPEN_BUCKET_STREAMS
                if (remove) eldest?.value?.close()
                return remove
            }
        }
        private val nonEmpty = linkedSetOf<Int>()
        private var closed = false

        fun append(record: InternalRecord) {
            checkCancellation()
            require(record.key.size <= MAX_LEVELDB_KEY_BYTES) {
                "LevelDB key exceeds the $MAX_LEVELDB_KEY_BYTES-byte safety limit"
            }
            require(record.value.size <= MAX_LIVE_RECORD_VALUE_BYTES) {
                "LevelDB value exceeds the $MAX_LIVE_RECORD_VALUE_BYTES-byte safety limit"
            }
            val source = record.sourceFile.toByteArray(Charsets.UTF_8)
            require(source.size <= MAX_SOURCE_NAME_BYTES) { "LevelDB source name is too long" }
            val bucket = bucketFor(record.key)
            val output = openOutputs[bucket] ?: DataOutputStream(
                BufferedOutputStream(FileOutputStream(bucketFile(bucket), true), BUCKET_IO_BUFFER_BYTES)
            ).also { openOutputs[bucket] = it }
            output.writeInt(record.key.size)
            output.writeInt(record.value.size)
            output.writeLong(record.sequence)
            output.writeByte(record.valueType)
            output.writeShort(source.size)
            output.write(source)
            output.write(record.key)
            output.write(record.value)
            nonEmpty += bucket
        }

        fun nonEmptyBucketCount(): Int = nonEmpty.size

        fun visitLatest(
            visitor: BedrockLevelDbRecordVisitor,
            baseProgress: BedrockLevelDbProgress,
        ): BucketVisitCounts {
            closeOutputs()
            var live = 0L
            var deleted = 0L
            var bucketsProcessed = 0
            var emittedRecords = 0L
            val buckets = nonEmpty.sorted()
            for (bucket in buckets) {
                checkCancellation()
                val file = bucketFile(bucket)
                val latest = linkedMapOf<ByteKey, InternalRecord>()
                var retainedValueBytes = 0L
                var retainedKeyBytes = 0L
                DataInputStream(BufferedInputStream(FileInputStream(file), BUCKET_IO_BUFFER_BYTES)).use { input ->
                    while (true) {
                        val keyLength = try {
                            input.readInt()
                        } catch (_: EOFException) {
                            break
                        }
                        val valueLength = input.readInt()
                        val sequence = input.readLong()
                        val valueType = input.readUnsignedByte()
                        val sourceLength = input.readUnsignedShort()
                        require(keyLength in 0..MAX_LEVELDB_KEY_BYTES) { "Corrupt temporary LevelDB key length" }
                        require(valueLength in 0..MAX_LIVE_RECORD_VALUE_BYTES) { "Corrupt temporary LevelDB value length" }
                        require(sourceLength in 0..MAX_SOURCE_NAME_BYTES) { "Corrupt temporary source length" }
                        val sourceBytes = ByteArray(sourceLength)
                        input.readFully(sourceBytes)
                        val key = ByteArray(keyLength)
                        input.readFully(key)
                        val value = ByteArray(valueLength)
                        input.readFully(value)
                        require(valueType == VALUE_TYPE || valueType == DELETION_TYPE) {
                            "Corrupt temporary LevelDB value type $valueType"
                        }
                        val byteKey = ByteKey(key)
                        val previous = latest[byteKey]
                        if (previous == null || sequence > previous.sequence) {
                            latest[byteKey] = InternalRecord(
                                key = key,
                                value = value,
                                sequence = sequence,
                                valueType = valueType,
                                sourceFile = sourceBytes.toString(Charsets.UTF_8),
                            )
                            retainedValueBytes += value.size - (previous?.value?.size ?: 0)
                            if (previous == null) retainedKeyBytes += key.size
                            require(latest.size <= MAX_KEYS_PER_BUCKET) {
                                "A LevelDB hash bucket exceeds the in-memory key-count safety limit"
                            }
                            require(retainedValueBytes <= MAX_VALUE_BYTES_PER_BUCKET) {
                                "A LevelDB hash bucket exceeds the in-memory value safety limit"
                            }
                            require(retainedKeyBytes + retainedValueBytes <= MAX_TOTAL_BYTES_PER_BUCKET) {
                                "A LevelDB hash bucket exceeds the total in-memory byte safety limit"
                            }
                        }
                    }
                }
                for (record in latest.values) {
                    checkCancellation()
                    if (record.valueType == VALUE_TYPE) {
                        live++
                        emittedRecords++
                        if (!visitor.visit(Record(record.key, record.value, record.sequence, record.sourceFile))) {
                            return BucketVisitCounts(live, deleted)
                        }
                    } else {
                        deleted++
                    }
                }
                latest.clear()
                file.delete()
                bucketsProcessed++
                visitor.onProgress(
                    baseProgress.copy(
                        filesProcessed = baseProgress.filesProcessed + bucketsProcessed,
                        recordsProcessed = baseProgress.recordsProcessed + emittedRecords,
                        currentFile = "temporary-index-bucket-$bucket",
                    )
                )
            }
            return BucketVisitCounts(live, deleted)
        }

        override fun close() {
            if (closed) return
            closed = true
            closeOutputs()
        }

        private fun closeOutputs() {
            openOutputs.values.forEach { runCatching { it.close() } }
            openOutputs.clear()
        }

        private fun bucketFor(key: ByteArray): Int {
            val hash = digest.digest(key)
            return (((hash[0].toInt() and 0xff) shl 4) or ((hash[1].toInt() and 0xff) ushr 4)) and
                (BUCKET_COUNT - 1)
        }

        private fun bucketFile(bucket: Int): File = File(root, "%04x.bucket".format(bucket))
    }

    private data class ManifestState(
        val liveTables: Set<Long>,
        val logNumber: Long?,
        val previousLogNumber: Long?,
    )

    private data class VersionEdit(
        val addedTables: Set<Long>,
        val deletedTables: Set<Long>,
        val logNumber: Long?,
        val previousLogNumber: Long?,
    )

    private fun readManifest(manifest: File): ManifestState {
        val live = linkedSetOf<Long>()
        var log: Long? = null
        var previousLog: Long? = null
        var recordCount = 0
        forEachPhysicalLogRecord(manifest) { record ->
            recordCount++
            val edit = parseVersionEdit(record, manifest.name)
            edit.deletedTables.forEach(live::remove)
            live.addAll(edit.addedTables)
            if (edit.logNumber != null) log = edit.logNumber
            if (edit.previousLogNumber != null) previousLog = edit.previousLogNumber
        }
        require(recordCount > 0) { "${manifest.name} contains no VersionEdit records" }
        return ManifestState(live, log, previousLog)
    }

    private fun parseVersionEdit(bytes: ByteArray, source: String): VersionEdit {
        var position = 0
        val added = linkedSetOf<Long>()
        val deleted = linkedSetOf<Long>()
        var logNumber: Long? = null
        var previousLogNumber: Long? = null
        while (position < bytes.size) {
            val tag = readVarInt(bytes, position).also { position = it.second }.first
            when (tag.toInt()) {
                VERSION_EDIT_COMPARATOR -> {
                    val slice = readLengthPrefixed(bytes, position)
                    position = slice.second
                }
                VERSION_EDIT_LOG_NUMBER -> {
                    val value = readVarInt(bytes, position)
                    logNumber = value.first
                    position = value.second
                }
                VERSION_EDIT_NEXT_FILE_NUMBER,
                VERSION_EDIT_LAST_SEQUENCE -> position = readVarInt(bytes, position).second
                VERSION_EDIT_COMPACT_POINTER -> {
                    position = readVarInt(bytes, position).second // level
                    position = readLengthPrefixed(bytes, position).second // internal key
                }
                VERSION_EDIT_DELETED_FILE -> {
                    position = readVarInt(bytes, position).second // level
                    val number = readVarInt(bytes, position)
                    deleted += number.first
                    position = number.second
                }
                VERSION_EDIT_NEW_FILE -> {
                    position = readVarInt(bytes, position).second // level
                    val number = readVarInt(bytes, position)
                    position = number.second
                    position = readVarInt(bytes, position).second // file size
                    position = readLengthPrefixed(bytes, position).second // smallest key
                    position = readLengthPrefixed(bytes, position).second // largest key
                    added += number.first
                }
                VERSION_EDIT_PREVIOUS_LOG_NUMBER -> {
                    val value = readVarInt(bytes, position)
                    previousLogNumber = value.first
                    position = value.second
                }
                else -> error("$source contains unsupported VersionEdit tag $tag")
            }
        }
        require(position == bytes.size) { "$source VersionEdit has trailing bytes" }
        return VersionEdit(added, deleted, logNumber, previousLogNumber)
    }

    private data class WriteOperation(
        val key: ByteArray,
        val value: ByteArray?,
        val sequence: Long,
        val valueType: Int,
    )

    private fun forEachWriteBatchOperation(
        bytes: ByteArray,
        source: String,
        consumer: (WriteOperation) -> Unit,
    ): Int {
        require(bytes.size >= WRITE_BATCH_HEADER_BYTES) { "$source write batch is truncated" }
        val sequence = readLongLE(bytes, 0)
        val count = readIntLE(bytes, 8)
        require(count >= 0) { "$source write batch has negative operation count" }
        require(count <= MAX_WRITE_BATCH_OPERATIONS) {
            "$source write batch operation count $count exceeds the safety limit $MAX_WRITE_BATCH_OPERATIONS"
        }
        // Every operation needs at least a type byte and a one-byte key length.
        require(count <= (bytes.size - WRITE_BATCH_HEADER_BYTES) / 2) {
            "$source write batch operation count is impossible for its encoded size"
        }
        var position = WRITE_BATCH_HEADER_BYTES
        repeat(count) { index ->
            require(position < bytes.size) { "$source write batch ends before operation $index" }
            val type = bytes[position++].toInt() and 0xff
            val key = readLengthPrefixed(bytes, position).also { position = it.second }
            when (type) {
                VALUE_TYPE -> {
                    val value = readLengthPrefixed(bytes, position).also { position = it.second }
                    consumer(WriteOperation(key.first, value.first, sequence + index, VALUE_TYPE))
                }
                DELETION_TYPE -> consumer(WriteOperation(key.first, null, sequence + index, DELETION_TYPE))
                else -> error("$source write batch has unsupported operation type $type")
            }
        }
        require(position == bytes.size) { "$source write batch has ${bytes.size - position} trailing bytes" }
        return count
    }

    /**
     * Streams LevelDB's 32 KiB block-framed log format. At most one physical
     * block and one bounded fragmented logical record are retained in memory.
     */
    private fun forEachPhysicalLogRecord(
        file: File,
        consumer: (ByteArray) -> Unit,
    ) {
        var fragmented: ByteArrayOutputStream? = null
        BufferedInputStream(FileInputStream(file), LOG_BLOCK_BYTES).use { input ->
            val block = ByteArray(LOG_BLOCK_BYTES)
            var absoluteOffset = 0L
            while (true) {
                var blockLength = 0
                while (blockLength < LOG_BLOCK_BYTES) {
                    val read = input.read(block, blockLength, LOG_BLOCK_BYTES - blockLength)
                    if (read < 0) break
                    if (read == 0) continue
                    blockLength += read
                }
                if (blockLength == 0) break

                var position = 0
                while (position < blockLength) {
                    val remaining = blockLength - position
                    if (remaining < LOG_HEADER_BYTES) {
                        require(block.copyOfRange(position, blockLength).all { it == 0.toByte() }) {
                            "${file.name} has a non-zero truncated log trailer at ${absoluteOffset + position}"
                        }
                        position = blockLength
                        continue
                    }

                    val storedChecksum = readIntLE(block, position)
                    val length = readUnsignedShortLE(block, position + 4)
                    val type = block[position + 6].toInt() and 0xff
                    if (storedChecksum == 0 && length == 0 && type == 0) {
                        require(block.copyOfRange(position, blockLength).all { it == 0.toByte() }) {
                            "${file.name} has data after an empty log trailer"
                        }
                        position = blockLength
                        continue
                    }

                    require(type in LOG_FULL..LOG_LAST) {
                        "${file.name} has unsupported log record type $type"
                    }
                    require(length <= remaining - LOG_HEADER_BYTES) {
                        "${file.name} log record crosses a physical block boundary"
                    }
                    require(length <= MAX_LOGICAL_RECORD_BYTES) {
                        "${file.name} physical log fragment exceeds the bounded record limit"
                    }
                    val payloadStart = position + LOG_HEADER_BYTES
                    val payload = block.copyOfRange(payloadStart, payloadStart + length)
                    val actualChecksum = maskCrc32c(crc32c(byteArrayOf(type.toByte()), payload))
                    require(storedChecksum == actualChecksum) {
                        "${file.name} log checksum mismatch at offset ${absoluteOffset + position}"
                    }
                    position = payloadStart + length

                    when (type) {
                        LOG_FULL -> {
                            require(fragmented == null) {
                                "${file.name} FULL record interrupted a fragmented record"
                            }
                            consumer(payload)
                        }
                        LOG_FIRST -> {
                            require(fragmented == null) {
                                "${file.name} FIRST record interrupted a fragmented record"
                            }
                            fragmented = ByteArrayOutputStream(payload.size.coerceAtLeast(32)).apply {
                                write(payload)
                            }
                        }
                        LOG_MIDDLE -> {
                            val output = fragmented
                                ?: error("${file.name} MIDDLE record has no FIRST record")
                            require(output.size() + payload.size <= MAX_LOGICAL_RECORD_BYTES) {
                                "${file.name} fragmented logical record exceeds $MAX_LOGICAL_RECORD_BYTES bytes"
                            }
                            output.write(payload)
                        }
                        LOG_LAST -> {
                            val output = fragmented
                                ?: error("${file.name} LAST record has no FIRST record")
                            require(output.size() + payload.size <= MAX_LOGICAL_RECORD_BYTES) {
                                "${file.name} fragmented logical record exceeds $MAX_LOGICAL_RECORD_BYTES bytes"
                            }
                            output.write(payload)
                            consumer(output.toByteArray())
                            fragmented = null
                        }
                    }
                }
                absoluteOffset += blockLength
                if (blockLength < LOG_BLOCK_BYTES) break
            }
        }
        require(fragmented == null) {
            "${file.name} ends with an incomplete fragmented record"
        }
    }

    private data class InternalRecord(
        val key: ByteArray,
        val value: ByteArray,
        val sequence: Long,
        val valueType: Int,
        val sourceFile: String,
    )

    private data class Entry(val key: ByteArray, val value: ByteArray)
    private data class BlockHandle(val offset: Long, val size: Long)

    private class ByteKey(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is ByteKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private fun applyInternalEntry(
        latest: MutableMap<ByteKey, InternalRecord>,
        internalKey: ByteArray,
        value: ByteArray,
        sourceFile: String,
    ): Long {
        val decoded = decodeInternalEntry(internalKey, value, sourceFile)
        val userKey = ByteKey(decoded.key)
        val previous = latest[userKey]
        if (previous == null || decoded.sequence > previous.sequence) {
            latest[userKey] = decoded
            return value.size.toLong() - (previous?.value?.size?.toLong() ?: 0L)
        }
        return 0L
    }

    private fun readTable(
        tableFile: File,
        compressionCounts: MutableMap<Int, Int>,
        consumer: (Entry) -> Unit,
    ) {
        val fileLength = tableFile.length()
        require(fileLength >= FOOTER_BYTES) { "SSTable footer is truncated" }
        java.io.RandomAccessFile(tableFile, "r").use { raf ->
            raf.seek(fileLength - 8)
            val magicBuf = ByteArray(8)
            raf.readFully(magicBuf)
            val magic = readLongLE(magicBuf, 0)
            require(magic == TABLE_MAGIC) { "Invalid SSTable magic" }

            val footerBuf = ByteArray(FOOTER_BYTES - 8)
            raf.seek(fileLength - FOOTER_BYTES)
            raf.readFully(footerBuf)

            var cursor = 0
            cursor = readBlockHandle(footerBuf, cursor).second // metaindex
            val indexHandlePair = readBlockHandle(footerBuf, cursor)
            val indexBlock = readBlockFromFile(raf, fileLength, indexHandlePair.first, compressionCounts)

            for (indexEntry in parseRestartBlock(indexBlock)) {
                val dataHandle = readBlockHandle(indexEntry.value, 0).first
                val dataBlock = readBlockFromFile(raf, fileLength, dataHandle, compressionCounts)
                parseRestartBlock(dataBlock).forEach(consumer)
            }
        }
    }

    private fun readBlockFromFile(
        raf: java.io.RandomAccessFile,
        fileLength: Long,
        handle: BlockHandle,
        compressionCounts: MutableMap<Int, Int>,
    ): ByteArray {
        require(handle.offset >= 0 && handle.size >= 0) { "Negative block handle" }
        require(handle.size <= MAX_COMPRESSED_BLOCK_BYTES.toLong()) {
            "Compressed SSTable block exceeds the $MAX_COMPRESSED_BLOCK_BYTES-byte safety limit"
        }
        require(handle.size <= Int.MAX_VALUE - BLOCK_TRAILER_BYTES) { "SSTable block is too large" }
        require(handle.offset <= fileLength - handle.size - BLOCK_TRAILER_BYTES) { "SSTable block points outside file" }
        val offset = handle.offset
        val size = handle.size.toInt()
        val totalToRead = size + BLOCK_TRAILER_BYTES
        val blockData = ByteArray(totalToRead)
        raf.seek(offset)
        raf.readFully(blockData)

        val payload = blockData.copyOf(size)
        val compressionType = blockData[size].toInt() and 0xff
        val storedChecksum = readIntLE(blockData, size + 1)
        val actualChecksum = maskCrc32c(crc32c(payload, byteArrayOf(compressionType.toByte())))
        require(storedChecksum == actualChecksum) { "SSTable block checksum mismatch at offset $offset" }
        compressionCounts[compressionType] = compressionCounts.getOrDefault(compressionType, 0) + 1
        return when (compressionType) {
            COMPRESSION_NONE -> payload
            COMPRESSION_SNAPPY -> Snappy.decode(payload)
            COMPRESSION_ZLIB -> inflate(payload, nowrap = false)
            COMPRESSION_RAW_DEFLATE -> inflate(payload, nowrap = true)
            else -> error("Unsupported LevelDB compression type $compressionType")
        }
    }

    private fun inflate(payload: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(payload)
            val output = ByteArrayOutputStream(payload.size * 2)
            val buffer = ByteArray(32 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    output.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw DataFormatException("Incomplete deflate stream")
                }
                require(output.size() <= MAX_DECOMPRESSED_BLOCK_BYTES) { "Decompressed LevelDB block is too large" }
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun parseRestartBlock(raw: ByteArray): List<Entry> {
        require(raw.size >= 4) { "Restart block is truncated" }
        val restartCount = readIntLE(raw, raw.size - 4)
        require(restartCount >= 0) { "Negative restart count" }
        require(restartCount <= (raw.size - 4) / 4) { "Invalid restart count $restartCount" }
        val restartStart = raw.size - 4 - restartCount * 4
        require(restartStart >= 0) { "Invalid restart array" }

        var position = 0
        var previousKey = ByteArray(0)
        val entries = mutableListOf<Entry>()
        while (position < restartStart) {
            val shared = readVarInt(raw, position).also { position = it.second }.first
            val nonShared = readVarInt(raw, position).also { position = it.second }.first
            val valueLength = readVarInt(raw, position).also { position = it.second }.first
            require(shared <= previousKey.size) { "Shared key prefix exceeds previous key" }
            require(nonShared <= Int.MAX_VALUE && valueLength <= Int.MAX_VALUE) { "Restart entry is too large" }
            val neededLong = nonShared + valueLength
            require(neededLong <= Int.MAX_VALUE) { "Restart entry is too large" }
            val needed = neededLong.toInt()
            require(position <= restartStart - needed) { "Restart entry crosses restart array" }
            val key = ByteArray(shared.toInt() + nonShared.toInt())
            previousKey.copyInto(key, endIndex = shared.toInt())
            raw.copyInto(key, destinationOffset = shared.toInt(), startIndex = position, endIndex = position + nonShared.toInt())
            position += nonShared.toInt()
            val value = raw.copyOfRange(position, position + valueLength.toInt())
            position += valueLength.toInt()
            previousKey = key
            entries += Entry(key, value)
        }
        require(position == restartStart) { "Restart entry boundary mismatch" }
        return entries
    }

    private fun readBlockHandle(bytes: ByteArray, offset: Int): Pair<BlockHandle, Int> {
        val first = readVarInt(bytes, offset)
        val second = readVarInt(bytes, first.second)
        return BlockHandle(first.first, second.first) to second.second
    }

    private fun readLengthPrefixed(bytes: ByteArray, offset: Int): Pair<ByteArray, Int> {
        val length = readVarInt(bytes, offset)
        require(length.first <= Int.MAX_VALUE) { "Length-prefixed value is too large" }
        val start = length.second
        val size = length.first.toInt()
        require(start <= bytes.size - size) { "Length-prefixed value is truncated" }
        return bytes.copyOfRange(start, start + size) to (start + size)
    }

    private fun readVarInt(bytes: ByteArray, offset: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var position = offset
        repeat(10) {
            require(position < bytes.size) { "Truncated varint" }
            val current = bytes[position++].toInt() and 0xff
            value = value or ((current and 0x7f).toLong() shl shift)
            if (current < 0x80) return value to position
            shift += 7
        }
        error("Varint is too long")
    }

    private fun readUnsignedShortLE(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 2) { "Short outside buffer" }
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 4) { "Int outside buffer" }
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }

    private fun readLongLE(bytes: ByteArray, offset: Int): Long {
        val low = readIntLE(bytes, offset).toLong() and 0xffffffffL
        val high = readIntLE(bytes, offset + 4).toLong() and 0xffffffffL
        return low or (high shl 32)
    }

    private fun crc32c(vararg parts: ByteArray): Int {
        var crc = -1
        for (part in parts) {
            for (byte in part) {
                val index = (crc xor (byte.toInt() and 0xff)) and 0xff
                crc = CRC32C_TABLE[index] xor (crc ushr 8)
            }
        }
        return crc.inv()
    }

    private fun maskCrc32c(crc: Int): Int = ((crc ushr 15) or (crc shl 17)) + CRC_MASK_DELTA

    private object Snappy {
        fun decode(input: ByteArray): ByteArray {
            var position = 0
            val expectedPair = readVarInt(input, position)
            val expected = expectedPair.first
            position = expectedPair.second
            require(expected in 0..MAX_DECOMPRESSED_BLOCK_BYTES.toLong()) { "Invalid Snappy output length $expected" }
            val output = ByteArray(expected.toInt())
            var outputPosition = 0

            while (position < input.size && outputPosition < output.size) {
                val tag = input[position++].toInt() and 0xff
                when (tag and 0x03) {
                    0 -> {
                        val lengthCode = tag ushr 2
                        val length = if (lengthCode < 60) {
                            lengthCode + 1
                        } else {
                            val byteCount = lengthCode - 59
                            require(byteCount in 1..4 && position <= input.size - byteCount) { "Invalid Snappy literal length" }
                            var literalLength = 0
                            repeat(byteCount) { index ->
                                literalLength = literalLength or ((input[position++].toInt() and 0xff) shl (index * 8))
                            }
                            literalLength + 1
                        }
                        require(position <= input.size - length) { "Truncated Snappy literal" }
                        require(outputPosition <= output.size - length) { "Snappy literal exceeds output" }
                        input.copyInto(output, outputPosition, position, position + length)
                        position += length
                        outputPosition += length
                    }
                    1 -> {
                        val length = 4 + ((tag ushr 2) and 0x07)
                        require(position < input.size) { "Truncated Snappy copy-1" }
                        val offset = ((tag and 0xe0) shl 3) or (input[position++].toInt() and 0xff)
                        outputPosition = copy(output, outputPosition, offset, length)
                    }
                    2 -> {
                        val length = 1 + (tag ushr 2)
                        require(position <= input.size - 2) { "Truncated Snappy copy-2" }
                        val offset = (input[position].toInt() and 0xff) or
                            ((input[position + 1].toInt() and 0xff) shl 8)
                        position += 2
                        outputPosition = copy(output, outputPosition, offset, length)
                    }
                    3 -> {
                        val length = 1 + (tag ushr 2)
                        require(position <= input.size - 4) { "Truncated Snappy copy-4" }
                        val offsetLong = (input[position].toLong() and 0xffL) or
                            ((input[position + 1].toLong() and 0xffL) shl 8) or
                            ((input[position + 2].toLong() and 0xffL) shl 16) or
                            ((input[position + 3].toLong() and 0xffL) shl 24)
                        position += 4
                        require(offsetLong <= Int.MAX_VALUE) { "Snappy copy offset is too large" }
                        outputPosition = copy(output, outputPosition, offsetLong.toInt(), length)
                    }
                }
            }
            require(outputPosition == output.size) { "Snappy output length mismatch: $outputPosition != ${output.size}" }
            return output
        }

        private fun copy(output: ByteArray, outputPosition: Int, offset: Int, length: Int): Int {
            require(offset > 0 && offset <= outputPosition) { "Invalid Snappy copy offset $offset" }
            require(outputPosition <= output.size - length) { "Snappy copy exceeds output" }
            var destination = outputPosition
            repeat(length) {
                output[destination] = output[destination - offset]
                destination++
            }
            return destination
        }
    }

    private val CRC32C_TABLE: IntArray = IntArray(256).also { table ->
        for (index in table.indices) {
            var value = index
            repeat(8) {
                value = if ((value and 1) != 0) CRC32C_POLYNOMIAL xor (value ushr 1) else value ushr 1
            }
            table[index] = value
        }
    }

    private val MANIFEST_NAME = Regex("^MANIFEST-[0-9]+$")
    private const val TABLE_MAGIC = -2646017456237118633L // 0xdb4775248b80fb57 unsigned
    private const val FOOTER_BYTES = 48
    private const val BLOCK_TRAILER_BYTES = 5
    private const val INTERNAL_TRAILER_BYTES = 8
    private const val WRITE_BATCH_HEADER_BYTES = 12
    private const val DELETION_TYPE = 0
    private const val VALUE_TYPE = 1
    private const val COMPRESSION_NONE = 0
    private const val COMPRESSION_SNAPPY = 1
    private const val COMPRESSION_ZLIB = 2
    private const val COMPRESSION_RAW_DEFLATE = 4
    private const val MAX_COMPRESSED_BLOCK_BYTES = 32 * 1024 * 1024
    private const val MAX_DECOMPRESSED_BLOCK_BYTES = 64 * 1024 * 1024
    private const val MAX_WRITE_BATCH_OPERATIONS = 1_000_000
    private const val BUCKET_COUNT = 4096
    private const val MAX_OPEN_BUCKET_STREAMS = 32
    private const val BUCKET_IO_BUFFER_BYTES = 64 * 1024
    private const val MAX_LEVELDB_KEY_BYTES = 1024 * 1024
    private const val MAX_LIVE_RECORD_VALUE_BYTES = 64 * 1024 * 1024
    private const val MAX_SOURCE_NAME_BYTES = 4096
    private const val MAX_KEYS_PER_BUCKET = 100_000
    private const val MAX_VALUE_BYTES_PER_BUCKET = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES_PER_BUCKET = 96L * 1024L * 1024L

    private fun enforceSnapshotMemoryBounds(
        latestKeyCount: Int,
        retainedValueBytes: Long,
    ) {
        require(latestKeyCount <= MAX_LATEST_KEYS_IN_MEMORY) {
            "LevelDB snapshot exceeds the safe in-memory key limit ($MAX_LATEST_KEYS_IN_MEMORY). " +
                "Import was stopped before Android could run out of memory."
        }
        require(retainedValueBytes <= MAX_LIVE_VALUE_BYTES_IN_MEMORY) {
            "LevelDB snapshot exceeds the safe in-memory live-value limit " +
                "($MAX_LIVE_VALUE_BYTES_IN_MEMORY bytes). Import was stopped fail-closed."
        }
    }

    private const val LOG_BLOCK_BYTES = 32 * 1024
    private const val MAX_LOGICAL_RECORD_BYTES = 64 * 1024 * 1024
    private const val MAX_LATEST_KEYS_IN_MEMORY = 2_000_000
    private const val MAX_LIVE_VALUE_BYTES_IN_MEMORY = 256L * 1024L * 1024L
    private const val LOG_HEADER_BYTES = 7
    private const val LOG_FULL = 1
    private const val LOG_FIRST = 2
    private const val LOG_MIDDLE = 3
    private const val LOG_LAST = 4

    private const val VERSION_EDIT_COMPARATOR = 1
    private const val VERSION_EDIT_LOG_NUMBER = 2
    private const val VERSION_EDIT_NEXT_FILE_NUMBER = 3
    private const val VERSION_EDIT_LAST_SEQUENCE = 4
    private const val VERSION_EDIT_COMPACT_POINTER = 5
    private const val VERSION_EDIT_DELETED_FILE = 6
    private const val VERSION_EDIT_NEW_FILE = 7
    private const val VERSION_EDIT_PREVIOUS_LOG_NUMBER = 9

    private const val CRC32C_POLYNOMIAL = -2097792136
    private const val CRC_MASK_DELTA = -1568478504
}
