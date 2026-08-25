package com.example.server.version

import android.content.Context
import com.example.BuildConfig
import com.example.backup.BackupManagerV2
import com.example.data.ServerProfile
import com.example.data.ServerProfileChanges
import com.example.data.ServerProfileRepository
import com.example.data.StartServerResult
import com.example.server.Downloader
import com.example.server.ServerManager
import com.example.server.ServerStatus
import com.example.server.canStart
import com.example.server.isBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/** Atomic engine update with backup, readiness health check and rollback. */
class EngineVersionTransactionManager(private val context: Context) {
    sealed class Result {
        data class Success(val message: String) : Result()
        data class Failure(val message: String, val rolledBack: Boolean) : Result()
    }

    suspend fun install(
        profile: ServerProfile,
        target: EngineVersion,
        profiles: ServerProfileRepository,
        serverManager: ServerManager,
        onProgress: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        if (target.engineId != profile.engineId) {
            return@withContext Result.Failure("Target build belongs to another engine", false)
        }
        if (target.minimumMineHostVersionCode > BuildConfig.VERSION_CODE) {
            return@withContext Result.Failure("This build requires a newer MineHost app", false)
        }
        val root = File(profile.serverDirectory)
        val backupManager = BackupManagerV2(root)
        val originalMetadata = BackupManagerV2.Metadata(
            serverUuid = profile.id,
            engineId = profile.engineId,
            engineVersionId = profile.engineVersionId,
            javaVersion = InstalledEngineVersionRepository.read(root)?.runtimeJavaVersion ?: target.runtimeJavaVersion,
            levelName = profile.levelName,
        )
        val backupCreation = backupManager.createWithResult(originalMetadata, "before-engine-update")
        val backup = backupCreation.result
        val rollbackArchive = backupCreation.archive
        if (!backup.success || rollbackArchive == null) {
            return@withContext Result.Failure("Update cancelled: ${backup.message}", false)
        }

        val transaction = File(root, ".minehost/version-transactions/${UUID.randomUUID()}").apply { mkdirs() }
        val stagedJar = File(transaction, target.jarFileName)
        val oldMetadataFile = File(root, ".minehost/engine-installation.json")
        val oldMetadata = oldMetadataFile.takeIf(File::isFile)?.readBytes()
        val oldInstalled = InstalledEngineVersionRepository.read(root)
        val oldJar = oldInstalled?.jarFileName?.let { File(root, it) }
        val savedOldJar = File(transaction, "old-engine.jar")
        val wasRunning = serverManager.getStatus(profile.id).isBlocking()

        try {
            if (wasRunning) {
                onProgress("Stopping ${profile.name} before engine update...")
                serverManager.stopServer(profile.id)
                val stopDeadline = System.currentTimeMillis() + 45_000
                while (!serverManager.getStatus(profile.id).canStart() && System.currentTimeMillis() < stopDeadline) delay(250)
                check(serverManager.getStatus(profile.id).canStart()) { "Server did not stop before update" }
            }

            val isPaper = target.engineId == "java_paper" || target.sourceType == VersionSourceType.PAPER_API
            val selectedMinecraftVersion = if (isPaper) {
                profile.bedrockVersion.trim().takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
                    ?: error("Paper update requires an exact Minecraft version.")
            } else {
                target.recommendedBedrockVersion ?: profile.bedrockVersion
            }

            onProgress("Downloading exact engine build ${target.displayName}...")
            val downloadRes = Downloader.downloadServerJar(
                context = context,
                version = target,
                destination = stagedJar,
                minecraftVersion = selectedMinecraftVersion,
            ) { onProgress(it) }
            val identity = (downloadRes as? com.example.server.ServerJarDownloadResult.Success)?.identity
            if (downloadRes !is com.example.server.ServerJarDownloadResult.Success) {
                val msg = (downloadRes as? com.example.server.ServerJarDownloadResult.Failure)?.message ?: "Engine download or JAR validation failed"
                error(msg)
            }

            if (isPaper && identity != null) {
                check(identity.engineId == "java_paper") { "Resolved engine ID mismatch: ${identity.engineId}" }
                check(identity.effectiveVersionId.startsWith("java_paper:${selectedMinecraftVersion}:")) {
                    "Resolved Paper build (${identity.effectiveVersionId}) does not match requested Minecraft version $selectedMinecraftVersion"
                }
            }

            val jarValidation = InstalledEngineVersionRepository.validateJar(stagedJar, target.launchMode, target.mainClass)
            check(jarValidation.valid) { jarValidation.error ?: "Staged engine JAR is invalid" }

            if (oldJar?.isFile == true) oldJar.copyTo(savedOldJar, overwrite = true)
            val destination = File(root, target.jarFileName)
            val part = File(root, "${target.jarFileName}.installing")
            stagedJar.copyTo(part, overwrite = true)
            if (destination.exists() && destination != oldJar) destination.delete()
            if (!part.renameTo(destination)) {
                part.copyTo(destination, overwrite = true)
                part.delete()
            }
            check(sha256(destination) == sha256(stagedJar)) { "Installed JAR checksum differs from staged JAR" }

            val targetJavaMajor = EngineRuntimeJavaPolicy.requiredMajor(target, selectedMinecraftVersion)

            val installed = EngineInstallationMetadataFactory.create(
                version = target,
                bedrockVersion = selectedMinecraftVersion,
                jarFile = destination,
                validation = jarValidation,
                resolvedIdentity = identity,
                runtimeJavaVersion = targetJavaMajor,
            )
            check(InstalledEngineVersionRepository.write(root, installed)) {
                "Unable to save installed engine metadata"
            }
            check(
                InstalledEngineVersionRepository.matches(
                    root,
                    target,
                    selectedMinecraftVersion,
                )
            ) {
                "Installed engine failed final integrity verification"
            }
            val newBedrockVersion = if (isPaper) profile.bedrockVersion else (target.recommendedBedrockVersion ?: profile.bedrockVersion)
            profiles.updateProfile(
                profile.id,
                ServerProfileChanges(
                    engineVersionId = target.id,
                    bedrockVersion = newBedrockVersion
                )
            ).getOrThrow()

            onProgress("Starting updated engine for a real readiness health check...")
            when (val start = serverManager.startServer(profile.id, profile.memoryMb)) {
                is StartServerResult.Started -> Unit
                is StartServerResult.OperationBlocked -> error(start.reason)
                is StartServerResult.ValidationFailed -> error(start.reason)
                is StartServerResult.Failed -> error(start.reason)
            }
            val healthDeadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < healthDeadline) {
                when (serverManager.getStatus(profile.id)) {
                    ServerStatus.ONLINE -> {
                        if (!wasRunning) {
                            onProgress(
                                "Readiness verified. Restoring the server to " +
                                    "its previous stopped state..."
                            )
                            serverManager.stopServer(profile.id)
                            val restoreStopDeadline =
                                System.currentTimeMillis() + 45_000
                            while (
                                !serverManager.getStatus(profile.id).canStart() &&
                                System.currentTimeMillis() < restoreStopDeadline
                            ) {
                                delay(250)
                            }
                            check(serverManager.getStatus(profile.id).canStart()) {
                                "Updated engine passed readiness, but the " +
                                    "temporary health-check process did not stop"
                            }
                        }

                        writeLastKnownGood(
                            root,
                            target,
                            sha256(destination),
                        )
                        transaction.deleteRecursively()
                        return@withContext Result.Success(
                            "${target.displayName} installed and readiness verified"
                        )
                    }
                    ServerStatus.CRASHED, ServerStatus.FAILED -> error("Updated engine failed its readiness health check")
                    else -> delay(500)
                }
            }
            error("Updated engine did not become ready before timeout")
        } catch (error: Throwable) {
            onProgress("Update failed: ${error.message}. Rolling back the complete server state...")
            runCatching { serverManager.stopServer(profile.id) }
            val stopDeadline = System.currentTimeMillis() + 30_000
            while (!serverManager.getStatus(profile.id).canStart() && System.currentTimeMillis() < stopDeadline) delay(250)
            val rolledBack = runCatching {
                check(serverManager.getStatus(profile.id).canStart()) { "Updated process did not stop before rollback" }

                val restoreResult = BackupManagerV2(root).restore(
                    archive = rollbackArchive,
                    currentMetadata = originalMetadata,
                    createRecoveryBackup = false,
                )
                check(restoreResult.success) { restoreResult.message }

                val current = File(root, target.jarFileName)
                if (current.exists() && current != oldJar) current.delete()
                if (oldJar != null && savedOldJar.isFile) {
                    savedOldJar.copyTo(oldJar, overwrite = true)
                }
                if (oldMetadata != null) {
                    oldMetadataFile.parentFile?.mkdirs()
                    oldMetadataFile.writeBytes(oldMetadata)
                } else oldMetadataFile.delete()
                profiles.updateProfile(
                    profile.id,
                    ServerProfileChanges(
                        engineVersionId = profile.engineVersionId,
                        bedrockVersion = profile.bedrockVersion,
                        levelName = profile.levelName,
                        port = profile.port,
                        memoryMb = profile.memoryMb,
                        maxPlayers = profile.maxPlayers,
                        onlineMode = profile.onlineMode,
                        autoRestart = profile.autoRestart,
                        autoBackup = profile.autoBackup,
                    ),
                ).getOrThrow()
                true
            }.getOrDefault(false)
            transaction.deleteRecursively()
            Result.Failure(error.message ?: "Engine update failed", rolledBack)
        }
    }

    private fun writeLastKnownGood(root: File, version: EngineVersion, sha: String) {
        val file = File(root, ".minehost/last-known-good-engine.json")
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("versionId", version.id)
                .put("engineId", version.engineId)
                .put("jarFileName", version.jarFileName)
                .put("sha256", sha)
                .put("verifiedAt", System.currentTimeMillis())
                .toString(2)
        )
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
}
