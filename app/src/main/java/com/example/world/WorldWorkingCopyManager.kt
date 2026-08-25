package com.example.world

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

enum class WorldProtectionState {
    ORIGINAL_PROTECTED,
    INSPECTING,
    PREPARING_ENGINE_COPY,
    TESTING_COMPATIBILITY,
    PROVISIONALLY_LOADED,
    COMPATIBLE,
    PARTIALLY_COMPATIBLE,
    INCOMPATIBLE,
    RESTORED_AFTER_FAILURE,
}

data class ImportedWorldSidecarManifest(
    val worldName: String,
    val protectionMetadataRelativePath: String?,
    val verificationMarkerRelativePath: String?,
    val playerMigrationRelativePath: String?,
    val originalBucketRelativePath: String?,
)

/**
 * Owns the immutable import copy and the disposable engine-facing copy.
 * The engine never receives the permanent original directory.
 */
class WorldWorkingCopyManager(private val serverRoot: File) {

    data class Metadata(
        val worldName: String,
        val sourceWorldHash: String,
        val originalWorldPath: String,
        val state: WorldProtectionState,
        val engineId: String?,
        val engineVersionId: String?,
        val adapterVersion: String?,
        val preparedWorldHash: String?,
        val importedAt: Long,
        val updatedAt: Long,
        val lastError: String?,
        val sourceWorldVersion: String?,
        val sourceStorageVersion: Int?,
        val sourceNetworkVersion: Int?,
        val backupLocation: String,
        val conversionDate: Long?,
        val warnings: List<String>,
        val unsupportedData: List<String>,
        val importTransactionId: String? = null,
    )

    data class ProtectionResult(
        val metadata: Metadata,
        val originalFingerprint: WorldFileIntegrity.Fingerprint,
    )

    data class PreparedImportedWorldProtection(
        val transactionId: String,
        val worldName: String,
        val sourceFingerprint: WorldFileIntegrity.Fingerprint,
        val preparedOriginalDirectory: File,
        val preparedOriginalOwnedByTransaction: Boolean,
        val finalOriginalDirectory: File,
        val inspection: BedrockWorldInspectionResult,
        val levelMetadata: BedrockLevelMetadata,
        val preparedAt: Long,
    )

    data class PreparedCopy(
        val metadata: Metadata,
        val resumedExistingCopy: Boolean,
    )

    private val compatibilityRoot = File(serverRoot, ".minehost/world-compatibility")
    private val originalsRoot = File(compatibilityRoot, "originals")
    private val metadataRoot = File(compatibilityRoot, "metadata")
    private val diagnosticsRoot = File(compatibilityRoot, "diagnostics")
    private val worldsRoot = File(serverRoot, "worlds")

    private fun requireSafeWorldName(worldName: String) {
        require(worldName.isNotBlank()) { "World name is blank" }
        require('/' !in worldName && '\\' !in worldName) {
            "World name contains a path separator"
        }
        require(worldName != "." && worldName != "..") {
            "Invalid world name"
        }
    }

    private fun isInside(parent: File, child: File): Boolean {
        val parentPath = parent.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        return childPath.startsWith(parentPath)
    }

