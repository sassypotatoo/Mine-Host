package com.example.plugins

import com.example.data.OperationResult
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Engine-aware disable/remove operations. Actual unloading occurs only after the exact server restarts. */
class PluginLifecycleManager(
    private val serverUuid: String,
    private val engineId: String,
    private val serverRoot: File,
    private val createBackup: (String) -> OperationResult,
) {
    data class InstalledPlugin(
        val metadata: PluginMetadata,
        val file: File,
        val enabled: Boolean,
        val sha256: String,
        val configurationDirectory: File,
    )

    fun inspectInstalled(): List<Result<InstalledPlugin>> {
        val adapter = PluginAdapterRegistry.get(engineId)
            ?: return listOf(Result.failure(IllegalStateException("No plugin adapter exists for $engineId")))
        return adapter.installedPluginFiles(serverRoot).map { file ->
            runCatching {
                val normalizedJar = if (file.name.endsWith(".jar.disabled", true)) file else file
                val metadata = adapter.inspectMetadata(normalizedJar).getOrElse { throw it }
                InstalledPlugin(
                    metadata = metadata,
                    file = file,
                    enabled = file.name.endsWith(".jar", true) && !file.name.endsWith(".jar.disabled", true),
                    sha256 = sha256(file),
                    configurationDirectory = adapter.configDirectory(serverRoot, metadata.name),
                )
            }
        }
    }

    fun setEnabled(pluginName: String, enabled: Boolean): OperationResult {
        val adapter = PluginAdapterRegistry.get(engineId)
            ?: return OperationResult(false, "No plugin adapter exists for $engineId")
        val installed = inspectInstalled().mapNotNull(Result<InstalledPlugin>::getOrNull)
            .firstOrNull { it.metadata.name.equals(pluginName, true) }
            ?: return OperationResult(false, "Plugin is not installed: $pluginName")
        if (installed.enabled == enabled) return OperationResult(true, "Plugin is already ${if (enabled) "enabled" else "disabled"}")
        val backup = createBackup("before-plugin-${if (enabled) "enable" else "disable"}")
        if (!backup.success) return OperationResult(false, "Plugin change cancelled: ${backup.message}")

        val pluginDir = adapter.pluginDirectory(serverRoot).canonicalFile
        val source = installed.file.canonicalFile
        if (source.parentFile != pluginDir) return OperationResult(false, "Plugin path ownership could not be verified")
        val targetName = if (enabled) source.name.removeSuffix(".disabled") else source.name + ".disabled"
        val target = File(pluginDir, targetName).canonicalFile
        if (target.parentFile != pluginDir || target.exists()) return OperationResult(false, "Plugin destination is unsafe or already exists")
        return if (source.renameTo(target)) {
            writeLifecycleRecord(installed.metadata.name, if (enabled) "enable_pending_restart" else "disable_pending_restart", source, target)
            OperationResult(true, "Plugin ${if (enabled) "enabled" else "disabled"}; restart server $serverUuid to apply")
        } else OperationResult(false, "Unable to change plugin state")
    }

    fun remove(pluginName: String, removeConfiguration: Boolean): OperationResult {
        if (PluginAdapterRegistry.get(engineId) == null) {
            return OperationResult(false, "No plugin adapter exists for $engineId")
        }
        val installed = inspectInstalled().mapNotNull(Result<InstalledPlugin>::getOrNull)
            .firstOrNull { it.metadata.name.equals(pluginName, true) }
            ?: return OperationResult(false, "Plugin is not installed: $pluginName")
        val backup = createBackup("before-plugin-remove")
        if (!backup.success) return OperationResult(false, "Plugin removal cancelled: ${backup.message}")

        val transaction = File(serverRoot, ".minehost/plugin-removals/${UUID.randomUUID()}").apply { mkdirs() }
        val removedJar = File(transaction, installed.file.name)
        val config = installed.configurationDirectory
        val removedConfig = File(transaction, "config")
        return runCatching {
            moveOrCopy(installed.file, removedJar)
            if (removeConfiguration && config.exists()) moveOrCopy(config, removedConfig)
            writeLifecycleRecord(installed.metadata.name, "removed_pending_restart", removedJar, null)
            OperationResult(true, "Plugin removed from the load directory; restart server $serverUuid to confirm unload")
        }.getOrElse { error ->
            runCatching {
                if (removedJar.exists()) moveOrCopy(removedJar, installed.file)
                if (removedConfig.exists()) moveOrCopy(removedConfig, config)
            }
            OperationResult(false, error.message ?: "Plugin removal failed and rollback was attempted")
        }
    }

    private fun writeLifecycleRecord(pluginName: String, action: String, source: File, target: File?) {
        val dir = File(serverRoot, ".minehost/plugin-lifecycle").apply { mkdirs() }
        val file = File(dir, pluginName.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
        val part = File(dir, file.name + ".part")
        part.writeText(
            JSONObject()
                .put("server_uuid", serverUuid)
                .put("engine_id", engineId)
                .put("plugin_name", pluginName)
                .put("action", action)
                .put("source_relative", relativeOwnedPath(source))
                .put("target_relative", target?.let(::relativeOwnedPath) ?: JSONObject.NULL)
                .put("created_at", System.currentTimeMillis())
                .toString(2),
        )
        if (!part.renameTo(file)) {
            java.nio.file.Files.move(
                part.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun relativeOwnedPath(file: File): String {
        val root = serverRoot.canonicalFile
        val owned = file.canonicalFile
        require(owned.path.startsWith(root.path + File.separator)) { "Plugin lifecycle path is outside the server directory" }
        return owned.relativeTo(root).invariantSeparatorsPath
    }

    private fun moveOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        require(!target.exists()) { "Rollback target already exists: ${target.name}" }
        if (!source.renameTo(target)) {
            if (source.isDirectory) source.copyRecursively(target, overwrite = false) else source.copyTo(target, overwrite = false)
            require(source.deleteRecursively()) { "Unable to remove original ${source.name}" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
