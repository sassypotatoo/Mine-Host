package com.example.backup

import android.os.Build
import com.example.data.OperationResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Verified MineHost backups and recoverable whole-directory restore swaps.
 *
 * A backup contains exactly the files declared by its manifest. Restore never
 * overlays the live directory file-by-file; it builds a complete sibling root,
 * verifies it, and then swaps roots with an external transaction journal.
 */
class BackupManagerV2(private val serverRoot: File) {
    companion object {
        const val MANIFEST_ENTRY = "minehost-manifest.json"
        private const val FORMAT_VERSION = 2
        private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_ENTRY_COUNT = 250_000
        private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    }

    data class Metadata(
        val serverUuid: String,
        val engineId: String,
        val engineVersionId: String,
        val javaVersion: Int,
        val levelName: String,
    )

    data class Verification(
        val valid: Boolean,
        val message: String,
        val manifest: JSONObject? = null,
    )

    data class Creation(
        val result: OperationResult,
        val archive: File? = null,
    )

    private enum class RestorePhase {
        PREPARED,
        OLD_RENAMED,
        NEW_INSTALLED,
        BACKUPS_MOVED,
        COMMITTED,
    }

    private val canonicalRoot: File get() = serverRoot.canonicalFile
    private val backupsDir: File get() = File(canonicalRoot, "backups")
    private val restoreTransactionDir: File
        get() = File(canonicalRoot.parentFile, ".minehost-restore-${canonicalRoot.name}")

    fun create(metadata: Metadata, reason: String = "manual"): OperationResult =
        createWithResult(metadata, reason).result