    fun prepareImportedWorldProtection(
        transactionId: String,
        worldName: String,
        stagedWorldDirectory: File,
        inspection: BedrockWorldInspectionResult,
        levelMetadata: BedrockLevelMetadata,
        transactionDirectory: File,
    ): PreparedImportedWorldProtection {
        require(transactionId.isNotBlank()) { "Missing import transaction ID" }
        requireSafeWorldName(worldName)
        require(!inspection.report.hasFatalInspectionError) {
            "World inspection contains fatal errors"
        }

        val expectedTransaction = File(
            WorldImportJournalManager.transactionRoot(serverRoot),
            transactionId,
        ).canonicalFile
        val transaction = transactionDirectory.canonicalFile

        require(transaction == expectedTransaction) {
            "Import transaction path does not match transaction ID"
        }

        val stagingRoot = File(transaction, "staging").canonicalFile
        val staged = stagedWorldDirectory.canonicalFile

        require(isInside(stagingRoot, staged)) {
            "Staged world escaped the import transaction staging directory"
        }
        require(staged.name == worldName) {
            "Staged world directory name does not match '$worldName'"
        }
        require(File(staged, "level.dat").isFile) {
            "Staged Bedrock world has no level.dat"
        }
        require(File(staged, "db").isDirectory) {
            "Staged Bedrock world has no db directory"
        }

        val stagedFingerprint = WorldFileIntegrity.fingerprint(staged)

        require(stagedFingerprint.rootHash == inspection.report.sourceWorldHash) {
            "Staged world changed after read-only inspection"
        }

        val key = worldKey(worldName)
        val finalOriginal = File(
            originalsRoot,
            "$key/${stagedFingerprint.rootHash}/world",
        )

        if (finalOriginal.isDirectory) {
            val existingFingerprint = WorldFileIntegrity.fingerprint(finalOriginal)
            require(existingFingerprint.rootHash == stagedFingerprint.rootHash) {
                "Existing protected original is corrupt"
            }

            return PreparedImportedWorldProtection(
                transactionId = transactionId,
                worldName = worldName,
                sourceFingerprint = stagedFingerprint,
                preparedOriginalDirectory = finalOriginal,
                preparedOriginalOwnedByTransaction = false,
                finalOriginalDirectory = finalOriginal,
                inspection = inspection,
                levelMetadata = levelMetadata,
                preparedAt = System.currentTimeMillis(),
            )
        }

        val preparedOriginal = File(
            transaction,
            "prepared-protection/original/$key/${stagedFingerprint.rootHash}/world",
        )

        if (preparedOriginal.exists()) {
            preparedOriginal.deleteRecursively()
        }

        val copiedFingerprint = WorldFileIntegrity.copyVerified(
            staged,
            preparedOriginal,
        )

        require(copiedFingerprint.rootHash == stagedFingerprint.rootHash) {
            "Prepared immutable original fingerprint mismatch"
        }

        return PreparedImportedWorldProtection(
            transactionId = transactionId,
            worldName = worldName,
            sourceFingerprint = stagedFingerprint,
            preparedOriginalDirectory = preparedOriginal,
            preparedOriginalOwnedByTransaction = true,
            finalOriginalDirectory = finalOriginal,
            inspection = inspection,
            levelMetadata = levelMetadata,
            preparedAt = System.currentTimeMillis(),
        )
    }

    fun commitImportedWorldProtection(
        prepared: PreparedImportedWorldProtection,
        installedWorld: File,
    ): ProtectionResult {
        requireSafeWorldName(prepared.worldName)

        val expectedInstalled = resolveWorkingWorld(prepared.worldName).canonicalFile
        val installed = installedWorld.canonicalFile

        require(installed == expectedInstalled) {
            "Installed world does not match the final server world path"
        }
        require(File(installed, "level.dat").isFile) {
            "Installed Bedrock world has no level.dat"
        }
        require(File(installed, "db").isDirectory) {
            "Installed Bedrock world has no db directory"
        }

        val installedFingerprint = WorldFileIntegrity.fingerprint(installed)

        require(installedFingerprint.rootHash == prepared.sourceFingerprint.rootHash) {
            "Installed world fingerprint differs from prepared source"
        }

        var originalCreatedByThisCommit = false
        var metadataCommitted = false
        var markerCommitted = false
        var playerMigrationCommitted = false

        try {
            val original = prepared.finalOriginalDirectory

            if (!original.isDirectory) {
                require(prepared.preparedOriginalOwnedByTransaction) {
                    "Prepared original ownership is inconsistent"
                }

                original.parentFile?.mkdirs()
                moveDirectory(prepared.preparedOriginalDirectory, original)
                originalCreatedByThisCommit = true
            }

            val originalFingerprint = WorldFileIntegrity.fingerprint(original)

            require(originalFingerprint.rootHash == prepared.sourceFingerprint.rootHash) {
                "Committed immutable original fingerprint mismatch"
            }

            val now = System.currentTimeMillis()

            val metadata = Metadata(
                worldName = prepared.worldName,
                sourceWorldHash = prepared.sourceFingerprint.rootHash,
                originalWorldPath = original.absolutePath,
                state = WorldProtectionState.ORIGINAL_PROTECTED,
                engineId = null,
                engineVersionId = null,
                adapterVersion = null,
                preparedWorldHash = installedFingerprint.rootHash,
                importedAt = now,
                updatedAt = now,
                lastError = null,
                sourceWorldVersion =
                    prepared.inspection.report.lastOpenedMinecraftVersion,
                sourceStorageVersion =
                    prepared.inspection.report.storageVersion,
                sourceNetworkVersion =
                    prepared.inspection.report.networkVersion,
                backupLocation = original.absolutePath,
                conversionDate = null,
                warnings = prepared.inspection.report.warnings.distinct(),
                unsupportedData = buildList {
                    prepared.inspection.report.unknownChunkRecordTypes
                        .keys
                        .sorted()
                        .forEach { add("unknown chunk record type $it") }
                    prepared.inspection.report.blockPalette
                        .parseErrors
                        .forEach(::add)
                }.distinct(),
                importTransactionId = prepared.transactionId,
            )

            writeMetadata(metadata)
            metadataCommitted = true

            require(
                ImportedWorldVerificationStore.markPending(
                    serverRoot = serverRoot,
                    worldName = prepared.worldName,
                    metadata = prepared.levelMetadata,
                    sourceWorldHash = prepared.sourceFingerprint.rootHash,
                    transactionId = prepared.transactionId,
                )
            ) {
                "Unable to commit imported-world verification marker"
            }
            markerCommitted = true

            prepared.inspection.localPlayerRecord?.let { record ->
                ImportedPlayerMigrationStore(serverRoot).stage(
                    prepared.worldName,
                    prepared.inspection.report.localPlayer,
                    record,
                )
                playerMigrationCommitted = true
            }

            require(
                ImportedWorldVerificationStore.agreesWithProtection(
                    serverRoot,
                    prepared.worldName,
                    metadata,
                )
            ) {
                "Imported-world marker does not agree with protection metadata"
            }

            return ProtectionResult(
                metadata = metadata,
                originalFingerprint = originalFingerprint,
            )
        } catch (error: Throwable) {
            if (playerMigrationCommitted) {
                runCatching {
                    ImportedPlayerMigrationStore(serverRoot)
                        .delete(prepared.worldName)
                }
            }

            if (markerCommitted) {
                runCatching {
                    ImportedWorldVerificationStore.delete(
                        serverRoot,
                        prepared.worldName,
                    )
                }
            }

            if (metadataCommitted) {
                runCatching { metadataFile(prepared.worldName).delete() }
            }

            if (originalCreatedByThisCommit) {
                runCatching {
                    prepared.finalOriginalDirectory.parentFile
                        ?.deleteRecursively()
                }
            }

            throw error
        }
    }

