package com.example.world

import android.content.Context
import android.util.Log
import com.example.backup.BackupManagerV2
import com.example.data.ServerProfile
import com.example.data.ServerProfileChanges
import com.example.data.ServerProfileRepository
import com.example.data.StorageResult
import com.example.server.engine.ConfigAdapterFactory
import com.example.server.engine.EngineServerConfig
import com.example.server.engine.WorldSeedMode
import com.example.server.updates.AtomicJsonFileStore
import com.example.server.version.EngineVersion
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

enum class RegenerationState {
    PREPARED,
    BACKUP_VERIFIED,
    WORLD_QUARANTINED,
    PROFILE_UPDATED,
    WAITING_FOR_NEW_SESSION,
    NEW_SESSION_STARTED,
    WAITING_FOR_ONLINE,
    IDENTITY_VERIFIED,
    TERRAIN_VERIFIED,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    RECOVERY_REQUIRED
}

data class RegenerationJournal(
    val transactionId: String,
    val profileId: String,
    val worldName: String,
    val oldProfileJson: String,
    val newProfileJson: String,
    val oldSeed: Long,
    val oldSeedMode: WorldSeedMode,
    val oldSeedKnown: Boolean,
    val newSeed: Long,
    val newSeedMode: WorldSeedMode,
    val newSeedKnown: Boolean = true,
    val verifiedBackupId: String?,
    val backupArchivePath: String?,
    val backupSize: Long?,
    val propertiesBackupPath: String?,
    val oldGenerationMarkerSnapshot: String?,
    val oldOwnershipMarkerSnapshot: String?,
    val oldImportedOwnershipSnapshot: String?,
    val expectedGeneratorId: String,
    val expectedGeneratorRevision: String,
    val replacementRuntimeSessionId: String?,
    val state: RegenerationState,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object WorldRegenerationManager {

    fun getJournalFile(serverDir: File, transactionId: String): File =
        File(serverDir, ".minehost/transactions/regeneration-$transactionId.json")

    private fun writeJournal(serverDir: File, journal: RegenerationJournal): Boolean {
        val file = getJournalFile(serverDir, journal.transactionId)
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("transactionId", journal.transactionId)
            put("profileId", journal.profileId)
            put("worldName", journal.worldName)
            put("oldProfileJson", journal.oldProfileJson)
            put("newProfileJson", journal.newProfileJson)
            put("oldSeed", journal.oldSeed)
            put("oldSeedMode", journal.oldSeedMode.name)
            put("oldSeedKnown", journal.oldSeedKnown)
            put("newSeed", journal.newSeed)
            put("newSeedMode", journal.newSeedMode.name)
            put("newSeedKnown", journal.newSeedKnown)
            put("verifiedBackupId", journal.verifiedBackupId)
            put("backupArchivePath", journal.backupArchivePath)
            put("backupSize", journal.backupSize ?: -1L)
            put("propertiesBackupPath", journal.propertiesBackupPath)
            put("oldGenerationMarkerSnapshot", journal.oldGenerationMarkerSnapshot)
            put("oldOwnershipMarkerSnapshot", journal.oldOwnershipMarkerSnapshot)
            put("oldImportedOwnershipSnapshot", journal.oldImportedOwnershipSnapshot)
            put("expectedGeneratorId", journal.expectedGeneratorId)
            put("expectedGeneratorRevision", journal.expectedGeneratorRevision)
            put("replacementRuntimeSessionId", journal.replacementRuntimeSessionId)
            put("state", journal.state.name)
            put("lastError", journal.lastError)
            put("createdAt", journal.createdAt)
            put("updatedAt", journal.updatedAt)
        }
        val store = AtomicJsonFileStore(file)
        if (store.save(json.toString(2)) !is StorageResult.Success) return false
        
        // Reread and match expected state (Stage 17)
        val readBack = (store.loadRaw() as? StorageResult.Success)?.value ?: return false
        val readObj = JSONObject(readBack)
        return readObj.optString("state") == journal.state.name && readObj.optString("transactionId") == journal.transactionId
    }

    suspend fun regenerateExact(
        context: Context,
        serverId: String,
        worldName: String,
        profileRepository: ServerProfileRepository,
        engineVersion: EngineVersion
    ): Result<ServerProfile> {
        val profile = profileRepository.profiles.value.find { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException("Profile not found"))
        
        return regenerateWorld(
            context = context,
            serverId = serverId,
            worldName = worldName,
            newSeed = profile.worldSeed,
            newSeedMode = profile.worldSeedMode,
            profileRepository = profileRepository,
            engineVersion = engineVersion
        )
    }

    suspend fun regenerateWorld(
        context: Context,
        serverId: String,
        worldName: String,
        newSeed: Long,
        newSeedMode: WorldSeedMode,
        profileRepository: ServerProfileRepository,
        engineVersion: EngineVersion
    ): Result<ServerProfile> {
        val profile = profileRepository.profiles.value.find { it.id == serverId }
            ?: return Result.failure(IllegalArgumentException("Profile not found"))
            
        val serverDir = File(profile.serverDirectory)

        if (ServerRunStateStore.isRunning(serverDir)) {
            return Result.failure(IllegalStateException("Cannot regenerate world while the server is running. Please stop the server first."))
        }

        val levelName = profile.levelName
        if (levelName != worldName) {
            return Result.failure(IllegalArgumentException("Regeneration target world name '$worldName' does not match the active world '$levelName'"))
        }
        val worldDir = File(serverDir, "worlds/$levelName")

        val genIdentity = EngineGeneratorIdentityResolver.resolve(profile.engineId, engineVersion, profile.levelType, profile.resolvedIdentity)

        val txId = UUID.randomUUID().toString()
        val quarantineDir = File(serverDir, ".minehost/quarantine/$txId-$levelName")
        quarantineDir.parentFile?.mkdirs()

        val propsFile = File(serverDir, "server.properties")
        val propsBackup = File(serverDir, ".minehost/transactions/$txId-server.properties.bak")

        val newProfileChanges = ServerProfileChanges(
            worldSeed = newSeed,
            worldSeedMode = newSeedMode,
            worldSeedKnown = true
        )
        val newProfileJson = JSONObject(profile.toJson()).apply {
            put("worldSeed", newSeed)
            put("worldSeedMode", newSeedMode.name)
            put("worldSeedKnown", true)
        }.toString(2)

        var journal = RegenerationJournal(
            transactionId = txId,
            profileId = profile.id,
            worldName = levelName,
            oldProfileJson = profile.toJson(),
            newProfileJson = newProfileJson,
            oldSeed = profile.worldSeed,
            oldSeedMode = profile.worldSeedMode,
            oldSeedKnown = profile.worldSeedKnown,
            newSeed = newSeed,
            newSeedMode = newSeedMode,
            newSeedKnown = true,
            verifiedBackupId = null,
            backupArchivePath = null,
            backupSize = null,
            propertiesBackupPath = propsBackup.absolutePath,
            oldGenerationMarkerSnapshot = null,
            oldOwnershipMarkerSnapshot = null,
            oldImportedOwnershipSnapshot = null,
            expectedGeneratorId = genIdentity.id,
            expectedGeneratorRevision = genIdentity.revision,
            replacementRuntimeSessionId = null,
            state = RegenerationState.PREPARED
        )
        if (!writeJournal(serverDir, journal)) {
            return Result.failure(IOException("Failed to create and verify regeneration transaction journal"))
        }

        // Mandatory pre-regeneration backup and verification (Stage 16)
        val backupManager = BackupManagerV2(serverDir)
        val backupMeta = BackupManagerV2.Metadata(
            serverUuid = profile.id,
            engineId = profile.engineId,
            engineVersionId = profile.engineVersionId,
            javaVersion = 17,
            levelName = levelName
        )
        val backupRes = backupManager.createWithResult(backupMeta, "pre-regeneration-$txId")
        if (!backupRes.result.success || backupRes.archive == null || !backupRes.archive.isFile) {
            journal = journal.copy(state = RegenerationState.ROLLED_BACK, lastError = "Backup failed: ${backupRes.result.message}", updatedAt = System.currentTimeMillis())
            writeJournal(serverDir, journal)
            return Result.failure(IOException("Mandatory pre-regeneration backup failed: ${backupRes.result.message}"))
        }
        
        val verification = backupManager.verify(backupRes.archive, profile.id)
        if (!verification.valid) {
            journal = journal.copy(state = RegenerationState.ROLLED_BACK, lastError = "Backup verification failed: ${verification.message}", updatedAt = System.currentTimeMillis())
            writeJournal(serverDir, journal)
            return Result.failure(IOException("Mandatory pre-regeneration backup verification failed: ${verification.message}"))
        }

        val backupId = verification.manifest?.optString("backupId")
        journal = journal.copy(
            verifiedBackupId = backupId,
            backupArchivePath = backupRes.archive.absolutePath,
            backupSize = backupRes.archive.length(),
            state = RegenerationState.BACKUP_VERIFIED,
            updatedAt = System.currentTimeMillis()
        )
        writeJournal(serverDir, journal)

        if (propsFile.exists()) {
            propsFile.copyTo(propsBackup, overwrite = true)
        }

        // Snapshots of old markers (Stage 17)
        val oldGenMarkerFileRes = WorldGenerationMarkerManager.getMarkerFile(serverDir, levelName)
        val oldGenMarkerSnapshot = if (oldGenMarkerFileRes.isSuccess && oldGenMarkerFileRes.getOrThrow().isFile) {
            oldGenMarkerFileRes.getOrThrow().readText()
        } else null
        
        // External markers for ownership
        val worldKey = MessageDigest.getInstance("SHA-256")
            .digest(levelName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            
        val oldOwnershipMarkerFile = File(serverDir, ".minehost/engine-generated-worlds/$worldKey.properties")
        val oldOwnershipMarkerSnapshot = if (oldOwnershipMarkerFile.isFile) oldOwnershipMarkerFile.readText() else null
        
        val oldImportedOwnershipFile = File(serverDir, ".minehost/imported-worlds/$worldKey.properties")
        val oldImportedOwnershipSnapshot = if (oldImportedOwnershipFile.isFile) oldImportedOwnershipFile.readText() else null
        
        journal = journal.copy(
            oldGenerationMarkerSnapshot = oldGenMarkerSnapshot,
            oldOwnershipMarkerSnapshot = oldOwnershipMarkerSnapshot,
            oldImportedOwnershipSnapshot = oldImportedOwnershipSnapshot,
            updatedAt = System.currentTimeMillis()
        )
        writeJournal(serverDir, journal)

        // Move world into quarantine
        if (worldDir.exists()) {
            if (!worldDir.renameTo(quarantineDir)) {
                journal = journal.copy(state = RegenerationState.ROLLED_BACK, lastError = "Could not move world directory to quarantine", updatedAt = System.currentTimeMillis())
                writeJournal(serverDir, journal)
                return Result.failure(IOException("Could not move world directory to quarantine"))
            }
        }

        journal = journal.copy(state = RegenerationState.WORLD_QUARANTINED, updatedAt = System.currentTimeMillis())
        writeJournal(serverDir, journal)

        // Update profile in repository
        val updateRes = profileRepository.updateProfile(profile.id, newProfileChanges)

        if (updateRes.isFailure) {
            rollback(serverDir, profile, journal, profileRepository, propsBackup, quarantineDir, worldDir)
            return updateRes
        }

        val updatedProfile = updateRes.getOrThrow()

        journal = journal.copy(state = RegenerationState.PROFILE_UPDATED, updatedAt = System.currentTimeMillis())
        writeJournal(serverDir, journal)

        // Reconfigure engine properties
        try {
            val config = EngineServerConfig(
                port = updatedProfile.port,
                onlineMode = updatedProfile.onlineMode,
                levelName = updatedProfile.levelName,
                worldSeed = updatedProfile.worldSeed,
                worldSeedKnown = true,
                gameMode = updatedProfile.gameMode,
                difficulty = updatedProfile.difficulty,
                levelType = updatedProfile.levelType
            )
            val adapter = ConfigAdapterFactory.getAdapter(updatedProfile.engineId)
            adapter.applyConfig(serverDir, config)
        } catch (error: Throwable) {
            rollback(serverDir, profile, journal, profileRepository, propsBackup, quarantineDir, worldDir)
            return Result.failure(IOException("Failed to apply new seed configuration: ${error.message}", error))
        }

        journal = journal.copy(state = RegenerationState.WAITING_FOR_NEW_SESSION, updatedAt = System.currentTimeMillis())
        writeJournal(serverDir, journal)

        return Result.success(updatedProfile)
    }

    suspend fun commitRegeneration(serverDir: File, transactionId: String) {
        val file = getJournalFile(serverDir, transactionId)
        if (!file.exists()) return
        val store = AtomicJsonFileStore(file)
        val jsonStr = (store.loadRaw() as? StorageResult.Success)?.value ?: return
        val obj = JSONObject(jsonStr)
        val stateStr = obj.optString("state")
        if (stateStr == RegenerationState.COMMITTED.name || stateStr == RegenerationState.ROLLED_BACK.name) return

        val journal = parseJournal(obj)
        val worldName = journal.worldName
        val worldDir = File(serverDir, "worlds/$worldName")

        // Stage 21: Hardened Terrain Verification (Part 6)
        if (worldDir.isDirectory && File(worldDir, "level.dat").isFile && File(worldDir, "db").isDirectory) {
            val inspector = BedrockWorldInspector()
            val inspection = inspector.inspect(worldDir)
            val result = inspection.getOrNull()
            if (result != null) {
                val nonAirBlocks = result.report.blockPalette.blockNameCounts
                    .filter { it.key != "minecraft:air" && it.key != "air" }
                    .values.sum()
                
                if (nonAirBlocks >= 50) {
                    writeJournal(serverDir, journal.copy(state = RegenerationState.TERRAIN_VERIFIED, updatedAt = System.currentTimeMillis()))
                } else {
                    Log.w("WorldRegen", "Terrain verification warning: world '$worldName' has only $nonAirBlocks non-air blocks. It might be empty or still generating.")
                }
            }
        }

        writeJournal(serverDir, journal.copy(state = RegenerationState.COMMITTED, updatedAt = System.currentTimeMillis()))
        // Note: Quarantined world and backup are retained per requirements.
    }

    suspend fun commitRegenerationForWorld(
        serverDir: File,
        worldName: String,
        seed: Long,
        seedKnown: Boolean,
        generatorId: String,
        generatorRevision: String
    ) {
        val txDir = File(serverDir, ".minehost/transactions")
        if (!txDir.isDirectory) return
        val files = txDir.listFiles() ?: return
        for (file in files) {
            if (!file.name.startsWith("regeneration-") || !file.name.endsWith(".json")) continue
            val store = AtomicJsonFileStore(file)
            val jsonStr = (store.loadRaw() as? StorageResult.Success)?.value ?: continue
            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
            if (obj.optString("worldName") == worldName) {
                val state = runCatching { RegenerationState.valueOf(obj.getString("state")) }.getOrNull()
                if (state == RegenerationState.WAITING_FOR_NEW_SESSION || state == RegenerationState.NEW_SESSION_STARTED || state == RegenerationState.WAITING_FOR_ONLINE) {
                    // Stage 18: Verify all identity gates
                    val match = obj.optLong("newSeed") == seed &&
                            obj.optBoolean("newSeedKnown") == seedKnown &&
                            obj.optString("expectedGeneratorId") == generatorId &&
                            obj.optString("expectedGeneratorRevision") == generatorRevision
                            
                    if (match) {
                        val txId = obj.getString("transactionId")
                        val journal = parseJournal(obj)
                        writeJournal(serverDir, journal.copy(state = RegenerationState.IDENTITY_VERIFIED, updatedAt = System.currentTimeMillis()))
                        commitRegeneration(serverDir, txId)
                    }
                }
            }
        }
    }

    private fun parseJournal(obj: JSONObject): RegenerationJournal {
        return RegenerationJournal(
            transactionId = obj.getString("transactionId"),
            profileId = obj.getString("profileId"),
            worldName = obj.getString("worldName"),
            oldProfileJson = obj.getString("oldProfileJson"),
            newProfileJson = obj.getString("newProfileJson"),
            oldSeed = obj.getLong("oldSeed"),
            oldSeedMode = WorldSeedMode.valueOf(obj.getString("oldSeedMode")),
            oldSeedKnown = obj.getBoolean("oldSeedKnown"),
            newSeed = obj.getLong("newSeed"),
            newSeedMode = WorldSeedMode.valueOf(obj.getString("newSeedMode")),
            newSeedKnown = obj.optBoolean("newSeedKnown", true),
            verifiedBackupId = obj.optString("verifiedBackupId").takeIf { it.isNotBlank() },
            backupArchivePath = obj.optString("backupArchivePath").takeIf { it.isNotBlank() },
            backupSize = obj.optLong("backupSize", -1).takeIf { it >= 0 },
            propertiesBackupPath = obj.optString("propertiesBackupPath").takeIf { it.isNotBlank() },
            oldGenerationMarkerSnapshot = obj.optString("oldGenerationMarkerSnapshot").takeIf { it.isNotBlank() },
            oldOwnershipMarkerSnapshot = obj.optString("oldOwnershipMarkerSnapshot").takeIf { it.isNotBlank() },
            oldImportedOwnershipSnapshot = obj.optString("oldImportedOwnershipSnapshot").takeIf { it.isNotBlank() },
            expectedGeneratorId = obj.getString("expectedGeneratorId"),
            expectedGeneratorRevision = obj.getString("expectedGeneratorRevision"),
            replacementRuntimeSessionId = obj.optString("replacementRuntimeSessionId").takeIf { it.isNotBlank() },
            state = RegenerationState.valueOf(obj.getString("state")),
            lastError = obj.optString("lastError").takeIf { it.isNotBlank() },
            createdAt = obj.getLong("createdAt"),
            updatedAt = obj.getLong("updatedAt")
        )
    }

    private suspend fun rollback(
        serverDir: File,
        originalProfile: ServerProfile,
        journal: RegenerationJournal,
        repository: ServerProfileRepository,
        propsBackup: File,
        quarantineDir: File,
        worldDir: File
    ) {
        writeJournal(serverDir, journal.copy(state = RegenerationState.ROLLING_BACK, updatedAt = System.currentTimeMillis()))
        repository.updateProfile(
            originalProfile.id,
            ServerProfileChanges(
                worldSeed = journal.oldSeed,
                worldSeedMode = journal.oldSeedMode,
                worldSeedKnown = journal.oldSeedKnown
            )
        )
        if (propsBackup.exists()) {
            val propsFile = File(serverDir, "server.properties")
            propsBackup.copyTo(propsFile, overwrite = true)
            propsBackup.delete()
        }
        if (quarantineDir.exists()) {
            if (worldDir.exists()) worldDir.deleteRecursively()
            quarantineDir.renameTo(worldDir)
        }
        writeJournal(serverDir, journal.copy(state = RegenerationState.ROLLED_BACK, updatedAt = System.currentTimeMillis()))
    }

    suspend fun recoverIncompleteTransactions(
        serverDir: File,
        profile: ServerProfile,
        repository: ServerProfileRepository
    ) {
        val txDir = File(serverDir, ".minehost/transactions")
        if (!txDir.isDirectory) return
        val files = txDir.listFiles() ?: return
        for (file in files) {
            if (!file.name.startsWith("regeneration-") || !file.name.endsWith(".json")) continue
            val store = AtomicJsonFileStore(file)
            val jsonStr = (store.loadRaw() as? StorageResult.Success)?.value ?: continue
            val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
            val state = runCatching { RegenerationState.valueOf(obj.getString("state")) }.getOrNull() ?: continue
            if (state == RegenerationState.COMMITTED || state == RegenerationState.ROLLED_BACK) continue

            val txId = obj.getString("transactionId")
            val worldName = obj.getString("worldName")
            val quarantineDir = File(serverDir, ".minehost/quarantine/$txId-$worldName")
            val worldDir = File(serverDir, "worlds/$worldName")
            val hasNewWorld = File(worldDir, "level.dat").exists()

            val journal = parseJournal(obj)

            if (hasNewWorld) {
                writeJournal(serverDir, journal.copy(state = RegenerationState.COMMITTED, updatedAt = System.currentTimeMillis()))
            } else {
                val propsBackup = File(serverDir, ".minehost/transactions/$txId-server.properties.bak")
                rollback(serverDir, profile, journal, repository, propsBackup, quarantineDir, worldDir)
            }
        }
    }
}
