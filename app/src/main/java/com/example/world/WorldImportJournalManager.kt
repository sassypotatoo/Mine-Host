package com.example.world

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

enum class WorldImportPhase {
    PREPARING,
    COPYING,
    INSPECTING,
    FINGERPRINTING,
    PROTECTING_ORIGINAL,
    CREATING_COMPATIBILITY_RECORDS,
    COMMITTING_WORLD,
    SETTING_ACTIVE_WORLD,
    CLEANING_UP,
    COMPLETED,
    ROLLING_BACK,
    FAILED
}

data class WorldImportJournalData(
    val transactionId: String,
    val serverId: String?,
    val targetWorldName: String,
    val stagingPath: String,
    val finalPath: String,
    val rollbackPath: String?,
    val rollbackSidecarPath: String?,
    val previousActiveWorld: String?,
    val phase: WorldImportPhase,

    val stagedFingerprint: String? = null,
    val preparedOriginalPath: String? = null,
    val preparedOriginalFingerprint: String? = null,

    val protectionPrepared: Boolean = false,

    val oldWorldMoveStarted: Boolean = false,
    val oldWorldMoved: Boolean = false,

    val oldSidecarsMoveStarted: Boolean = false,
    val oldSidecarsMoved: Boolean = false,

    val worldCommitStarted: Boolean = false,
    val worldCommitted: Boolean = false,

    val sidecarCommitStarted: Boolean = false,
    val immutableOriginalCommitted: Boolean = false,
    val protectionMetadataCommitted: Boolean = false,
    val verificationMarkerCommitted: Boolean = false,
    val playerMigrationCommitted: Boolean = false,
    val newOriginalCreated: Boolean = false,
    val newOriginalRelativePath: String? = null,

    val activeWorldChangeStarted: Boolean = false,
    val activeWorldSet: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val failureMessage: String? = null,
)

data class WorldImportRecoveryResult(
    val recoveredTransactions: Int,
    val blockedTransactions: List<String>,
    val messages: List<String>,
) {
    val success: Boolean get() = blockedTransactions.isEmpty()
}

object WorldImportJournalManager {
    private const val JOURNAL_FILE_NAME = "journal.properties"

    fun transactionRoot(serverRoot: File): File =
        File(serverRoot, ".minehost/world-import-transactions")

    fun recoveryRequiredRoot(serverRoot: File): File =
        File(serverRoot, ".minehost/world-import-recovery-required")

    fun blockedRecoveryDirectories(serverRoot: File): List<File> =
        recoveryRequiredRoot(serverRoot).listFiles().orEmpty()
            .filter(File::isDirectory)
            .sortedBy { it.name }

    fun startTransaction(
        serverRoot: File,
        serverId: String?,
        sourceName: String,
        targetWorldName: String,
        previousActiveWorld: String?,
        replaceActive: Boolean
    ): WorldImportJournalData {
        val root = transactionRoot(serverRoot)
        root.mkdirs()
        val txId = UUID.randomUUID().toString()
        val txDir = File(root, txId).apply { mkdirs() }
        val staging = File(txDir, "staging")
        val rollback = File(txDir, "rollback")
        val finalWorld = File(serverRoot, "worlds/$targetWorldName")
        val rollbackSidecar = File(txDir, "rollback-sidecars")

        val data = WorldImportJournalData(
            transactionId = txId,
            serverId = serverId,
            targetWorldName = targetWorldName,
            stagingPath = staging.absolutePath,
            finalPath = finalWorld.absolutePath,
            rollbackPath = rollback.absolutePath,
            rollbackSidecarPath = rollbackSidecar.absolutePath,
            previousActiveWorld = previousActiveWorld,
            phase = WorldImportPhase.PREPARING,
            createdAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
        )
        writeJournal(txDir, data)
        return data
    }

    fun updateJournal(txDir: File, data: WorldImportJournalData) {
        writeJournal(txDir, data.copy(lastUpdatedAt = System.currentTimeMillis()))
    }

