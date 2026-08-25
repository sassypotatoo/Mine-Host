package com.example.world

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

enum class ImportedWorldVerificationState {
    PENDING,
    PROVISIONAL,
    FAILED,
    VERIFIED,
}

data class ImportedWorldIdentity(
    val worldName: String,
    val sourceWorldHash: String,
    val transactionId: String,
)

/**
 * MineHost-owned sidecar state for imported worlds.
 * Markers live outside the world folder so the original Bedrock files remain untouched.
 */
object ImportedWorldVerificationStore {
    fun identity(
        serverRoot: File,
        worldName: String,
    ): ImportedWorldIdentity? {
        val values = metadata(serverRoot, worldName)
            ?: return null

        val storedWorldName = values.getProperty("worldName")
            ?.takeIf(String::isNotBlank)
            ?: return null

        val sourceHash = values.getProperty("sourceWorldHash")
            ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            ?: return null

        val transactionId = values.getProperty("transactionId")
            ?.takeIf(String::isNotBlank)
            ?: return null

        return ImportedWorldIdentity(
            worldName = storedWorldName,
            sourceWorldHash = sourceHash,
            transactionId = transactionId,
        )
    }

    fun markPending(
        serverRoot: File,
        worldName: String,
        metadata: BedrockLevelMetadata?,
        sourceWorldHash: String? = null,
        transactionId: String? = null,
    ): Boolean = runCatching {
        val marker = markerFile(serverRoot, worldName)
        val now = System.currentTimeMillis()
        val values = Properties().apply {
            setProperty("worldName", worldName)
            setProperty("state", ImportedWorldVerificationState.PENDING.name)
            setProperty("importedAt", now.toString())
            setProperty("updatedAt", now.toString())
            sourceWorldHash?.let { setProperty("sourceWorldHash", it) }
            transactionId?.let { setProperty("transactionId", it) }
            metadata?.levelName?.let { setProperty("levelName", it) }
            metadata?.storageVersion?.let { setProperty("storageVersion", it.toString()) }
            metadata?.networkVersion?.let { setProperty("networkVersion", it.toString()) }
            if (!metadata?.lastOpenedWithVersion.isNullOrEmpty()) {
                setProperty("lastOpenedWithVersion", metadata!!.lastOpenedWithVersion.joinToString("."))
            }
            if (!metadata?.minimumCompatibleClientVersion.isNullOrEmpty()) {
                setProperty(
                    "minimumCompatibleClientVersion",
                    metadata!!.minimumCompatibleClientVersion.joinToString(".")
                )
            }
        }
        write(marker, values, "MineHost imported-world verification state")
        true
    }.getOrDefault(false)

    fun updateStatePreservingIdentity(
        serverRoot: File,
        worldName: String,
        newState: ImportedWorldVerificationState,
    ): Boolean = runCatching {
        val marker = markerFile(serverRoot, worldName)
        require(marker.isFile) {
            "Imported-world marker is missing"
        }

        val values = Properties().apply {
            marker.inputStream().use(::load)
        }

        require(
            values.getProperty("worldName") == worldName
        ) {
            "Imported-world marker belongs to another world"
        }

        require(
            values.getProperty("sourceWorldHash")
                ?.matches(Regex("[a-f0-9]{64}")) == true
        ) {
            "Imported-world marker has no valid source hash"
        }

        require(
            !values.getProperty("transactionId").isNullOrBlank()
        ) {
            "Imported-world marker has no transaction ID"
        }

        values.setProperty("state", newState.name)
        values.setProperty(
            "updatedAt",
            System.currentTimeMillis().toString(),
        )

        write(
            marker,
            values,
            "MineHost imported-world verification state",
        )

        true
    }.getOrDefault(false)

    fun agreesWithProtection(
        serverRoot: File,
        worldName: String,
        protection: WorldWorkingCopyManager.Metadata,
    ): Boolean {
        val identity = identity(serverRoot, worldName)
            ?: return false

        val transactionId = protection.importTransactionId
            ?.takeIf(String::isNotBlank)
            ?: return false

        return identity.worldName == protection.worldName &&
            identity.sourceWorldHash == protection.sourceWorldHash &&
            identity.transactionId == transactionId
    }


    /** All non-verified imported worlds remain behind the protected launch gate. */
    fun isPending(serverRoot: File, worldName: String): Boolean {
        return when (state(serverRoot, worldName)) {
            null, ImportedWorldVerificationState.VERIFIED -> false
            ImportedWorldVerificationState.PENDING,
            ImportedWorldVerificationState.PROVISIONAL,
            ImportedWorldVerificationState.FAILED -> true
        }
    }

    fun markerExists(serverRoot: File, worldName: String): Boolean =
        markerFile(serverRoot, worldName).isFile

    fun markerFileForBackup(serverRoot: File, worldName: String): File = markerFile(serverRoot, worldName)

