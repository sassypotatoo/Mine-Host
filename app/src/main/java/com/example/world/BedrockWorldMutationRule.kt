package com.example.world

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** A single explicitly planned LevelDB record mutation. */
data class WorldRecordMutation(
    val databaseKeyHex: String,
    val oldValueSha256: String,
    val newValueSha256: String,
    val description: String,
)

/**
 * A rule may change only the listed database records and listed files.
 * No automatic lossy rule is registered by MineHost.
 */
data class WorldMutationPlan(
    val ruleName: String,
    val sourceWorldHash: String,
    val mutations: List<WorldRecordMutation>,
    val expectedChangedRelativePaths: Set<String>,
    val warnings: List<String> = emptyList(),
)

interface BedrockWorldMutationRule {
    val name: String

    /** Builds an exact, read-only plan before any bytes are changed. */
    fun plan(worldDirectory: File): Result<WorldMutationPlan>

    /** Applies only the supplied, previously persisted plan. */
    fun apply(worldDirectory: File, plan: WorldMutationPlan): Result<Unit>

    /** Reopens and semantically verifies the changed records after the write. */
    fun verify(worldDirectory: File, plan: WorldMutationPlan): Result<Unit>
}

enum class WorldMutationPhase {
    PREPARING,
    PLAN_VERIFIED,
    BACKUP_VERIFIED,
    APPLYING,
    APPLIED,
    VERIFYING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    FAILED,
}

data class WorldMutationJournalData(
    val mutationId: String,
    val worldName: String,
    val ruleName: String,
    val targetPath: String,
    val backupPath: String,
    val sourceFingerprint: String,
    val planSha256: String,
    val plannedRecordCount: Int,
    val expectedChangedRelativePaths: Set<String>,
    val phase: WorldMutationPhase = WorldMutationPhase.PREPARING,
    val committed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val failureMessage: String? = null,
)

data class WorldMutationRecoveryResult(
    val recovered: Int,
    val blocked: List<String>,
) {
    val success: Boolean get() = blocked.isEmpty()
}

/**
 * Transaction wrapper for explicitly approved working-copy rules.
 * The immutable original must never be passed as [worldDirectory]. Any failed
 * or interrupted mutation is restored from a verified transaction backup.
 */
object BedrockWorldMutationRunner {
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val HEX = Regex("^(?:[a-fA-F0-9]{2})+$")

    fun runMutation(
        serverRoot: File,
        worldName: String,
        worldDirectory: File,
        rule: BedrockWorldMutationRule,
    ): Result<Unit> {
        val mutationId = UUID.randomUUID().toString()
        val journalDir = File(serverRoot, ".minehost/world-mutations/$mutationId")
        return runCatching {
            require(worldDirectory.isDirectory) { "World directory $worldDirectory does not exist" }
            require(File(worldDirectory, "level.dat").isFile) { "Working world has no level.dat" }
            require(File(worldDirectory, "db").isDirectory) { "Working world has no db directory" }
            require(worldDirectory.canonicalFile.toPath().startsWith(File(serverRoot, "worlds").canonicalFile.toPath())) {
                "Mutation target is not an engine-facing world copy"
            }

            val sourceFingerprint = WorldFileIntegrity.fingerprint(worldDirectory)
            val plan = rule.plan(worldDirectory).getOrThrow()
            validatePlan(rule, plan, sourceFingerprint.rootHash)
            val planSha256 = planSha256(plan)
            val beforeFiles = fileHashes(worldDirectory)

            journalDir.mkdirs()
            val backupDir = File(journalDir, "backup/world")
            var data = WorldMutationJournalData(
                mutationId = mutationId,
                worldName = worldName,
                ruleName = rule.name,
                targetPath = worldDirectory.canonicalPath,
                backupPath = backupDir.canonicalPath,
                sourceFingerprint = sourceFingerprint.rootHash,
                planSha256 = planSha256,
                plannedRecordCount = plan.mutations.size,
                expectedChangedRelativePaths = plan.expectedChangedRelativePaths,
                phase = WorldMutationPhase.PLAN_VERIFIED,
            )
            writePlan(journalDir, plan)
            writeJournal(journalDir, data)

            val backupFingerprint = WorldFileIntegrity.copyVerified(worldDirectory, backupDir)
            require(backupFingerprint.rootHash == sourceFingerprint.rootHash) {
                "Mutation backup fingerprint mismatch"
            }
            data = data.copy(
                phase = WorldMutationPhase.BACKUP_VERIFIED,
                updatedAt = System.currentTimeMillis(),
            )
            writeJournal(journalDir, data)

            try {
                data = data.copy(
                    phase = WorldMutationPhase.APPLYING,
                    updatedAt = System.currentTimeMillis(),
                )
                writeJournal(journalDir, data)

                rule.apply(worldDirectory, plan).getOrThrow()

                data = data.copy(
                    phase = WorldMutationPhase.APPLIED,
                    updatedAt = System.currentTimeMillis(),
                )
                writeJournal(journalDir, data)

                require(File(worldDirectory, "level.dat").isFile) { "Mutation removed level.dat" }
                require(File(worldDirectory, "db").isDirectory) { "Mutation removed the LevelDB directory" }
                data = data.copy(
                    phase = WorldMutationPhase.VERIFYING,
                    updatedAt = System.currentTimeMillis(),
                )
                writeJournal(journalDir, data)

                val afterFiles = fileHashes(worldDirectory)
                val changedPaths = (beforeFiles.keys + afterFiles.keys)
                    .filterTo(linkedSetOf()) { beforeFiles[it] != afterFiles[it] }
                val unexpected = changedPaths - plan.expectedChangedRelativePaths
                require(unexpected.isEmpty()) {
                    "Mutation changed unplanned files: ${unexpected.sorted().joinToString()}"
                }
                require(changedPaths.isNotEmpty()) { "Mutation plan changed no files" }
                require(changedPaths.all { it.startsWith("db/") }) {
                    "World mutation attempted to change non-LevelDB files: ${changedPaths.sorted().joinToString()}"
                }

                rule.verify(worldDirectory, plan).getOrThrow()
                WorldFileIntegrity.fingerprint(worldDirectory)

                data = data.copy(
                    phase = WorldMutationPhase.COMMITTED,
                    committed = true,
                    updatedAt = System.currentTimeMillis(),
                )
                writeJournal(journalDir, data)
                require(journalDir.deleteRecursively()) {
                    "Mutation committed but transaction cleanup failed"
                }
            } catch (error: Throwable) {
                data = data.copy(
                    phase = WorldMutationPhase.ROLLING_BACK,
                    failureMessage = error.message,
                    updatedAt = System.currentTimeMillis(),
                )
                writeJournal(journalDir, data)
                restoreVerifiedBackup(data)
                data = data.copy(
                    phase = WorldMutationPhase.ROLLED_BACK,
                    updatedAt = System.currentTimeMillis(),
                )
                writeJournal(journalDir, data)
                throw IllegalStateException(
                    "World mutation '${rule.name}' failed and the disposable working copy was restored: ${error.message}",
                    error,
                )
            }
        }
    }

