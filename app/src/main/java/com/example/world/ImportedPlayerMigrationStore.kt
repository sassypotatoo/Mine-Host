package com.example.world

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

enum class ImportedPlayerMigrationState {
    UNCLAIMED,
    CLAIMED,
    POSITION_APPLIED,
    COMPLETE,
    BLOCKED,
}

/** Stores imported local-player data outside the world until a real server account is approved. */
class ImportedPlayerMigrationStore(private val serverRoot: File) {
    data class Profile(
        val worldName: String,
        val state: ImportedPlayerMigrationState,
        val recordSha256: String,
        val position: WorldCoordinates?,
        val rotation: WorldRotation?,
        val dimensionId: Int?,
        val gameMode: Int?,
        val selectedHotbarSlot: Int?,
        val inventoryEntries: Int,
        val armorEntries: Int,
        val offhandEntries: Int,
        val enderChestEntries: Int,
        val claimedPlayerName: String?,
        val claimedXuid: String?,
        val claimedUuid: String?,
        val positionAppliedAt: Long?,
        val completedAt: Long?,
        val lastError: String?,
    )

    data class PositionCommandPlan(
        val commands: List<String>,
        val unsupportedReason: String?,
    )

    private val root = File(serverRoot, ".minehost/world-compatibility/imported-players")

    fun stage(worldName: String, player: ImportedPlayerData, rawRecord: ByteArray): Profile {
        require(player.available) { "Local player data is unavailable" }
        val hash = WorldFileIntegrity.sha256(rawRecord)
        require(hash == player.recordSha256) { "Local player record hash mismatch" }
        val dir = worldDirectory(worldName)
        dir.mkdirs()
        val recordFile = File(dir, "local-player.nbt")
        if (recordFile.isFile) {
            require(WorldFileIntegrity.sha256(recordFile) == hash) { "Previously staged local player record differs" }
        } else {
            val part = File(dir, ".local-player.nbt.part-${UUID.randomUUID()}")
            part.writeBytes(rawRecord)
            require(WorldFileIntegrity.sha256(part) == hash) { "Staged local player record verification failed" }
            move(part, recordFile)
        }
        val existing = load(worldName)
        if (existing != null && existing.recordSha256 == hash) return existing
        val profile = Profile(
            worldName = worldName,
            state = ImportedPlayerMigrationState.UNCLAIMED,
            recordSha256 = hash,
            position = player.position,
            rotation = player.rotation,
            dimensionId = player.dimensionId,
            gameMode = player.gameMode,
            selectedHotbarSlot = player.selectedHotbarSlot,
            inventoryEntries = player.inventory.count { it.count > 0 && it.name.isNotBlank() },
            armorEntries = player.armor.count { it.count > 0 && it.name.isNotBlank() },
            offhandEntries = player.offhand.count { it.count > 0 && it.name.isNotBlank() },
            enderChestEntries = player.enderChest.count { it.count > 0 && it.name.isNotBlank() },
            claimedPlayerName = null,
            claimedXuid = null,
            claimedUuid = null,
            positionAppliedAt = null,
            completedAt = null,
            lastError = null,
        )
        write(profile)
        return profile
    }

    /** Must be called only after explicit user approval. */
    fun approveClaim(worldName: String, playerName: String, xuid: String? = null, uuid: String? = null): Profile {
        require(playerName.isNotBlank()) { "Player name is blank" }
        val current = load(worldName) ?: error("No imported local-player profile exists")
        require(current.state == ImportedPlayerMigrationState.UNCLAIMED ||
            current.claimedPlayerName.equals(playerName, ignoreCase = true)) {
            "Imported player profile is already claimed"
        }
        return current.copy(
            state = ImportedPlayerMigrationState.CLAIMED,
            claimedPlayerName = playerName,
            claimedXuid = xuid,
            claimedUuid = uuid,
            lastError = null,
        ).also(::write)
    }