    fun protectImportedWorld(
        worldName: String,
        installedWorld: File,
        inspection: BedrockWorldInspectionResult,
        importTransactionId: String,
    ): ProtectionResult {
        require(importTransactionId.isNotBlank()) { "Missing adoption transaction ID" }
        require(resolveWorkingWorld(worldName).canonicalFile == installedWorld.canonicalFile) {
            "Installed world does not match the server world path"
        }
        require(!inspection.report.hasFatalInspectionError) {
            val errors = inspection.report.errors.joinToString("; ")
            val sectionErrors = inspection.report.sections.filterValues { it.status == CompatibilityStatus.CORRUPT }
                .map { "${it.key}: ${it.value.message}" }.joinToString("; ")
            "World inspection contains fatal errors. Main: [$errors]; Sections: [$sectionErrors]"
        }
        val current = WorldFileIntegrity.fingerprint(installedWorld)
        require(current.rootHash == inspection.report.sourceWorldHash) {
            "World changed between inspection and protection"
        }
        require(installedWorld.usableSpace >= current.totalBytes * 2L + MIN_FREE_SPACE_BYTES) {
            "Not enough free space to protect the imported world"
        }

        val key = worldKey(worldName)
        val original = File(originalsRoot, "$key/${current.rootHash}/world")
        val protectedFingerprint = if (original.isDirectory) {
            WorldFileIntegrity.fingerprint(original).also {
                require(it.rootHash == current.rootHash) { "Existing protected original is corrupt" }
            }
        } else {
            WorldFileIntegrity.copyVerified(installedWorld, original)
        }
        val now = System.currentTimeMillis()
        val metadata = Metadata(
            worldName = worldName,
            sourceWorldHash = current.rootHash,
            originalWorldPath = original.absolutePath,
            state = WorldProtectionState.ORIGINAL_PROTECTED,
            engineId = null,
            engineVersionId = null,
            adapterVersion = null,
            preparedWorldHash = current.rootHash,
            importedAt = now,
            updatedAt = now,
            lastError = null,
            sourceWorldVersion = inspection.report.lastOpenedMinecraftVersion,
            sourceStorageVersion = inspection.report.storageVersion,
            sourceNetworkVersion = inspection.report.networkVersion,
            backupLocation = original.absolutePath,
            conversionDate = null,
            warnings = inspection.report.warnings.distinct(),
            unsupportedData = buildList {
                inspection.report.unknownChunkRecordTypes.keys.sorted().forEach { add("unknown chunk record type $it") }
                inspection.report.blockPalette.parseErrors.forEach(::add)
            }.distinct(),
            importTransactionId = importTransactionId,
        )
        writeMetadata(metadata)
        inspection.localPlayerRecord?.let { record ->
            ImportedPlayerMigrationStore(serverRoot).stage(worldName, inspection.report.localPlayer, record)
        }
        return ProtectionResult(metadata, protectedFingerprint)
    }