    fun recoverIncompleteMutations(serverRoot: File): WorldMutationRecoveryResult {
        val root = File(serverRoot, ".minehost/world-mutations")
        if (!root.isDirectory) return WorldMutationRecoveryResult(0, emptyList())
        var recovered = 0
        val blocked = mutableListOf<String>()
        root.listFiles().orEmpty().filter(File::isDirectory).forEach { journalDir ->
            val journal = runCatching { readJournal(journalDir) }.getOrNull()
            if (journal == null) {
                blocked += journalDir.absolutePath
                return@forEach
            }
            if (journal.committed || journal.phase == WorldMutationPhase.COMMITTED) {
                if (!journalDir.deleteRecursively()) blocked += journalDir.absolutePath
                return@forEach
            }
            runCatching {
                restoreVerifiedBackup(journal)
                require(journalDir.deleteRecursively()) { "Unable to clean recovered mutation transaction" }
            }.onSuccess {
                recovered++
            }.onFailure {
                blocked += journalDir.absolutePath
            }
        }
        return WorldMutationRecoveryResult(recovered, blocked)
    }

    private fun validatePlan(
        rule: BedrockWorldMutationRule,
        plan: WorldMutationPlan,
        sourceHash: String,
    ) {
        require(plan.ruleName == rule.name) { "Mutation plan belongs to another rule" }
        require(plan.sourceWorldHash == sourceHash) { "Mutation plan source fingerprint is stale" }
        require(plan.mutations.isNotEmpty()) { "Mutation plan contains no record changes" }
        require(plan.expectedChangedRelativePaths.isNotEmpty()) { "Mutation plan contains no file changes" }
        require(plan.expectedChangedRelativePaths.all { path ->
            path.isNotBlank() && !path.startsWith('/') && ".." !in path.split('/') && path.startsWith("db/")
        }) { "Mutation plan contains an unsafe or non-LevelDB path" }
        val keys = linkedSetOf<String>()
        plan.mutations.forEach { mutation ->
            require(HEX.matches(mutation.databaseKeyHex)) { "Mutation contains an invalid database key" }
            require(keys.add(mutation.databaseKeyHex.lowercase())) { "Mutation plan contains a duplicate database key" }
            require(SHA256.matches(mutation.oldValueSha256)) { "Mutation contains an invalid old-value SHA-256" }
            require(SHA256.matches(mutation.newValueSha256)) { "Mutation contains an invalid new-value SHA-256" }
            require(mutation.oldValueSha256 != mutation.newValueSha256) { "Mutation old and new hashes are identical" }
            require(mutation.description.isNotBlank()) { "Mutation description is blank" }
        }
    }