    fun hasIncompleteTransaction(serverRoot: File, worldName: String): Boolean =
        hasIncompleteTransactionForWorld(serverRoot, worldName)

    fun hasIncompleteTransactionForWorld(serverRoot: File, worldName: String): Boolean {
        fun matches(directory: File): Boolean {
            val journalFile = File(directory, JOURNAL_FILE_NAME)
            if (!journalFile.isFile) return true // Ambiguous recovery evidence blocks all worlds fail-closed.
            val data = runCatching { readJournalFile(journalFile) }.getOrNull() ?: return true
            return data.targetWorldName == worldName && data.phase != WorldImportPhase.COMPLETED
        }
        if (blockedRecoveryDirectories(serverRoot).any(::matches)) return true
        return transactionRoot(serverRoot).listFiles().orEmpty()
            .filter(File::isDirectory)
            .any(::matches)
    }

    private fun quarantineTransaction(
        serverRoot: File,
        transactionDirectory: File,
        reason: String,
    ): String {
        val quarantineRoot = recoveryRequiredRoot(serverRoot).apply { mkdirs() }

        val destination = File(
            quarantineRoot,
            "${transactionDirectory.name}-${System.currentTimeMillis()}",
        )

        Files.move(
            transactionDirectory.toPath(),
            destination.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )

        File(destination, "RECOVERY_REQUIRED.txt")
            .writeText(reason)

        return destination.absolutePath
    }

    fun recoverIncompleteTransactions(
        serverRoot: File,
        onLog: (String) -> Unit = {},
    ): WorldImportRecoveryResult {
        val recovered = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        val messages = mutableListOf<String>()

        blockedRecoveryDirectories(serverRoot).forEach { directory ->
            blocked += directory.absolutePath
            val reason = File(directory, "RECOVERY_REQUIRED.txt")
                .takeIf(File::isFile)
                ?.runCatching { readText().trim() }
                ?.getOrNull()
                .orEmpty()
                .ifBlank { "manual recovery is still required" }
            messages += "Unresolved quarantined import blocks launch: ${directory.absolutePath} ($reason)"
        }

        val root = transactionRoot(serverRoot)
        for (transactionDirectory in root.listFiles().orEmpty().filter(File::isDirectory)) {
            val journalFile = File(transactionDirectory, JOURNAL_FILE_NAME)

            if (!journalFile.isFile) {
                val quarantined = quarantineTransaction(
                    serverRoot,
                    transactionDirectory,
                    "Import transaction has no journal",
                )
                blocked += quarantined
                messages += "Quarantined transaction without journal: $quarantined"
                continue
            }

            val journal = runCatching { readJournalFile(journalFile) }.getOrNull()

            if (journal == null) {
                val quarantined = quarantineTransaction(
                    serverRoot,
                    transactionDirectory,
                    "Import transaction journal is unreadable",
                )
                blocked += quarantined
                messages += "Quarantined corrupt transaction: $quarantined"
                continue
            }

            if (journal.phase == WorldImportPhase.COMPLETED) {
                transactionDirectory.deleteRecursively()
                continue
            }

            val finalWorld = File(journal.finalPath)
            val rollbackWorld = journal.rollbackPath?.let(::File)
            val rollbackSidecars = journal.rollbackSidecarPath?.let(::File)

            val recovery = runCatching {
                // Remove only artifacts proven to belong to the interrupted new import.
                if (journal.sidecarCommitStarted) {
                    removeNewImportSidecars(
                        serverRoot = serverRoot,
                        worldName = journal.targetWorldName,
                        stagedFingerprint = journal.stagedFingerprint,
                        newOriginalCreated = journal.newOriginalCreated,
                        newOriginalRelativePath = journal.newOriginalRelativePath,
                    )
                }
                if (journal.worldCommitStarted && finalWorld.exists()) {
                    removeVerifiedPartialTarget(
                        target = finalWorld,
                        expectedNewFingerprint = journal.stagedFingerprint,
                    )
                }

                // Restore the world before sidecars so a sidecar failure can never
                // prevent recovery of the actual world database.
                if (journal.oldWorldMoveStarted && rollbackWorld?.exists() == true) {
                    restoreWorld(target = finalWorld, rollback = rollbackWorld)
                }
                if (journal.oldSidecarsMoveStarted && rollbackSidecars?.exists() == true) {
                    WorldWorkingCopyManager(serverRoot)
                        .restoreSidecarsFromBackup(rollbackSidecars)
                }
                if (journal.activeWorldChangeStarted) {
                    journal.previousActiveWorld?.let { previous ->
                        restoreActiveWorld(serverRoot, previous)
                    }
                }

                verifyRollbackResult(serverRoot, journal)
                require(transactionDirectory.deleteRecursively() || !transactionDirectory.exists()) {
                    "Recovered transaction could not be removed"
                }
            }

            if (recovery.isSuccess) {
                recovered += journal.transactionId
                messages += "Recovered import ${journal.transactionId}"
            } else {
                val quarantined = quarantineTransaction(
                    serverRoot,
                    transactionDirectory,
                    "Automatic recovery failed: " +
                        (recovery.exceptionOrNull()?.message ?: "unknown"),
                )
                blocked += quarantined
                messages += "Recovery blocked: $quarantined"
            }
        }

        messages.forEach(onLog)

        return WorldImportRecoveryResult(
            recoveredTransactions = recovered.size,
            blockedTransactions = blocked,
            messages = messages,
        )
    }