    /**
     * Prepares the engine-facing copy. A clean provisional copy may be resumed only when the
     * exact engine, engine version, adapter version and last committed fingerprint all match.
     * Every other path resets from the immutable original.
     */
    fun prepareEngineWorkingCopy(
        worldName: String,
        engineId: String,
        engineVersionId: String,
        adapterVersion: String,
        allowResume: Boolean = true,
    ): PreparedCopy {
        val existing = requireMetadata(worldName)
        val original = verifiedOriginal(existing)
        val working = resolveWorkingWorld(worldName)
        val canResume = allowResume &&
            (existing.state == WorldProtectionState.PARTIALLY_COMPATIBLE ||
                existing.state == WorldProtectionState.COMPATIBLE) &&
            existing.engineId == engineId &&
            existing.engineVersionId == engineVersionId &&
            existing.adapterVersion == adapterVersion &&
            existing.preparedWorldHash != null &&
            working.isDirectory &&
            runCatching { WorldFileIntegrity.fingerprint(working).rootHash == existing.preparedWorldHash }
                .getOrDefault(false)

        if (canResume) {
            val resumed = existing.copy(
                state = WorldProtectionState.TESTING_COMPATIBILITY,
                updatedAt = System.currentTimeMillis(),
                lastError = null,
                conversionDate = System.currentTimeMillis(),
            ).also(::writeMetadata)
            return PreparedCopy(resumed, resumedExistingCopy = true)
        }

        val preparing = existing.copy(
            state = WorldProtectionState.PREPARING_ENGINE_COPY,
            engineId = engineId,
            engineVersionId = engineVersionId,
            adapterVersion = adapterVersion,
            updatedAt = System.currentTimeMillis(),
            lastError = null,
            conversionDate = System.currentTimeMillis(),
        )
        writeMetadata(preparing)
        val prepared = try {
            WorldFileIntegrity.replaceWithVerifiedCopy(original, working)
        } catch (error: Throwable) {
            val failed = preparing.copy(
                state = WorldProtectionState.INCOMPATIBLE,
                updatedAt = System.currentTimeMillis(),
                lastError = "Unable to prepare working copy: ${error.message}",
            )
            writeMetadata(failed)
            throw error
        }
        require(prepared.rootHash == existing.sourceWorldHash) { "Prepared copy differs from protected original" }
        val metadata = preparing.copy(
            state = WorldProtectionState.TESTING_COMPATIBILITY,
            preparedWorldHash = prepared.rootHash,
            updatedAt = System.currentTimeMillis(),
        ).also(::writeMetadata)
        return PreparedCopy(metadata, resumedExistingCopy = false)
    }

    fun markCleanProvisionalStop(
        worldName: String,
        engineId: String,
        engineVersionId: String,
        adapterVersion: String,
    ): Metadata {
        val current = requireMetadata(worldName)
        require(current.state == WorldProtectionState.TESTING_COMPATIBILITY ||
            current.state == WorldProtectionState.PROVISIONALLY_LOADED ||
            current.state == WorldProtectionState.PARTIALLY_COMPATIBLE) {
            "World cannot preserve a provisional copy from ${current.state}"
        }
        require(current.engineId == engineId && current.engineVersionId == engineVersionId &&
            current.adapterVersion == adapterVersion) {
            "Provisional world engine identity changed during the protected session"
        }
        val fingerprint = WorldFileIntegrity.fingerprint(resolveWorkingWorld(worldName))
        val warning = "Clean protected stop completed; the same verified engine may resume this provisional working copy"
        return current.copy(
            state = WorldProtectionState.PARTIALLY_COMPATIBLE,
            preparedWorldHash = fingerprint.rootHash,
            updatedAt = System.currentTimeMillis(),
            lastError = null,
            warnings = (current.warnings + warning).distinct().take(MAX_LIST_ITEMS),
        ).also(::writeMetadata)
    }

