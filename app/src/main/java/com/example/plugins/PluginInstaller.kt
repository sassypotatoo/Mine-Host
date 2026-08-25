package com.example.plugins

import com.example.data.OperationResult
import com.example.marketplace.CompatibilityConstraint
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class PluginInstaller(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val createBackup: (String) -> OperationResult
) {
    fun validate(request: PluginInstallRequest): List<String> = buildList {
        if (request.serverUuid.isBlank()) add("Server UUID is missing")
        val canonicalRoot = runCatching { request.serverDirectory.canonicalFile }.getOrNull()
        if (canonicalRoot == null || canonicalRoot.name != request.serverUuid) add("Server UUID does not own the selected plugin directory")
        val adapter = PluginAdapterRegistry.get(request.engineId)
        if (adapter == null) add("No plugin adapter exists for ${request.engineId}")
        val compatibility = request.plugin.compatibility[request.engineId]
        when (compatibility?.state) {
            null, CompatibilityState.UNKNOWN -> add("Compatibility is unknown for ${request.engineId}; installation is blocked")
            CompatibilityState.UNSUPPORTED -> add(compatibility.reason ?: "Plugin is unsupported on this engine")
            else -> listOf(
                "engine version" to CompatibilityConstraint.version(request.engineVersionId, compatibility.engineVersionRange),
                "Minecraft version" to CompatibilityConstraint.version(request.minecraftVersion, compatibility.minecraftVersionRange),
                "Java version" to CompatibilityConstraint.java(request.javaVersion, compatibility.javaRange),
            ).forEach { (label, check) ->
                if (!check.understood) add("$label compatibility could not be verified: ${check.reason}")
                else if (!check.matches) add("$label is outside the plugin compatibility range")
            }
        }
        if (request.plugin.downloadUrl.isBlank() || !request.plugin.downloadUrl.startsWith("https://")) add("Plugin download URL is not secure")
        if (!request.plugin.sha256.matches(Regex("[a-fA-F0-9]{64}"))) add("Plugin SHA-256 is invalid")
        if (request.plugin.fileSize <= 0L) add("Plugin file size is invalid")

        if (adapter != null) {
            val installedNames = adapter.installedPluginFiles(request.serverDirectory)
                .map { it.nameWithoutExtension.removeSuffix(".jar").lowercase() }
                .toSet()
            request.plugin.conflicts.filter { it.lowercase() in installedNames }
                .forEach { add("Conflicting plugin is installed: $it") }
            request.plugin.dependencies.filterNot { it.lowercase() in installedNames }
                .forEach { add("Required dependency is missing: $it") }
        }
    }

    fun install(request: PluginInstallRequest): PluginInstallResult {
        val errors = validate(request)
        if (errors.isNotEmpty()) return PluginInstallResult.Failure(errors.joinToString("\n"))
        val adapter = PluginAdapterRegistry.get(request.engineId)
            ?: return PluginInstallResult.Failure("No adapter for ${request.engineId}")
        val backup = createBackup("before-plugin-install")
        if (!backup.success) return PluginInstallResult.Failure("Installation cancelled: ${backup.message}")

        val pluginDir = adapter.pluginDirectory(request.serverDirectory).apply { mkdirs() }
        val transaction = File(request.serverDirectory, ".minehost/plugin-transactions/${UUID.randomUUID()}").apply { mkdirs() }
        val part = File(transaction, "download.jar.part")
        val staged = File(transaction, "validated.jar")
        val finalName = request.plugin.name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jar"
        val target = File(pluginDir, finalName)
        val rollbackDir = File(request.serverDirectory, ".minehost/plugin-rollbacks").apply { mkdirs() }
        val old = File(rollbackDir, "${finalName.removeSuffix(".jar")}-${System.currentTimeMillis()}.jar")

        return try {
            download(request.plugin, part)
            check(part.length() == request.plugin.fileSize) {
                "Downloaded size ${part.length()} does not match catalog size ${request.plugin.fileSize}"
            }
            check(sha256(part).equals(request.plugin.sha256, true)) { "Plugin SHA-256 verification failed" }
            if (!part.renameTo(staged)) part.copyTo(staged, overwrite = true)
            val metadata = adapter.inspectMetadata(staged).getOrElse { throw IllegalStateException(it.message ?: "Plugin metadata inspection failed") }
            val metadataErrors = adapter.validateMetadata(metadata)
            check(metadataErrors.isEmpty()) { metadataErrors.joinToString("\n") }
            check(metadata.name.equals(request.plugin.name, ignoreCase = true)) {
                "Catalog name ${request.plugin.name} does not match JAR metadata ${metadata.name}"
            }
            if (target.exists()) moveOrCopy(target, old)
            try {
                moveOrCopy(staged, target)
                check(target.isFile && sha256(target).equals(request.plugin.sha256, true)) { "Atomic plugin install verification failed" }
            } catch (error: Throwable) {
                target.delete()
                if (old.exists()) moveOrCopy(old, target)
                throw error
            }
            writePendingVerification(
                serverRoot = request.serverDirectory,
                serverUuid = request.serverUuid,
                engineId = request.engineId,
                pluginName = metadata.name,
                installedFile = target,
                rollbackFile = old.takeIf(File::exists),
                expectedSha256 = request.plugin.sha256,
            )
            transaction.deleteRecursively()
            PluginInstallResult.PendingVerification(
                "Plugin file installed and verified. Restart server ${request.serverUuid}; success will be confirmed from real startup logs.",
                target
            )
        } catch (error: Throwable) {
            if (!target.exists() && old.exists()) runCatching { moveOrCopy(old, target) }
            transaction.deleteRecursively()
            PluginInstallResult.Failure(error.message ?: "Plugin installation failed", rollbackAvailable = target.exists())
        }
    }

    fun verifyAfterRestart(request: PluginInstallRequest, logs: List<String>): PluginInstallResult {
        val adapter = PluginAdapterRegistry.get(request.engineId)
            ?: return PluginInstallResult.Failure("No adapter for ${request.engineId}")
        val target = File(adapter.pluginDirectory(request.serverDirectory), request.plugin.name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jar")
        if (!target.isFile) return PluginInstallResult.Failure("Installed plugin file is missing")
        val metadata = adapter.inspectMetadata(target).getOrElse { return PluginInstallResult.Failure(it.message ?: "Plugin metadata unavailable") }
        val verification = adapter.verifyStartup(metadata, logs)
        return when {
            verification.failed -> PluginInstallResult.Failure("Plugin failed to load:\n${verification.evidence.joinToString("\n")}", rollbackAvailable = true)
            verification.loaded -> {
                clearPendingVerification(request.serverDirectory, metadata.name, deleteRollback = true)
                PluginInstallResult.Success("Plugin ${metadata.name} loaded successfully", target, request.plugin.restartRequired)
            }
            else -> PluginInstallResult.PendingVerification("No authoritative load line was found yet", target)
        }
    }

    fun installLocal(request: LocalPluginInstallRequest): PluginInstallResult {
        val adapter = PluginAdapterRegistry.get(request.engineId)
            ?: return PluginInstallResult.Failure("No plugin adapter exists for ${request.engineId}")
        val source = request.sourceJar
        if (!source.isFile || !source.extension.equals("jar", true) || source.length() !in 1..(512L * 1024 * 1024)) {
            return PluginInstallResult.Failure("Selected plugin JAR is invalid")
        }
        val metadata = adapter.inspectMetadata(source).getOrElse {
            return PluginInstallResult.Failure(it.message ?: "Plugin metadata inspection failed")
        }
        val errors = adapter.validateMetadata(metadata).toMutableList()
        val installedNames = adapter.installedPluginFiles(request.serverDirectory)
            .map { it.name.removeSuffix(".disabled").removeSuffix(".jar").lowercase() }.toSet()
        metadata.dependencies.filterNot { it.lowercase() in installedNames }
            .forEach { errors += "Required dependency is missing: $it" }
        if (errors.isNotEmpty()) return PluginInstallResult.Failure(errors.joinToString("\n"))

        val backup = createBackup("before-local-plugin-install")
        if (!backup.success) return PluginInstallResult.Failure("Installation cancelled: ${backup.message}")
        val pluginDir = adapter.pluginDirectory(request.serverDirectory).apply { mkdirs() }
        val finalName = metadata.name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".jar"
        val target = File(pluginDir, finalName)
        val transaction = File(request.serverDirectory, ".minehost/plugin-transactions/${UUID.randomUUID()}").apply { mkdirs() }
        val staged = File(transaction, "plugin.jar.part")
        val rollback = File(request.serverDirectory, ".minehost/plugin-rollbacks/${finalName.removeSuffix(".jar")}-${System.currentTimeMillis()}.jar")
        return try {
            source.copyTo(staged, overwrite = true)
            val hash = sha256(staged)
            check(adapter.inspectMetadata(staged).isSuccess) { "Staged plugin validation failed" }
            if (target.exists()) moveOrCopy(target, rollback)
            try {
                moveOrCopy(staged, target)
                check(target.isFile && sha256(target) == hash) { "Installed plugin checksum verification failed" }
            } catch (error: Throwable) {
                target.delete()
                if (rollback.exists()) moveOrCopy(rollback, target)
                throw error
            }
            writePendingVerification(
                request.serverDirectory, request.serverUuid, request.engineId, metadata.name,
                target, rollback.takeIf(File::exists), hash,
            )
            transaction.deleteRecursively()
            PluginInstallResult.PendingVerification(
                "${metadata.name} was installed atomically. Restart the exact server to verify real plugin startup logs.",
                target,
            )
        } catch (error: Throwable) {
            transaction.deleteRecursively()
            PluginInstallResult.Failure(error.message ?: "Local plugin installation failed", rollbackAvailable = rollback.exists())
        }
    }

    fun verifyAllPending(serverRoot: File, engineId: String, logs: List<String>): List<PluginInstallResult> {
        val adapter = PluginAdapterRegistry.get(engineId)
            ?: return listOf(PluginInstallResult.Failure("No plugin adapter exists for $engineId"))
        val dir = pendingDirectory(serverRoot)
        return dir.listFiles().orEmpty().filter { it.isFile && it.extension.equals("json", true) }.map { file ->
            runCatching {
                val pending = JSONObject(file.readText())
                require(pending.getString("server_uuid") == serverRoot.canonicalFile.name) { "Pending plugin server UUID mismatch" }
                require(pending.getString("engine_id") == engineId) { "Pending plugin engine mismatch" }
                val pluginName = pending.getString("plugin_name")
                val installed = resolveOwnedPath(serverRoot, pending.getString("installed_relative"), "plugins")
                require(installed.isFile) { "Installed file for $pluginName is missing" }
                require(sha256(installed).equals(pending.getString("sha256"), true)) {
                    "Installed file for $pluginName was modified after installation"
                }
                val metadata = adapter.inspectMetadata(installed).getOrElse { throw it }
                val verification = adapter.verifyStartup(metadata, logs)
                when {
                    verification.failed -> PluginInstallResult.Failure(
                        "$pluginName failed to load:\n${verification.evidence.joinToString("\n")}",
                        rollbackAvailable = true,
                    )
                    verification.loaded -> {
                        clearPendingVerification(serverRoot, pluginName, deleteRollback = true)
                        PluginInstallResult.Success("$pluginName loaded successfully", installed, true)
                    }
                    else -> PluginInstallResult.PendingVerification(
                        "No authoritative startup evidence found yet for $pluginName", installed,
                    )
                }
            }.getOrElse { PluginInstallResult.Failure(it.message ?: "Plugin verification failed", rollbackAvailable = true) }
        }
    }

    fun rollbackPending(serverRoot: File, pluginName: String): PluginInstallResult {
        val pending = readPendingVerification(serverRoot, pluginName)
            ?: return PluginInstallResult.Failure("Pending plugin transaction not found")
        return runCatching {
            require(pending.getString("server_uuid") == serverRoot.canonicalFile.name) { "Pending plugin server UUID mismatch" }
            val installed = resolveOwnedPath(serverRoot, pending.getString("installed_relative"), "plugins")
            val rollbackRelative = pending.optString("rollback_relative")
            if (installed.exists()) require(installed.delete()) { "Unable to remove the failed plugin" }
            if (rollbackRelative.isNotBlank()) {
                val rollback = resolveOwnedPath(serverRoot, rollbackRelative, ".minehost/plugin-rollbacks")
                require(rollback.isFile) { "Rollback plugin file is missing" }
                moveOrCopy(rollback, installed)
            }
            clearPendingVerification(serverRoot, pluginName, deleteRollback = false)
            PluginInstallResult.Success("Plugin rollback completed; restart required", installed, true)
        }.getOrElse { PluginInstallResult.Failure(it.message ?: "Plugin rollback failed") }
    }

    private fun pendingDirectory(serverRoot: File): File =
        File(serverRoot, ".minehost/pending-plugin-verification").apply { mkdirs() }

    private fun pendingFile(serverRoot: File, pluginName: String): File =
        File(pendingDirectory(serverRoot), pluginName.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    private fun writePendingVerification(
        serverRoot: File,
        serverUuid: String,
        engineId: String,
        pluginName: String,
        installedFile: File,
        rollbackFile: File?,
        expectedSha256: String,
    ) {
        val file = pendingFile(serverRoot, pluginName)
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(
            JSONObject()
                .put("server_uuid", serverUuid)
                .put("engine_id", engineId)
                .put("plugin_name", pluginName)
                .put("installed_relative", installedFile.canonicalFile.relativeTo(serverRoot.canonicalFile).invariantSeparatorsPath)
                .put("rollback_relative", rollbackFile?.canonicalFile?.relativeTo(serverRoot.canonicalFile)?.invariantSeparatorsPath ?: "")
                .put("sha256", expectedSha256)
                .put("installed_at", System.currentTimeMillis())
                .toString(2),
        )
        if (file.exists()) file.delete()
        if (!part.renameTo(file)) { part.copyTo(file, overwrite = true); part.delete() }
    }

    private fun readPendingVerification(serverRoot: File, pluginName: String): JSONObject? = runCatching {
        val file = pendingFile(serverRoot, pluginName)
        if (file.isFile) JSONObject(file.readText()) else null
    }.getOrNull()

    private fun clearPendingVerification(serverRoot: File, pluginName: String, deleteRollback: Boolean) {
        val pending = readPendingVerification(serverRoot, pluginName)
        if (deleteRollback) {
            pending?.optString("rollback_relative")?.takeIf(String::isNotBlank)?.let { relative ->
                runCatching { resolveOwnedPath(serverRoot, relative, ".minehost/plugin-rollbacks").delete() }
            }
        }
        pendingFile(serverRoot, pluginName).delete()
    }

    private fun resolveOwnedPath(serverRoot: File, relative: String, requiredPrefix: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/') && !relative.startsWith('\\')) { "Stored plugin path is invalid" }
        require(relative.split('/', '\\').none { it == ".." || it.isBlank() }) { "Stored plugin path is unsafe" }
        val base = serverRoot.canonicalFile
        val target = File(base, relative).canonicalFile
        val prefix = File(base, requiredPrefix).canonicalFile
        require(target.path.startsWith(prefix.path + File.separator)) { "Stored plugin path is outside the allowed directory" }
        return target
    }

    private fun download(plugin: MarketplacePlugin, destination: File) {
        val request = Request.Builder().url(plugin.downloadUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Plugin download failed: HTTP ${response.code}" }
            val body = response.body ?: error("Plugin download returned no body")
            val contentLength = body.contentLength()
            if (contentLength > 0) check(contentLength == plugin.fileSize) { "Server content length differs from catalog" }
            FileOutputStream(destination).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= plugin.fileSize) { "Plugin download exceeded catalog size" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()
        if (!source.renameTo(target)) {
            if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
            check(source.deleteRecursively()) { "Unable to remove staging file ${source.name}" }
        }
    }
}