    private fun removeNewImportSidecars(
        serverRoot: File,
        worldName: String,
        stagedFingerprint: String?,
        newOriginalCreated: Boolean,
        newOriginalRelativePath: String?,
    ) {
        ImportedPlayerMigrationStore(serverRoot).delete(worldName)
        ImportedWorldVerificationStore.delete(serverRoot, worldName)

        val manager = WorldWorkingCopyManager(serverRoot)
        val metadata = manager.metadata(worldName)
        if (metadata != null && (stagedFingerprint == null || metadata.sourceWorldHash == stagedFingerprint)) {
            require(manager.deleteMetadataOnly(worldName)) {
                "Unable to remove newly committed protection metadata"
            }
        }

        if (newOriginalCreated && newOriginalRelativePath != null) {
            val original = File(serverRoot, newOriginalRelativePath).canonicalFile
            require(original.toPath().startsWith(serverRoot.canonicalFile.toPath())) {
                "New immutable-original rollback path escaped the server directory"
            }
            if (original.isDirectory) {
                val actual = runCatching { WorldFileIntegrity.fingerprint(original).rootHash }.getOrNull()
                require(stagedFingerprint == null || actual == stagedFingerprint) {
                    "Refusing to delete a newly recorded immutable original with an unexpected fingerprint"
                }
                require(original.deleteRecursively()) {
                    "Unable to remove newly created immutable original"
                }
                // Remove empty hash/world-key parents, but never traverse above the originals root.
                val originalsRoot = File(serverRoot, ".minehost/world-compatibility/originals").canonicalFile
                var parent = original.parentFile
                while (parent != null && parent.canonicalFile != originalsRoot &&
                    parent.canonicalFile.toPath().startsWith(originalsRoot.toPath()) &&
                    parent.listFiles().orEmpty().isEmpty()
                ) {
                    val next = parent.parentFile
                    parent.delete()
                    parent = next
                }
            }
        }
    }

    private fun removeVerifiedPartialTarget(
        target: File,
        expectedNewFingerprint: String?,
    ) {
        if (!target.exists()) return
        require(expectedNewFingerprint != null) {
            "Cannot remove a partial target without its staged fingerprint"
        }
        val actualFingerprint = runCatching { WorldFileIntegrity.fingerprint(target).rootHash }.getOrNull()
        if (actualFingerprint == expectedNewFingerprint) {
            require(target.deleteRecursively()) { "Unable to remove partial imported world" }
        } else {
            error("Partial target fingerprint mismatch during recovery ($actualFingerprint vs $expectedNewFingerprint)")
        }
    }