    fun createWithResult(metadata: Metadata, reason: String = "manual"): Creation {
        recoverInterruptedRestore().takeIf { !it.success }?.let { return Creation(it) }
        if (metadata.serverUuid.isBlank()) return Creation(OperationResult(false, "Backup server UUID is missing"))

        backupsDir.mkdirs()
        val timestamp = System.currentTimeMillis()
        val baseName = "minehost-v2-$timestamp-${reason.safeName()}"
        val part = File(backupsDir, "$baseName.zip.part")
        val final = File(backupsDir, "$baseName.zip")
        val checksumPart = File(backupsDir, "$baseName.zip.sha256.part")
        val checksumFinal = File(backupsDir, "$baseName.zip.sha256")
        val snapshotRoot = File(canonicalRoot.parentFile, ".minehost-backup-snapshot-${canonicalRoot.name}-${UUID.randomUUID()}")

        return try {
            val snapshotEntries = createStableSnapshot(snapshotRoot)
            val entriesJson = JSONArray()
            snapshotEntries.forEach { snapshot ->
                entriesJson.put(
                    JSONObject()
                        .put("path", snapshot.relativePath)
                        .put("size", snapshot.file.length())
                        .put("sha256", snapshot.sha256)
                        .put("lastModified", snapshot.lastModified),
                )
            }
            val manifest = JSONObject()
                .put("formatVersion", FORMAT_VERSION)
                .put("backupId", UUID.randomUUID().toString())
                .put("createdAt", timestamp)
                .put("reason", reason)
                .put("serverUuid", metadata.serverUuid)
                .put("engineId", metadata.engineId)
                .put("engineVersionId", metadata.engineVersionId)
                .put("javaVersion", metadata.javaVersion)
                .put("levelName", metadata.levelName)
                .put("files", entriesJson)

            FileOutputStream(part).use { fileOutput ->
                ZipOutputStream(BufferedOutputStream(fileOutput)).use { zip ->
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    snapshotEntries.forEach { snapshot ->
                        val entry = ZipEntry(snapshot.relativePath)
                        entry.time = snapshot.lastModified
                        zip.putNextEntry(entry)
                        FileInputStream(snapshot.file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                    zip.finish()
                }
                runCatching { fileOutput.fd.sync() }
            }

            val verification = verify(part, metadata.serverUuid)
            check(verification.valid) { verification.message }
            checksumPart.writeText(sha256(part) + "  " + final.name + "\n")
            atomicMove(part, final)
            atomicMove(checksumPart, checksumFinal)
            Creation(OperationResult(true, "Verified backup created: ${final.name}"), final)
        } catch (error: Throwable) {
            part.delete()
            checksumPart.delete()
            Creation(OperationResult(false, error.message ?: "Backup creation failed"))
        } finally {
            snapshotRoot.deleteRecursively()
        }
    }

    fun verify(archive: File, expectedServerUuid: String? = null): Verification {
        if (!archive.isFile || archive.length() <= 0L) return Verification(false, "Backup is missing or empty")
        return runCatching {
            ZipFile(archive).use { zip ->
                val allEntries = linkedMapOf<String, java.util.zip.ZipEntry>()
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement()
                    val normalized = normalizeEntryName(entry.name)
                        ?: return Verification(false, "Unsafe backup entry: ${entry.name}")
                    if (entry.isDirectory) return Verification(false, "Directory entries are not permitted: $normalized")
                    if (allEntries.put(normalized, entry) != null) {
                        return Verification(false, "Duplicate backup entry: $normalized")
                    }
                    if (allEntries.size > MAX_ENTRY_COUNT + 1) {
                        return Verification(false, "Backup contains too many entries")
                    }
                }

                val manifestEntry = allEntries[MANIFEST_ENTRY]
                    ?: return Verification(false, "Backup manifest is missing")
                if (manifestEntry.size > MAX_MANIFEST_BYTES) return Verification(false, "Backup manifest is too large")
                val manifestText = zip.getInputStream(manifestEntry).bufferedReader().use { reader ->
                    val text = reader.readText()
                    if (text.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
                        return Verification(false, "Backup manifest is too large")
                    }
                    text
                }
                val manifest = JSONObject(manifestText)
                if (manifest.optInt("formatVersion", -1) != FORMAT_VERSION) {
                    return Verification(false, "Unsupported backup format")
                }
                val serverUuid = manifest.getString("serverUuid").trim()
                if (serverUuid.isBlank()) return Verification(false, "Backup server UUID is missing")
                if (expectedServerUuid != null && expectedServerUuid.isNotBlank() && serverUuid != expectedServerUuid.trim()) {
                    return Verification(false, "Backup belongs to server UUID $serverUuid, not ${expectedServerUuid.trim()}")
                }

                val expectedArray = manifest.getJSONArray("files")
                if (expectedArray.length() > MAX_ENTRY_COUNT) return Verification(false, "Backup contains too many files")
                val expectedPaths = linkedSetOf<String>()
                var total = 0L
                for (index in 0 until expectedArray.length()) {
                    val item = expectedArray.getJSONObject(index)
                    val path = normalizeEntryName(item.getString("path"))
                        ?: return Verification(false, "Unsafe manifest path: ${item.optString("path")}")
                    if (path == MANIFEST_ENTRY || !expectedPaths.add(path)) {
                        return Verification(false, "Duplicate or reserved manifest path: $path")
                    }
                    val entry = allEntries[path] ?: return Verification(false, "Missing backup entry: $path")
                    val expectedSize = item.getLong("size")
                    if (expectedSize !in 0..MAX_ENTRY_BYTES) return Verification(false, "Invalid entry size: $path")
                    total = Math.addExact(total, expectedSize)
                    if (total > MAX_TOTAL_BYTES) return Verification(false, "Backup expands beyond safety limit")

                    val digest = MessageDigest.getInstance("SHA-256")
                    var actualSize = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            actualSize += count
                            if (actualSize > expectedSize || actualSize > MAX_ENTRY_BYTES) {
                                return Verification(false, "Entry size mismatch: $path")
                            }
                            digest.update(buffer, 0, count)
                        }
                    }
                    if (actualSize != expectedSize) return Verification(false, "Entry size mismatch: $path")
                    if (!digest.digest().toHex().equals(item.getString("sha256"), ignoreCase = true)) {
                        return Verification(false, "Checksum mismatch: $path")
                    }
                }

                val actualPayload = allEntries.keys - MANIFEST_ENTRY
                if (actualPayload != expectedPaths) {
                    val unexpected = (actualPayload - expectedPaths).take(3)
                    val missing = (expectedPaths - actualPayload).take(3)
                    return Verification(
                        false,
                        "Backup entry set differs from the manifest." +
                            (if (unexpected.isNotEmpty()) " Unexpected: ${unexpected.joinToString()}." else "") +
                            (if (missing.isNotEmpty()) " Missing: ${missing.joinToString()}." else ""),
                    )
                }
                Verification(true, "Backup verified", manifest)
            }
        }.getOrElse { Verification(false, it.message ?: "Backup verification failed") }
    }

    fun restore(
        archive: File,
        currentMetadata: Metadata,
        allowLegacyWithoutUuid: Boolean = false,
        createRecoveryBackup: Boolean = true,
    ): OperationResult {
        recoverInterruptedRestore().takeIf { !it.success }?.let { return it }
        val verification = verify(archive, currentMetadata.serverUuid)
        if (!verification.valid) {
            if (verification.message == "Backup manifest is missing" && allowLegacyWithoutUuid) {
                return restoreLegacyArchive(archive, currentMetadata, createRecoveryBackup)
            }
            return OperationResult(false, verification.message)
        }

        if (createRecoveryBackup) {
            val recovery = create(currentMetadata, "before-restore")
            if (!recovery.success) return OperationResult(false, "Restore cancelled: ${recovery.message}")
        }

        val transaction = restoreTransactionDir
        if (transaction.exists()) return OperationResult(false, "Another restore transaction requires recovery")
        val newRoot = File(transaction, "new-root")
        val oldRoot = File(transaction, "old-root")
        transaction.mkdirs()
        newRoot.mkdirs()

        return try {
            extractVerified(archive, newRoot, verification.manifest ?: error("Verified manifest unavailable"))
            verifyExtracted(newRoot, verification.manifest)
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.PREPARED)

            moveDirectory(canonicalRoot, oldRoot)
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.OLD_RENAMED)

            moveDirectory(newRoot, canonicalRoot)
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.NEW_INSTALLED)

            val oldBackups = File(oldRoot, "backups")
            val newBackups = File(canonicalRoot, "backups")
            if (oldBackups.exists()) {
                if (newBackups.exists()) error("Restored archive unexpectedly contains a backups directory")
                moveDirectory(oldBackups, newBackups)
            }
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.BACKUPS_MOVED)
            verifyExtracted(canonicalRoot, verification.manifest)
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.COMMITTED)

            oldRoot.deleteRecursively()
            transaction.deleteRecursively()
            OperationResult(true, "Backup restored and verified. Start the server to complete the health check.")
        } catch (error: Throwable) {
            val recovery = recoverInterruptedRestore()
            OperationResult(
                false,
                (error.message ?: "Restore failed") + if (recovery.success) ". Original server files were restored." else ". ${recovery.message}",
            )
        }
    }

    fun recoverInterruptedRestore(): OperationResult {
        val transaction = restoreTransactionDir
        if (!transaction.exists()) return OperationResult(true, "No interrupted restore")
        val oldRoot = File(transaction, "old-root")
        val newRoot = File(transaction, "new-root")
        val journal = readJournal(transaction)

        return runCatching {
            if (oldRoot.exists()) {
                val liveBackups = File(canonicalRoot, "backups")
                val oldBackups = File(oldRoot, "backups")
                if (!oldBackups.exists() && liveBackups.exists()) {
                    moveDirectory(liveBackups, oldBackups)
                }
                if (canonicalRoot.exists()) canonicalRoot.deleteRecursively()
                moveDirectory(oldRoot, canonicalRoot)
            } else if (!canonicalRoot.exists()) {
                error("Restore journal ${journal?.optString("phase") ?: "unknown"} has no original server directory")
            }
            newRoot.deleteRecursively()
            transaction.deleteRecursively()
            OperationResult(true, "Interrupted restore rolled back")
        }.getOrElse { OperationResult(false, "Interrupted restore recovery failed: ${it.message}") }
    }

    fun delete(fileName: String): OperationResult {
        recoverInterruptedRestore().takeIf { !it.success }?.let { return it }
        val archives = backupsDir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("zip", true) }
        if (archives.size <= 1) return OperationResult(false, "The only backup cannot be deleted")
        val target = runCatching { File(backupsDir, fileName).canonicalFile }.getOrNull()
            ?: return OperationResult(false, "Backup not found")
        if (!target.isFile || target.parentFile != backupsDir.canonicalFile) return OperationResult(false, "Backup not found")
        val removed = target.delete()
        if (removed) File(backupsDir, "$fileName.sha256").delete()
        return OperationResult(removed, if (removed) "Backup deleted" else "Unable to delete backup")
    }

    private data class SnapshotFile(
        val relativePath: String,
        val file: File,
        val sha256: String,
        val lastModified: Long,
    )

    private fun createStableSnapshot(snapshotRoot: File): List<SnapshotFile> {
        snapshotRoot.mkdirs()
        val sourceFiles = collectBackupFiles()
        check(sourceFiles.size <= MAX_ENTRY_COUNT) { "Server contains too many files to back up safely" }
        var total = 0L
        return sourceFiles.map { source ->
            val relative = relative(source)
            val beforeLength = source.length()
            val beforeModified = source.lastModified()
            check(beforeLength in 0..MAX_ENTRY_BYTES) { "File is too large to back up: $relative" }
            total = Math.addExact(total, beforeLength)
            check(total <= MAX_TOTAL_BYTES) { "Backup exceeds safety limit" }

            val target = File(snapshotRoot, relative).canonicalFile
            check(target.path.startsWith(snapshotRoot.canonicalPath + File.separator)) { "Unsafe snapshot path" }
            target.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileInputStream(source).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= beforeLength && copied <= MAX_ENTRY_BYTES) { "File changed during backup: $relative" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }
            check(copied == beforeLength) { "File changed during backup: $relative" }
            check(source.length() == beforeLength && source.lastModified() == beforeModified) {
                "File changed during backup: $relative"
            }
            target.setLastModified(beforeModified)
            SnapshotFile(relative, target, digest.digest().toHex(), beforeModified)
        }
    }

    private fun collectBackupFiles(): List<File> = canonicalRoot.walkTopDown()
        .filter { it.isFile }
        .filterNot { file ->
            val rel = relative(file)
            rel.startsWith("backups/") ||
                rel.startsWith("runtime/") ||
                rel.startsWith(".restore-") ||
                rel.startsWith(".minehost/plugin-transactions/") ||
                rel.startsWith(".minehost/plugin-rollbacks/") ||
                rel.startsWith(".minehost/pending-plugin-verification/") ||
                rel.endsWith(".part") ||
                rel.endsWith(".tmp") ||
                (file.parentFile?.canonicalFile == canonicalRoot && rel.endsWith(".jar", ignoreCase = true))
        }
        .sortedBy(::relative)
        .toList()

    private fun extractVerified(archive: File, destination: File, manifest: JSONObject) {
        val expected = manifest.getJSONArray("files")
        val expectedPaths = (0 until expected.length()).mapTo(linkedSetOf()) {
            normalizeEntryName(expected.getJSONObject(it).getString("path")) ?: error("Unsafe manifest path")
        }
        var total = 0L
        val extracted = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = normalizeEntryName(entry.name) ?: error("Unsafe archive entry: ${entry.name}")
                if (name == MANIFEST_ENTRY) {
                    zip.closeEntry()
                    continue
                }
                check(!entry.isDirectory) { "Directory entry is not permitted: $name" }
                check(name in expectedPaths && extracted.add(name)) { "Unexpected or duplicate archive entry: $name" }
                val target = File(destination, name).canonicalFile
                check(target.path.startsWith(destination.canonicalPath + File.separator)) { "Path traversal blocked" }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        total += count
                        check(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Archive safety limit exceeded" }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    runCatching { output.fd.sync() }
                }
                zip.closeEntry()
            }
        }
        check(extracted == expectedPaths) { "Archive did not extract the exact manifest file set" }
    }

    private fun verifyExtracted(root: File, manifest: JSONObject) {
        val expectedArray = manifest.getJSONArray("files")
        val expectedPaths = linkedSetOf<String>()
        for (index in 0 until expectedArray.length()) {
            val item = expectedArray.getJSONObject(index)
            val path = normalizeEntryName(item.getString("path")) ?: error("Unsafe manifest path")
            expectedPaths += path
            val file = File(root, path).canonicalFile
            check(file.path.startsWith(root.canonicalPath + File.separator) && file.isFile) { "Restored file missing: $path" }
            check(file.length() == item.getLong("size")) { "Restored file size mismatch: $path" }
            check(sha256(file).equals(item.getString("sha256"), true)) { "Restored checksum mismatch: $path" }
        }
        val actualPaths = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filterNot { it.startsWith("backups/") }
            .toSet()
        check(actualPaths == expectedPaths) { "Restored directory contains files outside the manifest" }
    }

    private fun restoreLegacyArchive(
        archive: File,
        currentMetadata: Metadata,
        createRecoveryBackup: Boolean,
    ): OperationResult {
        if (createRecoveryBackup) {
            val recovery = create(currentMetadata, "before-legacy-restore")
            if (!recovery.success) return OperationResult(false, "Legacy restore cancelled: ${recovery.message}")
        }
        val transaction = restoreTransactionDir
        val newRoot = File(transaction, "new-root")
        val oldRoot = File(transaction, "old-root")
        transaction.mkdirs()
        newRoot.mkdirs()
        return try {
            val extractedCount = extractLegacySafely(archive, newRoot)
            check(extractedCount > 0) { "Legacy backup contains no files" }
            check(
                File(newRoot, "server.properties").isFile ||
                    File(newRoot, "worlds").isDirectory ||
                    File(newRoot, ".minehost").isDirectory,
            ) { "Legacy archive does not look like a MineHost server backup" }
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.PREPARED)
            moveDirectory(canonicalRoot, oldRoot)
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.OLD_RENAMED)
            moveDirectory(newRoot, canonicalRoot)
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.NEW_INSTALLED)
            val oldBackups = File(oldRoot, "backups")
            if (oldBackups.exists()) moveDirectory(oldBackups, File(canonicalRoot, "backups"))
            writeJournal(transaction, currentMetadata.serverUuid, RestorePhase.COMMITTED)
            oldRoot.deleteRecursively()
            transaction.deleteRecursively()
            OperationResult(true, "Legacy backup restored. Its server ownership could not be cryptographically verified.")
        } catch (error: Throwable) {
            recoverInterruptedRestore()
            OperationResult(false, error.message ?: "Legacy restore failed")
        }
    }

    private fun extractLegacySafely(archive: File, destination: File): Int {
        var total = 0L
        var fileCount = 0
        val seen = hashSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = normalizeEntryName(entry.name) ?: error("Unsafe legacy backup entry: ${entry.name}")
                check(!normalized.startsWith("backups/", ignoreCase = true)) { "Nested backups are not restored" }
                check(seen.add(normalized)) { "Duplicate legacy backup entry: $normalized" }
                check(seen.size <= MAX_ENTRY_COUNT) { "Legacy archive contains too many entries" }
                val target = File(destination, normalized).canonicalFile
                check(target.path.startsWith(destination.canonicalPath + File.separator)) { "Path traversal blocked" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            total += count
                            check(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Legacy archive safety limit exceeded" }
                            output.write(buffer, 0, count)
                        }
                    }
                    fileCount++
                }
                zip.closeEntry()
            }
        }
        return fileCount
    }

    private fun writeJournal(transaction: File, serverUuid: String, phase: RestorePhase) {
        val journal = JSONObject()
            .put("serverUuid", serverUuid)
            .put("serverRoot", canonicalRoot.absolutePath)
            .put("phase", phase.name)
            .put("updatedAt", System.currentTimeMillis())
        val file = File(transaction, "journal.json")
        val part = File(transaction, "journal.json.part")
        part.writeText(journal.toString(2))
        atomicMove(part, file)
    }

    private fun readJournal(transaction: File): JSONObject? = runCatching {
        JSONObject(File(transaction, "journal.json").readText())
    }.getOrNull()

    private fun normalizeEntryName(raw: String): String? {
        val path = raw.replace('\\', '/').trimStart('/')
        if (path.isBlank() || path.endsWith('/')) return null
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
        if (path.contains('\u0000') || path.length > 4096) return null
        return parts.joinToString("/")
    }

    private fun relative(file: File): String = file.relativeTo(canonicalRoot).invariantSeparatorsPath

    private fun String.safeName(): String = lowercase()
        .replace(Regex("[^a-z0-9_-]"), "-")
        .take(32)
        .ifBlank { "manual" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun atomicMove(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (_: Throwable) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            }
        }
        if (target.exists() && !target.delete()) error("Unable to replace ${target.name}")
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            check(source.delete()) { "Unable to remove staging file ${source.name}" }
        }
    }

    private fun moveDirectory(source: File, target: File) {
        check(source.exists()) { "Source directory is missing: ${source.absolutePath}" }
        check(!target.exists()) { "Destination already exists: ${target.absolutePath}" }
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (_: Throwable) {
                Files.move(source.toPath(), target.toPath())
                return
            }
        }
        source.copyRecursively(target, overwrite = false)
        check(source.deleteRecursively()) { "Unable to remove source directory ${source.absolutePath}" }
    }
}