    fun nukkitFamilyPositionPlan(worldName: String, joinedPlayerName: String): PositionCommandPlan {
        val profile = load(worldName) ?: return PositionCommandPlan(emptyList(), "No imported player profile exists")
        if (profile.state != ImportedPlayerMigrationState.CLAIMED) {
            return PositionCommandPlan(emptyList(), "Imported player profile has not been explicitly claimed")
        }
        if (!profile.claimedPlayerName.equals(joinedPlayerName, ignoreCase = true)) {
            return PositionCommandPlan(emptyList(), "Joined player does not match the approved imported profile")
        }
        val position = profile.position ?: return PositionCommandPlan(emptyList(), "Imported position is unavailable")
        if (profile.dimensionId != 0) {
            return PositionCommandPlan(
                emptyList(),
                "Nukkit-family dimension transfer has not been proven lossless for dimension ${profile.dimensionId}",
            )
        }
        val rotation = profile.rotation
        val quotedName = quoteCommandArgument(joinedPlayerName)
        val command = buildString {
            append("tp ").append(quotedName).append(' ')
            append(format(position.x)).append(' ')
            append(format(position.y)).append(' ')
            append(format(position.z))
            if (rotation != null) {
                append(' ').append(format(rotation.yaw))
                append(' ').append(format(rotation.pitch))
            }
        }
        return PositionCommandPlan(listOf(command), null)
    }

    @Deprecated("Use nukkitFamilyPositionPlan")
    fun nukkitMotPositionPlan(worldName: String, joinedPlayerName: String): PositionCommandPlan =
        nukkitFamilyPositionPlan(worldName, joinedPlayerName)

    fun markPositionApplied(worldName: String): Profile = update(worldName) {
        it.copy(
            state = ImportedPlayerMigrationState.POSITION_APPLIED,
            positionAppliedAt = System.currentTimeMillis(),
            lastError = null,
        )
    }

    /**
     * A protected launch applies player data only to the disposable engine-facing world.
     * If that world is rolled back, the one-time marker must roll back too or the next
     * verified attempt would incorrectly skip the migration.
     */
    fun resetAfterWorldRestore(worldName: String): Profile = update(worldName) { current ->
        if (current.state != ImportedPlayerMigrationState.POSITION_APPLIED) return@update current
        current.copy(
            state = ImportedPlayerMigrationState.CLAIMED,
            positionAppliedAt = null,
            lastError = null,
        )
    }

    fun markBlocked(worldName: String, error: String): Profile = update(worldName) {
        it.copy(state = ImportedPlayerMigrationState.BLOCKED, lastError = error.take(8_000))
    }

    fun profileExists(worldName: String): Boolean =
        File(worldDirectory(worldName), "profile.properties").isFile

    fun load(worldName: String): Profile? {
        val file = File(worldDirectory(worldName), "profile.properties")
        if (!file.isFile) return null
        return runCatching { read(file) }.getOrNull()
    }

    fun rename(oldWorldName: String, newWorldName: String): Boolean {
        if (oldWorldName == newWorldName) return load(oldWorldName) != null
        val source = worldDirectory(oldWorldName)
        if (!source.isDirectory) return false
        val destination = worldDirectory(newWorldName)
        require(!destination.exists()) { "Imported player migration data already exists for '$newWorldName'" }
        move(source, destination)
        return try {
            val current = load(newWorldName)
                ?: error("Moved imported player migration profile is unreadable")
            write(current.copy(worldName = newWorldName))
            true
        } catch (error: Throwable) {
            runCatching { if (destination.exists() && !source.exists()) move(destination, source) }
            throw error
        }
    }

    fun delete(worldName: String): Boolean {
        val directory = worldDirectory(worldName)
        return !directory.exists() || directory.deleteRecursively()
    }

    private fun update(worldName: String, transform: (Profile) -> Profile): Profile {
        val current = load(worldName) ?: error("No imported player profile exists")
        return transform(current).also(::write)
    }