    fun restoreAfterFailure(worldName: String, compatibilityErrors: List<String>): Metadata {
        val existing = requireMetadata(worldName)
        val original = verifiedOriginal(existing)
        val working = resolveWorkingWorld(worldName)
        val before = working.takeIf { it.isDirectory }?.let(WorldFileIntegrity::fingerprint)
        val restored = WorldFileIntegrity.replaceWithVerifiedCopy(original, working)
        require(restored.rootHash == existing.sourceWorldHash) { "Restored copy differs from protected original" }
        val difference = before?.let { WorldFileIntegrity.compare(restored, it) }
        writeDiagnostic(worldName, existing, compatibilityErrors, difference)
        return existing.copy(
            state = WorldProtectionState.RESTORED_AFTER_FAILURE,
            preparedWorldHash = restored.rootHash,
            updatedAt = System.currentTimeMillis(),
            lastError = compatibilityErrors.joinToString(" | ").take(MAX_ERROR_LENGTH),
        ).also(::writeMetadata)
    }

    fun markIncompatible(worldName: String, errors: List<String>): Metadata = update(worldName) {
        it.copy(
            state = WorldProtectionState.INCOMPATIBLE,
            updatedAt = System.currentTimeMillis(),
            lastError = errors.joinToString(" | ").take(MAX_ERROR_LENGTH),
            unsupportedData = (it.unsupportedData + errors).distinct().take(MAX_LIST_ITEMS),
        )
    }

    fun markPartiallyCompatible(worldName: String, warning: String): Metadata = update(worldName) {
        require(it.state == WorldProtectionState.TESTING_COMPATIBILITY ||
            it.state == WorldProtectionState.PROVISIONALLY_LOADED) {
            "World cannot become provisionally loaded from ${it.state}"
        }
        it.copy(
            state = WorldProtectionState.PROVISIONALLY_LOADED,
            updatedAt = System.currentTimeMillis(),
            lastError = warning.take(MAX_ERROR_LENGTH),
            warnings = (it.warnings + warning).distinct().take(MAX_LIST_ITEMS),
        )
    }

    internal fun markCompatible(
        worldName: String,
        evidence: ProtectedWorldVerificationEvidence,
    ): Metadata {
        val failures = evidence.failures()
        require(failures.isEmpty()) {
            "Protected world verification is incomplete: ${failures.joinToString(" | ")}"
        }
        return update(worldName) { current ->
            require(current.state == WorldProtectionState.TESTING_COMPATIBILITY ||
                current.state == WorldProtectionState.PROVISIONALLY_LOADED ||
                current.state == WorldProtectionState.PARTIALLY_COMPATIBLE) {
                "World cannot become compatible from ${current.state}"
            }
            val working = WorldFileIntegrity.fingerprint(resolveWorkingWorld(worldName))
            current.copy(
                state = WorldProtectionState.COMPATIBLE,
                preparedWorldHash = working.rootHash,
                updatedAt = System.currentTimeMillis(),
                lastError = null,
            )
        }
    }

    fun registrationExists(worldName: String): Boolean = metadataFile(worldName).isFile

    fun metadata(worldName: String): Metadata? {
        val file = metadataFile(worldName)
        if (!file.isFile) return null
        return runCatching { readMetadata(file) }.getOrNull()
    }

    fun hasProtectedOriginal(worldName: String): Boolean = runCatching {
        val data = requireMetadata(worldName)
        verifiedOriginal(data).isDirectory
    }.getOrDefault(false)

    fun renameRegistration(oldWorldName: String, newWorldName: String): Boolean {
        if (oldWorldName == newWorldName) return metadata(oldWorldName) != null
        val oldFile = metadataFile(oldWorldName)
        if (!oldFile.isFile) return false
        val newFile = metadataFile(newWorldName)
        require(!newFile.exists()) { "Protected metadata already exists for '$newWorldName'" }

        val current = readMetadata(oldFile)
        verifiedOriginal(current)
        val oldBucket = File(originalsRoot, worldKey(oldWorldName))
        val newBucket = File(originalsRoot, worldKey(newWorldName))
        require(oldBucket.isDirectory) { "Protected original bucket is missing for '$oldWorldName'" }
        require(!newBucket.exists()) { "Protected original bucket already exists for '$newWorldName'" }

        moveDirectory(oldBucket, newBucket)
        try {
            val newOriginal = File(newBucket, "${current.sourceWorldHash}/world").canonicalFile
            val movedFingerprint = WorldFileIntegrity.fingerprint(newOriginal)
            require(movedFingerprint.rootHash == current.sourceWorldHash) {
                "Renamed protected original fingerprint mismatch"
            }
            val updated = current.copy(
                worldName = newWorldName,
                originalWorldPath = newOriginal.absolutePath,
                backupLocation = newOriginal.absolutePath,
                updatedAt = System.currentTimeMillis(),
            )
            writeMetadata(updated)
            if (!oldFile.delete()) {
                newFile.delete()
                error("Unable to remove old protected metadata for '$oldWorldName'")
            }
            return true
        } catch (error: Throwable) {
            newFile.delete()
            runCatching {
                if (newBucket.exists() && !oldBucket.exists()) moveDirectory(newBucket, oldBucket)
            }
            throw error
        }
    }