    private fun planSha256(plan: WorldMutationPlan): String {
        val canonical = buildString {
            appendLine(plan.ruleName)
            appendLine(plan.sourceWorldHash)
            plan.expectedChangedRelativePaths.sorted().forEach(::appendLine)
            plan.mutations.sortedBy { it.databaseKeyHex }.forEach { mutation ->
                append(mutation.databaseKeyHex.lowercase()).append('|')
                append(mutation.oldValueSha256).append('|')
                append(mutation.newValueSha256).append('|')
                appendLine(mutation.description)
            }
        }
        return sha256(canonical.toByteArray())
    }

    private fun fileHashes(root: File): Map<String, String> = root.walkTopDown()
        .filter(File::isFile)
        .associate { file -> file.relativeTo(root).invariantSeparatorsPath to WorldFileIntegrity.sha256(file) }

    private fun restoreVerifiedBackup(data: WorldMutationJournalData) {
        val target = File(data.targetPath)
        val backup = File(data.backupPath)
        require(backup.isDirectory) { "Mutation rollback backup is missing" }
        val backupFingerprint = WorldFileIntegrity.fingerprint(backup)
        require(backupFingerprint.rootHash == data.sourceFingerprint) { "Mutation rollback backup fingerprint mismatch" }
        val restored = WorldFileIntegrity.replaceWithVerifiedCopy(backup, target)
        require(restored.rootHash == data.sourceFingerprint) { "Mutation rollback restored the wrong world fingerprint" }
    }

    private fun writePlan(journalDir: File, plan: WorldMutationPlan) {
        val file = File(journalDir, "plan.properties")
        val props = Properties().apply {
            setProperty("ruleName", plan.ruleName)
            setProperty("sourceWorldHash", plan.sourceWorldHash)
            setProperty("expectedChangedRelativePaths", plan.expectedChangedRelativePaths.sorted().joinToString("\n"))
            setProperty("mutationCount", plan.mutations.size.toString())
            plan.mutations.forEachIndexed { index, mutation ->
                setProperty("mutation.$index.key", mutation.databaseKeyHex.lowercase())
                setProperty("mutation.$index.oldSha256", mutation.oldValueSha256)
                setProperty("mutation.$index.newSha256", mutation.newValueSha256)
                setProperty("mutation.$index.description", mutation.description.take(16_000))
            }
        }
        writePropertiesDurably(file, props, "World Mutation Plan")
    }

    private fun writeJournal(journalDir: File, data: WorldMutationJournalData) {
        journalDir.mkdirs()
        val file = File(journalDir, "journal.properties")
        val props = Properties().apply {
            setProperty("mutationId", data.mutationId)
            setProperty("worldName", data.worldName)
            setProperty("ruleName", data.ruleName)
            setProperty("targetPath", data.targetPath)
            setProperty("backupPath", data.backupPath)
            setProperty("sourceFingerprint", data.sourceFingerprint)
            setProperty("planSha256", data.planSha256)
            setProperty("plannedRecordCount", data.plannedRecordCount.toString())
            setProperty("expectedChangedRelativePaths", data.expectedChangedRelativePaths.sorted().joinToString("\n"))
            setProperty("phase", data.phase.name)
            setProperty("committed", data.committed.toString())
            setProperty("timestamp", data.timestamp.toString())
            setProperty("updatedAt", data.updatedAt.toString())
            data.failureMessage?.let { setProperty("failureMessage", it.take(16_000)) }
        }
        writePropertiesDurably(file, props, "World Mutation Journal")
    }

    private fun writePropertiesDurably(file: File, props: Properties, comment: String) {
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, ".${file.name}.part-${UUID.randomUUID()}")
        try {
            FileOutputStream(part).use { output ->
                props.store(output, comment)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    part.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            part.delete()
        }
    }

    private fun readJournal(journalDir: File): WorldMutationJournalData {
        val file = File(journalDir, "journal.properties")
        require(file.isFile) { "Mutation journal is missing" }
        return Properties().run {
            file.inputStream().use(::load)
            WorldMutationJournalData(
                mutationId = getProperty("mutationId") ?: error("Missing mutationId"),
                worldName = getProperty("worldName") ?: error("Missing worldName"),
                ruleName = getProperty("ruleName") ?: error("Missing ruleName"),
                targetPath = getProperty("targetPath") ?: error("Missing targetPath"),
                backupPath = getProperty("backupPath") ?: error("Missing backupPath"),
                sourceFingerprint = getProperty("sourceFingerprint") ?: error("Missing sourceFingerprint"),
                planSha256 = getProperty("planSha256") ?: error("Missing planSha256"),
                plannedRecordCount = getProperty("plannedRecordCount")?.toIntOrNull()
                    ?: error("Missing plannedRecordCount"),
                expectedChangedRelativePaths = getProperty("expectedChangedRelativePaths", "")
                    .lineSequence().filter(String::isNotBlank).toSet(),
                phase = WorldMutationPhase.valueOf(getProperty("phase", "PREPARING")),
                committed = getProperty("committed")?.toBoolean() ?: false,
                timestamp = getProperty("timestamp")?.toLongOrNull() ?: 0L,
                updatedAt = getProperty("updatedAt")?.toLongOrNull() ?: 0L,
                failureMessage = getProperty("failureMessage"),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