    fun state(serverRoot: File, worldName: String): ImportedWorldVerificationState? {
        val marker = markerFile(serverRoot, worldName)
        if (!marker.isFile) return null
        return readState(read(marker))
    }

    fun markProvisionallyLoaded(
        serverRoot: File,
        worldName: String,
        engineVersionId: String,
    ): Boolean = mutate(serverRoot, worldName) { values ->
        val current = readState(values)
        require(current != ImportedWorldVerificationState.FAILED) {
            "A failed imported world cannot become provisional without a fresh protected copy"
        }
        require(current != ImportedWorldVerificationState.VERIFIED) {
            "A verified imported world does not require provisional verification"
        }
        values.apply {
            setProperty("state", ImportedWorldVerificationState.PROVISIONAL.name)
            setProperty("provisionalAt", System.currentTimeMillis().toString())
            setProperty("provisionalEngineVersionId", engineVersionId)
            remove("failureAt")
            remove("failureMessage")
        }
    }

    fun markFailed(
        serverRoot: File,
        worldName: String,
        error: String,
        engineVersionId: String? = null,
    ): Boolean = mutate(serverRoot, worldName) { values ->
        values.apply {
            setProperty("state", ImportedWorldVerificationState.FAILED.name)
            setProperty("failureAt", System.currentTimeMillis().toString())
            setProperty("failureMessage", error.trim().take(MAX_ERROR_LENGTH))
            engineVersionId?.let { setProperty("failedEngineVersionId", it) }
            remove("verifiedAt")
            remove("verifiedEngineVersionId")
        }
    }

    /**
     * Verification is deliberately one-way and may only follow a provisional protected launch.
     * A process start, UDP bind, RakNet reply, or engine "Done" line is not enough.
     */
    fun markVerified(serverRoot: File, worldName: String, engineVersionId: String): Boolean =
        mutate(serverRoot, worldName) { values ->
            val current = readState(values)
            require(current == ImportedWorldVerificationState.PROVISIONAL) {
                "Imported world must be PROVISIONAL before it can become VERIFIED (was $current)"
            }
            require(values.getProperty("failureMessage").isNullOrBlank()) {
                "Imported world has a recorded compatibility failure"
            }
            values.apply {
                setProperty("state", ImportedWorldVerificationState.VERIFIED.name)
                setProperty("verifiedAt", System.currentTimeMillis().toString())
                setProperty("verifiedEngineVersionId", engineVersionId)
            }
        }

    fun rename(serverRoot: File, oldWorldName: String, newWorldName: String): Boolean {
        if (oldWorldName == newWorldName) return markerFile(serverRoot, oldWorldName).isFile
        val source = markerFile(serverRoot, oldWorldName)
        if (!source.isFile) return false
        val destination = markerFile(serverRoot, newWorldName)
        require(!destination.exists()) { "Imported-world marker already exists for '$newWorldName'" }
        val values = read(source).apply {
            setProperty("worldName", newWorldName)
            setProperty("updatedAt", System.currentTimeMillis().toString())
        }
        write(destination, values, "MineHost imported-world verification state")
        if (!source.delete()) {
            destination.delete()
            error("Unable to remove old imported-world marker for '$oldWorldName'")
        }
        return true
    }

    fun delete(serverRoot: File, worldName: String): Boolean {
        val marker = markerFile(serverRoot, worldName)
        return !marker.exists() || marker.delete()
    }

    fun metadata(serverRoot: File, worldName: String): Properties? {
        val marker = markerFile(serverRoot, worldName)
        return marker.takeIf(File::isFile)?.let(::read)
    }

    private fun mutate(
        serverRoot: File,
        worldName: String,
        transform: (Properties) -> Properties,
    ): Boolean = runCatching {
        val marker = markerFile(serverRoot, worldName)
        require(marker.isFile) { "No imported-world marker exists for '$worldName'" }
        val values = transform(read(marker)).apply {
            setProperty("updatedAt", System.currentTimeMillis().toString())
        }
        write(marker, values, "MineHost imported-world verification state")
        true
    }.getOrDefault(false)

    private fun readState(values: Properties): ImportedWorldVerificationState = runCatching {
        ImportedWorldVerificationState.valueOf(values.getProperty("state", "PENDING"))
    }.getOrDefault(ImportedWorldVerificationState.PENDING)

    private fun write(destination: File, values: Properties, comment: String) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, ".${destination.name}.part-${UUID.randomUUID()}")
        try {
            part.outputStream().use { values.store(it, comment) }
            try {
                Files.move(
                    part.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Throwable) {
                Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            require(destination.isFile && destination.length() > 0L) { "Unable to commit imported-world marker" }
        } finally {
            part.delete()
        }
    }

    private fun markerFile(serverRoot: File, worldName: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(worldName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(serverRoot, ".minehost/imported-worlds/$digest.properties")
    }

    private fun read(file: File): Properties = Properties().apply {
        file.inputStream().use(::load)
    }

    private const val MAX_ERROR_LENGTH = 16_000
}