    fun deleteRegistration(worldName: String): Boolean {
        val metadata = metadata(worldName)
        val metadataFile = metadataFile(worldName)
        if (metadata == null && !metadataFile.exists()) return false
        metadata?.let { verifiedOriginal(it) }
        val originalBucket = File(originalsRoot, worldKey(worldName))
        if (originalBucket.exists() && !originalBucket.deleteRecursively()) {
            error("Unable to remove protected original for '$worldName'")
        }
        if (metadataFile.exists() && !metadataFile.delete()) {
            error("Unable to remove protected metadata for '$worldName'")
        }
        return true
    }

    private fun verifiedOriginal(metadata: Metadata): File {
        val original = File(metadata.originalWorldPath).canonicalFile
        val allowedRoot = originalsRoot.canonicalFile
        require(original.path.startsWith(allowedRoot.path + File.separator)) { "Protected original path escaped its root" }
        val fingerprint = WorldFileIntegrity.fingerprint(original)
        require(fingerprint.rootHash == metadata.sourceWorldHash) { "Protected original fingerprint mismatch" }
        return original
    }

    private fun resolveWorkingWorld(worldName: String): File {
        require(worldName.isNotBlank() && '/' !in worldName && '\\' !in worldName && worldName != "." && worldName != "..") {
            "Invalid world name"
        }
        val root = worldsRoot.canonicalFile
        val world = File(root, worldName).canonicalFile
        require(world.path.startsWith(root.path + File.separator)) { "World path escaped the worlds directory" }
        return world
    }

    private fun update(worldName: String, transform: (Metadata) -> Metadata): Metadata {
        val updated = transform(requireMetadata(worldName))
        writeMetadata(updated)
        return updated
    }

    private fun requireMetadata(worldName: String): Metadata =
        metadata(worldName) ?: error("No protected original is registered for '$worldName'")

    private fun writeDiagnostic(
        worldName: String,
        metadata: Metadata,
        errors: List<String>,
        difference: WorldFileIntegrity.Difference?,
    ) {
        diagnosticsRoot.mkdirs()
        val file = File(diagnosticsRoot, "${worldKey(worldName)}-${System.currentTimeMillis()}.properties")
        val values = Properties().apply {
            setProperty("worldName", worldName)
            setProperty("sourceWorldHash", metadata.sourceWorldHash)
            setProperty("engineId", metadata.engineId.orEmpty())
            setProperty("engineVersionId", metadata.engineVersionId.orEmpty())
            setProperty("adapterVersion", metadata.adapterVersion.orEmpty())
            setProperty("sourceWorldVersion", metadata.sourceWorldVersion.orEmpty())
            setProperty("sourceStorageVersion", metadata.sourceStorageVersion?.toString().orEmpty())
            setProperty("sourceNetworkVersion", metadata.sourceNetworkVersion?.toString().orEmpty())
            setProperty("backupLocation", metadata.backupLocation)
            setProperty("errors", errors.joinToString(" || "))
            setProperty("added", difference?.added?.sorted()?.joinToString("|").orEmpty())
            setProperty("removed", difference?.removed?.sorted()?.joinToString("|").orEmpty())
            setProperty("changed", difference?.changed?.sorted()?.joinToString("|").orEmpty())
        }
        atomicProperties(file, values, "MineHost world compatibility failure")
    }