    private fun restoreWorld(
        target: File,
        rollback: File,
    ) {
        if (target.exists()) target.deleteRecursively()
        moveOrCopy(rollback, target)
    }

    private fun verifyRollbackResult(
        serverRoot: File,
        journal: WorldImportJournalData,
    ) {
        if (journal.oldWorldMoveStarted && journal.rollbackPath != null) {
            val finalWorld = File(journal.finalPath)
            require(finalWorld.isDirectory && File(finalWorld, "level.dat").isFile) {
                "Failed to restore original world state during rollback"
            }
        }
    }

    private fun restoreActiveWorld(serverRoot: File, activeWorldName: String) {
        val propertiesFile = File(serverRoot, "server.properties")
        require(propertiesFile.isFile) {
            "Cannot restore active world because server.properties is missing"
        }
        val properties = Properties()
        propertiesFile.inputStream().use(properties::load)
        require(properties.isNotEmpty()) {
            "Refusing to overwrite an unreadable or empty server.properties file"
        }
        properties["level-name"] = activeWorldName
        writePropertiesDurably(propertiesFile, properties, "MineHost server settings")
    }

    private fun writeJournal(txDir: File, data: WorldImportJournalData) {
        txDir.mkdirs()
        val file = File(txDir, JOURNAL_FILE_NAME)
        val props = Properties().apply {
            setProperty("transactionId", data.transactionId)
            data.serverId?.let { setProperty("serverId", it) }
            setProperty("targetWorldName", data.targetWorldName)
            setProperty("stagingPath", data.stagingPath)
            setProperty("finalPath", data.finalPath)
            data.rollbackPath?.let { setProperty("rollbackPath", it) }
            data.rollbackSidecarPath?.let { setProperty("rollbackSidecarPath", it) }
            data.previousActiveWorld?.let { setProperty("previousActiveWorld", it) }
            setProperty("phase", data.phase.name)

            data.stagedFingerprint?.let { setProperty("stagedFingerprint", it) }
            data.preparedOriginalPath?.let { setProperty("preparedOriginalPath", it) }
            data.preparedOriginalFingerprint?.let { setProperty("preparedOriginalFingerprint", it) }
            setProperty("protectionPrepared", data.protectionPrepared.toString())

            setProperty("oldWorldMoveStarted", data.oldWorldMoveStarted.toString())
            setProperty("oldWorldMoved", data.oldWorldMoved.toString())
            setProperty("oldSidecarsMoveStarted", data.oldSidecarsMoveStarted.toString())
            setProperty("oldSidecarsMoved", data.oldSidecarsMoved.toString())

            setProperty("worldCommitStarted", data.worldCommitStarted.toString())
            setProperty("worldCommitted", data.worldCommitted.toString())

            setProperty("sidecarCommitStarted", data.sidecarCommitStarted.toString())
            setProperty("immutableOriginalCommitted", data.immutableOriginalCommitted.toString())
            setProperty("protectionMetadataCommitted", data.protectionMetadataCommitted.toString())
            setProperty("verificationMarkerCommitted", data.verificationMarkerCommitted.toString())
            setProperty("playerMigrationCommitted", data.playerMigrationCommitted.toString())
            setProperty("newOriginalCreated", data.newOriginalCreated.toString())
            data.newOriginalRelativePath?.let { setProperty("newOriginalRelativePath", it) }

            setProperty("activeWorldChangeStarted", data.activeWorldChangeStarted.toString())
            setProperty("activeWorldSet", data.activeWorldSet.toString())

            setProperty("createdAt", data.createdAt.toString())
            setProperty("lastUpdatedAt", data.lastUpdatedAt.toString())
            data.failureMessage?.let { setProperty("failureMessage", it) }
        }

        writePropertiesDurably(file, props, "MineHost World Import Journal")
    }

