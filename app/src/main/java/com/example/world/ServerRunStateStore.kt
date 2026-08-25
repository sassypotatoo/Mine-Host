package com.example.world

import java.io.File
import java.io.FileOutputStream
import java.io.SyncFailedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/** Cross-component guard that prevents copying or mutating a live LevelDB world. */
object ServerRunStateStore {
    private const val MAX_UNKNOWN_PID_AGE_MS = 2 * 60 * 1000L

    private fun marker(serverRoot: File): File =
        File(serverRoot, ".minehost/server-run-state.properties")

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