    private fun writeMetadata(metadata: Metadata) {
        val values = Properties().apply {
            setProperty("worldName", metadata.worldName)
            setProperty("sourceWorldHash", metadata.sourceWorldHash)
            setProperty("originalWorldPath", metadata.originalWorldPath)
            setProperty("state", metadata.state.name)
            metadata.engineId?.let { setProperty("engineId", it) }
            metadata.engineVersionId?.let { setProperty("engineVersionId", it) }
            metadata.adapterVersion?.let { setProperty("adapterVersion", it) }
            metadata.preparedWorldHash?.let { setProperty("preparedWorldHash", it) }
            metadata.importTransactionId?.let { setProperty("importTransactionId", it) }
            setProperty("importedAt", metadata.importedAt.toString())
            setProperty("updatedAt", metadata.updatedAt.toString())
            metadata.lastError?.let { setProperty("lastError", it) }
            metadata.sourceWorldVersion?.let { setProperty("sourceWorldVersion", it) }
            metadata.sourceStorageVersion?.let { setProperty("sourceStorageVersion", it.toString()) }
            metadata.sourceNetworkVersion?.let { setProperty("sourceNetworkVersion", it.toString()) }
            setProperty("backupLocation", metadata.backupLocation)
            metadata.conversionDate?.let { setProperty("conversionDate", it.toString()) }
            writeList(this, "warnings", metadata.warnings)
            writeList(this, "unsupportedData", metadata.unsupportedData)
        }
        atomicProperties(metadataFile(metadata.worldName), values, "MineHost protected world metadata")
    }

    fun readMetadata(worldName: String): Metadata = requireMetadata(worldName)

    fun readMetadata(file: File): Metadata {
        val values = Properties().apply { file.inputStream().use(::load) }
        return Metadata(
            worldName = values.getProperty("worldName") ?: error("Missing worldName"),
            sourceWorldHash = values.getProperty("sourceWorldHash") ?: error("Missing sourceWorldHash"),
            originalWorldPath = values.getProperty("originalWorldPath") ?: error("Missing originalWorldPath"),
            state = WorldProtectionState.valueOf(values.getProperty("state")),
            engineId = values.getProperty("engineId"),
            engineVersionId = values.getProperty("engineVersionId"),
            adapterVersion = values.getProperty("adapterVersion"),
            preparedWorldHash = values.getProperty("preparedWorldHash"),
            importedAt = values.getProperty("importedAt").toLong(),
            updatedAt = values.getProperty("updatedAt").toLong(),
            lastError = values.getProperty("lastError"),
            sourceWorldVersion = values.getProperty("sourceWorldVersion"),
            sourceStorageVersion = values.getProperty("sourceStorageVersion")?.toIntOrNull(),
            sourceNetworkVersion = values.getProperty("sourceNetworkVersion")?.toIntOrNull(),
            backupLocation = values.getProperty("backupLocation", values.getProperty("originalWorldPath")),
            conversionDate = values.getProperty("conversionDate")?.toLongOrNull(),
            warnings = readList(values, "warnings"),
            unsupportedData = readList(values, "unsupportedData"),
            importTransactionId = values.getProperty("importTransactionId"),
        )
    }

    private fun writeList(values: Properties, prefix: String, entries: List<String>) {
        val safe = entries.filter { it.isNotBlank() }.distinct().take(MAX_LIST_ITEMS)
        values.setProperty("$prefix.count", safe.size.toString())
        safe.forEachIndexed { index, value ->
            values.setProperty("$prefix.$index", value.take(MAX_ERROR_LENGTH))
        }
    }

    private fun readList(values: Properties, prefix: String): List<String> {
        val count = values.getProperty("$prefix.count")?.toIntOrNull()?.coerceIn(0, MAX_LIST_ITEMS) ?: 0
        return (0 until count).mapNotNull { values.getProperty("$prefix.$it") }.filter { it.isNotBlank() }
    }


