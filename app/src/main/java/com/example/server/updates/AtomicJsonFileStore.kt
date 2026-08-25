package com.example.server.updates

import android.os.Build
import android.util.Log
import com.example.data.StorageResult
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Small crash-resistant JSON store with atomic file operations.
 *
 * All instances pointing at the same canonical file share one lock, so callers
 * cannot interleave writes through different AtomicJsonFileStore objects.
 */
class AtomicJsonFileStore(private val file: File) {
    private val lock: ReentrantLock = lockFor(file)

    fun loadRaw(): StorageResult<String> = lock.withLock {
        if (!file.exists()) {
            return@withLock recoverFromBackup("Main file is missing") ?: StorageResult.Missing
        }

        try {
            val content = file.readText()
            if (isValidJson(content)) return@withLock StorageResult.Success(content)
            quarantineCorruptFile()
            recoverFromBackup("Main file contains invalid JSON")
                ?: StorageResult.Corrupt("Main file contains invalid JSON. No valid backup found.")
        } catch (error: Throwable) {
            quarantineCorruptFile()
            recoverFromBackup("File reading failed: ${error.message}")
                ?: StorageResult.Corrupt("File reading failed: ${error.message}. No valid backup found.", error)
        }
    }

    private fun recoverFromBackup(reason: String): StorageResult<String>? {
        val backup = backupFile()
        if (!backup.isFile) return null
        val content = runCatching { backup.readText() }.getOrNull() ?: return null
        if (!isValidJson(content)) return null

        return when (val persisted = saveLocked(content, preserveExistingBackup = false)) {
            is StorageResult.Success -> StorageResult.Recovered(
                content,
                "$reason. Recovered from backup and permanently restored the primary file.",
            )
            is StorageResult.Failure -> StorageResult.Corrupt(
                "$reason. A valid backup exists but could not be restored: ${persisted.message}",
                persisted.cause,
            )
            else -> StorageResult.Corrupt("$reason. Backup recovery returned an unexpected result.")
        }
    }

    fun save(content: String): StorageResult<Unit> = lock.withLock {
        saveLocked(content, preserveExistingBackup = true)
    }

    private fun saveLocked(content: String, preserveExistingBackup: Boolean): StorageResult<Unit> {
        val parent = file.parentFile ?: return StorageResult.Failure("File has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            return StorageResult.Failure("Failed to create directory: ${parent.absolutePath}")
        }
        if (!isValidJson(content)) {
            return StorageResult.Failure("Validation failed: content is not valid JSON")
        }

        val temp = File(parent, ".${file.name}.tmp-${Thread.currentThread().id}-${System.nanoTime()}")
        val backup = backupFile()

        return try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                runCatching { output.fd.sync() }
                    .onFailure { err -> runCatching { Log.w(TAG, "File sync failed for ${file.name}: ${err.message}") } }
            }

            val staged = temp.readText()
            if (staged != content || !isValidJson(staged)) {
                return StorageResult.Failure("Temporary file validation failed")
            }

            if (preserveExistingBackup && file.isFile) {
                val current = runCatching { file.readText() }.getOrNull()
                if (current != null && isValidJson(current)) {
                    copyReplacing(file, backup)
                }
            }

            moveReplacing(temp, file)
            if (!file.isFile) return StorageResult.Failure("Destination file missing after move")
            val reloaded = file.readText()
            if (reloaded != content || !isValidJson(reloaded)) {
                return StorageResult.Failure("Destination verification failed")
            }
            StorageResult.Success(Unit)
        } catch (error: Throwable) {
            runCatching { Log.e(TAG, "Save failed for ${file.name}: ${error.message}", error) }
            StorageResult.Failure("Storage error: ${error.message}", error)
        } finally {
            temp.delete()
        }
    }

    fun loadFromBackup(): String? = lock.withLock {
        backupFile().takeIf(File::isFile)?.let { backup ->
            runCatching { backup.readText() }.getOrNull()?.takeIf(::isValidJson)
        }
    }

    private fun backupFile(): File = File(file.parentFile, "${file.name}.bak")

    private fun quarantineCorruptFile() {
        if (!file.exists()) return
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val corrupt = File(file.parentFile, "${file.name}.corrupt-$timestamp")
        runCatching { moveReplacing(file, corrupt) }
            .onFailure { err -> runCatching { Log.e(TAG, "Failed to quarantine corrupt file: ${err.message}") } }
    }

    private fun isValidJson(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            val tokener = org.json.JSONTokener(trimmed)
            val value = tokener.nextValue()
            (value is org.json.JSONObject || value is org.json.JSONArray) && !tokener.more()
        }.getOrDefault(false)
    }

    private fun copyReplacing(source: File, target: File) {
        target.parentFile?.mkdirs()
        runCatching {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            source.copyTo(target, overwrite = true)
        }
    }

    private fun moveReplacing(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: Throwable) {
            try {
                Files.move(
                    source.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
                return
            } catch (_: Throwable) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }

    companion object {
        private const val TAG = "AtomicJsonFileStore"
        private val locks = ConcurrentHashMap<String, ReentrantLock>()

        private fun lockFor(file: File): ReentrantLock {
            val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            return locks.computeIfAbsent(key) { ReentrantLock() }
        }
    }
}