    private fun writePropertiesDurably(
        destination: File,
        properties: Properties,
        comment: String,
    ) {
        destination.parentFile?.mkdirs()

        val temporary = File(
            destination.parentFile,
            ".${destination.name}.part-${UUID.randomUUID()}",
        )

        try {
            FileOutputStream(temporary).use { output ->
                properties.store(output, comment)
                output.flush()
                output.fd.sync()
            }

            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            require(destination.isFile && destination.length() > 0L) {
                "Unable to commit import journal"
            }
        } finally {
            temporary.delete()
        }
    }

    internal fun readJournalFromTxDir(txDir: File): WorldImportJournalData? {
        val file = File(txDir, JOURNAL_FILE_NAME)
        if (!file.isFile) return null
        return readJournalFile(file)
    }

    private fun readJournalFile(file: File): WorldImportJournalData = Properties().run {
        file.inputStream().use(::load)
        WorldImportJournalData(
            transactionId = getProperty("transactionId") ?: error("Missing transactionId"),
            serverId = getProperty("serverId"),
            targetWorldName = getProperty("targetWorldName") ?: error("Missing targetWorldName"),
            stagingPath = getProperty("stagingPath") ?: error("Missing stagingPath"),
            finalPath = getProperty("finalPath") ?: error("Missing finalPath"),
            rollbackPath = getProperty("rollbackPath"),
            rollbackSidecarPath = getProperty("rollbackSidecarPath"),
            previousActiveWorld = getProperty("previousActiveWorld"),
            phase = WorldImportPhase.valueOf(getProperty("phase", "PREPARING")),

            stagedFingerprint = getProperty("stagedFingerprint"),
            preparedOriginalPath = getProperty("preparedOriginalPath"),
            preparedOriginalFingerprint = getProperty("preparedOriginalFingerprint"),
            protectionPrepared = getProperty("protectionPrepared")?.toBoolean() ?: false,

            oldWorldMoveStarted = getProperty("oldWorldMoveStarted")?.toBoolean() ?: false,
            oldWorldMoved = getProperty("oldWorldMoved")?.toBoolean() ?: false,

            oldSidecarsMoveStarted = getProperty("oldSidecarsMoveStarted")?.toBoolean() ?: false,
            oldSidecarsMoved = getProperty("oldSidecarsMoved")?.toBoolean() ?: false,

            worldCommitStarted = getProperty("worldCommitStarted")?.toBoolean() ?: false,
            worldCommitted = getProperty("worldCommitted")?.toBoolean() ?: false,

            sidecarCommitStarted = getProperty("sidecarCommitStarted")?.toBoolean() ?: false,
            immutableOriginalCommitted = getProperty("immutableOriginalCommitted")?.toBoolean() ?: false,
            protectionMetadataCommitted = getProperty("protectionMetadataCommitted")?.toBoolean() ?: false,
            verificationMarkerCommitted = getProperty("verificationMarkerCommitted")?.toBoolean() ?: false,
            playerMigrationCommitted = getProperty("playerMigrationCommitted")?.toBoolean() ?: false,
            newOriginalCreated = getProperty("newOriginalCreated")?.toBoolean() ?: false,
            newOriginalRelativePath = getProperty("newOriginalRelativePath"),

            activeWorldChangeStarted = getProperty("activeWorldChangeStarted")?.toBoolean() ?: false,
            activeWorldSet = getProperty("activeWorldSet")?.toBoolean() ?: false,

            createdAt = getProperty("createdAt")?.toLongOrNull() ?: System.currentTimeMillis(),
            lastUpdatedAt = getProperty("lastUpdatedAt")?.toLongOrNull() ?: System.currentTimeMillis(),
            failureMessage = getProperty("failureMessage"),
        )
    }

    private fun moveOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()
        if (!source.renameTo(target)) {
            if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
            source.deleteRecursively()
        }
    }
}