    private fun moveDirectory(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Throwable) {
            try {
                Files.move(source.toPath(), destination.toPath())
            } catch (_: Throwable) {
                require(source.copyRecursively(destination, overwrite = false)) {
                    "Unable to copy ${source.absolutePath} to ${destination.absolutePath}"
                }
                if (!source.deleteRecursively()) {
                    destination.deleteRecursively()
                    error("Unable to remove source after moving ${source.absolutePath}")
                }
            }
        }
    }

    fun sidecarManifest(
        worldName: String,
    ): ImportedWorldSidecarManifest {
        val protectionFile = metadataFile(worldName).takeIf(File::isFile)

        val markerFile = ImportedWorldVerificationStore.markerFileForBackup(
            serverRoot,
            worldName,
        ).takeIf(File::isFile)

        val playerFile = ImportedPlayerMigrationStore(serverRoot)
            .profileFileForBackup(worldName)
            .takeIf(File::isFile)

        fun relative(file: File?): String? =
            file?.canonicalFile
                ?.relativeTo(serverRoot.canonicalFile)
                ?.path

        return ImportedWorldSidecarManifest(
            worldName = worldName,
            protectionMetadataRelativePath = relative(protectionFile),
            verificationMarkerRelativePath = relative(markerFile),
            playerMigrationRelativePath = relative(playerFile),
            // Immutable originals are content-addressed and never overwritten.
            // Keep the previous bucket in place instead of copying a complete
            // world into rollback storage. Restored metadata will point to it.
            originalBucketRelativePath = null,
        )
    }

    fun backupSidecars(
        manifest: ImportedWorldSidecarManifest,
        rollbackRoot: File,
    ) {
        val paths = listOfNotNull(
            manifest.protectionMetadataRelativePath,
            manifest.verificationMarkerRelativePath,
            manifest.playerMigrationRelativePath,
        )

        for (relative in paths) {
            val source = File(serverRoot, relative)
            if (!source.exists()) continue

            val target = File(rollbackRoot, relative)

            if (source.isDirectory) {
                WorldFileIntegrity.copyVerified(source, target)
            } else {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
            }
        }

        File(rollbackRoot, "manifest.properties")
            .outputStream()
            .use { output ->
                Properties().apply {
                    setProperty("worldName", manifest.worldName)
                    manifest.protectionMetadataRelativePath?.let { setProperty("protectionMetadata", it) }
                    manifest.verificationMarkerRelativePath?.let { setProperty("verificationMarker", it) }
                    manifest.playerMigrationRelativePath?.let { setProperty("playerMigration", it) }
                }.store(output, "MineHost sidecar rollback")
            }
    }

    fun removeMutableSidecars(worldName: String) {
        ImportedPlayerMigrationStore(serverRoot).delete(worldName)
        ImportedWorldVerificationStore.delete(serverRoot, worldName)
        val metadata = metadataFile(worldName)
        if (metadata.exists() && !metadata.delete()) {
            error("Unable to remove protection metadata for '$worldName'")
        }
    }

    fun restoreSidecarsFromBackup(rollbackRoot: File) {
        val manifestFile = File(rollbackRoot, "manifest.properties")
        require(manifestFile.isFile) {
            "Sidecar rollback manifest is missing"
        }
        val properties = Properties().apply {
            manifestFile.inputStream().use(::load)
        }
        val paths = listOfNotNull(
            properties.getProperty("protectionMetadata"),
            properties.getProperty("verificationMarker"),
            properties.getProperty("playerMigration"),
        ).distinct()

        for (relative in paths) {
            val source = File(rollbackRoot, relative).canonicalFile
            val target = File(serverRoot, relative).canonicalFile
            require(source.toPath().startsWith(rollbackRoot.canonicalFile.toPath())) {
                "Sidecar rollback path escaped its transaction directory"
            }
            require(target.toPath().startsWith(serverRoot.canonicalFile.toPath())) {
                "Sidecar restore path escaped the server directory"
            }
            require(source.exists()) { "Missing sidecar rollback item: $relative" }

            target.parentFile?.mkdirs()
            if (source.isDirectory) {
                if (target.exists()) target.deleteRecursively()
                val copied = WorldFileIntegrity.copyVerified(source, target)
                require(copied.rootHash == WorldFileIntegrity.fingerprint(source).rootHash) {
                    "Restored sidecar directory fingerprint mismatch: $relative"
                }
            } else {
                source.copyTo(target, overwrite = true)
                require(target.isFile && target.length() == source.length()) {
                    "Unable to restore sidecar file: $relative"
                }
            }
        }
    }

    fun deleteMetadataOnly(worldName: String): Boolean {
        val file = metadataFile(worldName)
        return !file.exists() || file.delete()
    }

    fun metadataFile(worldName: String): File = File(metadataRoot, "${worldKey(worldName)}.properties")

    private fun atomicProperties(destination: File, properties: Properties, comment: String) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, ".${destination.name}.part-${UUID.randomUUID()}")
        try {
            part.outputStream().use { properties.store(it, comment) }
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
        } finally {
            part.delete()
        }
    }

    private fun worldKey(worldName: String): String = MessageDigest.getInstance("SHA-256")
        .digest(worldName.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
        const val MAX_ERROR_LENGTH = 16_000
        const val MAX_LIST_ITEMS = 2_048
    }
}