    private fun write(profile: Profile) {
        val values = Properties().apply {
            setProperty("worldName", profile.worldName)
            setProperty("state", profile.state.name)
            setProperty("recordSha256", profile.recordSha256)
            profile.position?.let {
                setProperty("positionX", it.x.toString())
                setProperty("positionY", it.y.toString())
                setProperty("positionZ", it.z.toString())
            }
            profile.rotation?.let {
                setProperty("yaw", it.yaw.toString())
                setProperty("pitch", it.pitch.toString())
            }
            profile.dimensionId?.let { setProperty("dimensionId", it.toString()) }
            profile.gameMode?.let { setProperty("gameMode", it.toString()) }
            profile.selectedHotbarSlot?.let { setProperty("selectedHotbarSlot", it.toString()) }
            setProperty("inventoryEntries", profile.inventoryEntries.toString())
            setProperty("armorEntries", profile.armorEntries.toString())
            setProperty("offhandEntries", profile.offhandEntries.toString())
            setProperty("enderChestEntries", profile.enderChestEntries.toString())
            profile.claimedPlayerName?.let { setProperty("claimedPlayerName", it) }
            profile.claimedXuid?.let { setProperty("claimedXuid", it) }
            profile.claimedUuid?.let { setProperty("claimedUuid", it) }
            profile.positionAppliedAt?.let { setProperty("positionAppliedAt", it.toString()) }
            profile.completedAt?.let { setProperty("completedAt", it.toString()) }
            profile.lastError?.let { setProperty("lastError", it) }
        }
        val destination = File(worldDirectory(profile.worldName), "profile.properties")
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, ".profile.part-${UUID.randomUUID()}")
        try {
            part.outputStream().use { values.store(it, "MineHost imported local-player migration") }
            move(part, destination, replace = true)
        } finally {
            part.delete()
        }
    }

    private fun read(file: File): Profile {
        val values = Properties().apply { file.inputStream().use(::load) }
        val position = values.getProperty("positionX")?.toDoubleOrNull()?.let { x ->
            WorldCoordinates(
                x,
                values.getProperty("positionY").toDouble(),
                values.getProperty("positionZ").toDouble(),
            )
        }
        val rotation = values.getProperty("yaw")?.toDoubleOrNull()?.let { yaw ->
            WorldRotation(yaw, values.getProperty("pitch").toDouble())
        }
        return Profile(
            worldName = values.getProperty("worldName"),
            state = ImportedPlayerMigrationState.valueOf(values.getProperty("state")),
            recordSha256 = values.getProperty("recordSha256"),
            position = position,
            rotation = rotation,
            dimensionId = values.getProperty("dimensionId")?.toIntOrNull(),
            gameMode = values.getProperty("gameMode")?.toIntOrNull(),
            selectedHotbarSlot = values.getProperty("selectedHotbarSlot")?.toIntOrNull(),
            inventoryEntries = values.getProperty("inventoryEntries", "0").toInt(),
            armorEntries = values.getProperty("armorEntries", "0").toInt(),
            offhandEntries = values.getProperty("offhandEntries", "0").toInt(),
            enderChestEntries = values.getProperty("enderChestEntries", "0").toInt(),
            claimedPlayerName = values.getProperty("claimedPlayerName"),
            claimedXuid = values.getProperty("claimedXuid"),
            claimedUuid = values.getProperty("claimedUuid"),
            positionAppliedAt = values.getProperty("positionAppliedAt")?.toLongOrNull(),
            completedAt = values.getProperty("completedAt")?.toLongOrNull(),
            lastError = values.getProperty("lastError"),
        )
    }

    fun profileFileForBackup(worldName: String): File = worldDirectory(worldName)

    private fun worldDirectory(worldName: String): File = File(root, worldKey(worldName))

    private fun worldKey(worldName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(worldName.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun move(source: File, destination: File, replace: Boolean = false) {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (_: Throwable) {
            if (replace) Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun quoteCommandArgument(value: String): String = "\"" +
        value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.6f", value)
}
