package com.example.world

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Cross-component guard that prevents copying or mutating a live LevelDB world.
 *  Also persists lightweight server status and recent logs across Android process death. */
object ServerRunStateStore {
    private const val MAX_UNKNOWN_PID_AGE_MS = 2 * 60 * 1000L
    private const val MAX_PERSISTED_LOG_LINES = 200

    private fun marker(serverRoot: File): File =
        File(serverRoot, ".minehost/server-run-state.properties")

    private fun logFile(serverRoot: File): File =
        File(serverRoot, ".minehost/recent-logs.json")

    fun markRunning(serverRoot: File, sessionId: String, processId: Long?) {
        val destination = marker(serverRoot)
        destination.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty("sessionId", sessionId)
            setProperty("startedAt", System.currentTimeMillis().toString())
            processId?.takeIf { it > 0L }?.let { setProperty("processId", it.toString()) }
        }
        writeDurably(destination, properties)
    }

    fun isRunning(serverRoot: File): Boolean {
        val file = marker(serverRoot)
        if (!file.isFile) return false
        val properties = runCatching {
            Properties().apply { file.inputStream().use(::load) }
        }.getOrElse {
            // An unreadable marker is safety evidence, not permission to copy a live DB.
            return true
        }
        val pid = properties.getProperty("processId")?.toLongOrNull()
        if (pid != null && pid > 0L && File("/proc/$pid").exists()) return true
        val startedAt = properties.getProperty("startedAt")?.toLongOrNull() ?: return true
        if (pid == null && System.currentTimeMillis() - startedAt <= MAX_UNKNOWN_PID_AGE_MS) return true
        file.delete()
        return false
    }

    fun clearIfOwned(serverRoot: File, sessionId: String) {
        val file = marker(serverRoot)
        if (!file.isFile) return
        val owner = runCatching {
            Properties().apply { file.inputStream().use(::load) }
                .getProperty("sessionId")
        }.getOrNull()
        if (owner == sessionId) file.delete()
    }

    // ── Status persistence (survives process death) ─────────────────────

    data class PersistedServerState(
        val status: String,
        val engineId: String,
        val profileId: String,
    )

    fun persistStatus(serverRoot: File, status: String, engineId: String, profileId: String) {
        val destination = marker(serverRoot)
        destination.parentFile?.mkdirs()
        val properties = runCatching {
            Properties().apply { destination.inputStream().use(::load) }
        }.getOrDefault(Properties())
        properties.setProperty("persistedStatus", status)
        properties.setProperty("engineId", engineId)
        properties.setProperty("profileId", profileId)
        writeDurably(destination, properties)
    }

    fun readPersistedStatus(serverRoot: File): PersistedServerState? {
        val file = marker(serverRoot)
        if (!file.isFile) return null
        val properties = runCatching {
            Properties().apply { file.inputStream().use(::load) }
        }.getOrElse { return null }
        val status = properties.getProperty("persistedStatus") ?: return null
        val engineId = properties.getProperty("engineId") ?: return null
        val profileId = properties.getProperty("profileId") ?: return null
        return PersistedServerState(status, engineId, profileId)
    }

    // ── Log persistence (last N lines survive process death) ─────────────

    fun persistLogs(serverRoot: File, logs: List<String>) {
        val file = logFile(serverRoot)
        file.parentFile?.mkdirs()
        val recent = logs.takeLast(MAX_PERSISTED_LOG_LINES)
        val jsonArray = JSONArray(recent)
        val obj = JSONObject().apply {
            put("lines", jsonArray)
            put("count", recent.size)
        }
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.part")
        try {
            temporary.writeText(obj.toString())
            try {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (_: Exception) {
            temporary.delete()
        }
    }

    fun readPersistedLogs(serverRoot: File): List<String> {
        val file = logFile(serverRoot)
        if (!file.isFile) return emptyList()
        return runCatching {
            val text = file.readText().trim()
            if (text.isEmpty()) return@runCatching emptyList()
            val obj = JSONObject(text)
            val lines = obj.getJSONArray("lines")
            (0 until lines.length()).map { lines.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun clearPersistedLogs(serverRoot: File) {
        logFile(serverRoot).delete()
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun writeDurably(destination: File, properties: Properties) {
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, "MineHost running server guard")
                output.flush()
                try {
                    output.fd.sync()
                } catch (_: SyncFailedException) {
                    // Some emulated filesystems do not implement fsync. The
                    // atomic rename and ownership verification below still
                    // prevent a partial marker from being treated as valid.
                }
            }
            require(temporary.isFile && temporary.length() > 0L) {
                "Running-server guard was not written"
            }
            try {
                Files.move(
                    temporary.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            require(destination.isFile && destination.length() > 0L) {
                "Running-server guard could not be committed"
            }
        } finally {
            temporary.delete()
        }
    }
}
