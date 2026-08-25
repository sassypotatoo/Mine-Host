package com.example.world

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.OperationResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Real Storage Access Framework world import/export with staging and rollback. */
class WorldManagerV2(
    private val context: Context,
    private val serverRoot: File,
    private val createSafetyBackup: (String) -> OperationResult
) {
    enum class ImportMode { REPLACE_ACTIVE, IMPORT_AS_ANOTHER }
    enum class WorldFormat { BEDROCK_LEVELDB, JAVA_ANVIL, UNKNOWN }

    data class WorldInspection(
        val valid: Boolean,
        val worldRoot: File?,
        val detectedName: String,
        val sizeBytes: Long,
        val message: String,
        val format: WorldFormat = WorldFormat.UNKNOWN,
        val allRoots: List<File> = emptyList(),
        val bedrockMetadata: BedrockLevelMetadata? = null
    )

    data class WorldImportPreview(
        val serverUuid: String,
        val sourceName: String,
        val detectedWorldName: String,
        val format: WorldFormat,
        val sizeBytes: Long,
        val requiredSpace: Long,
        val availableSpace: Long,
        val transactionDir: File,
        val isArchive: Boolean,
        val possibleWorlds: Map<String, File> = emptyMap(),
        val bedrockMetadata: BedrockLevelMetadata? = null
    )

    data class ImportProgress(
        val phase: String,
        val bytesProcessed: Long = 0,
        val totalBytes: Long = 0,
        val filesProcessed: Int = 0,
        val totalFiles: Int = 0,
        val currentFile: String? = null,
        val indeterminate: Boolean = true
    )

    private val worldsRoot = File(serverRoot, "worlds")
    private val transactionRoot = WorldImportJournalManager.transactionRoot(serverRoot)

    fun cleanAbandonedTransactions(): WorldImportRecoveryResult {
        // Recovery owns transaction cleanup. Never delete an incomplete or
        // ambiguous transaction solely because it is old; it may contain the
        // only rollback copy of a world or its protection sidecars.
        return WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
    }

    private data class ImportBudget(var files: Int = 0, var bytes: Long = 0L)

    fun prepareArchive(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        serverUuid: String,
        onProgress: (ImportProgress) -> Unit = {}
    ): Result<WorldImportPreview> = runCatching {
        val lower = displayName.lowercase()
        if (!lower.endsWith(".zip") && !lower.endsWith(".mcworld")) {
            error("Select a .mcworld or .zip world archive")
        }
        onProgress(ImportProgress("Preparing transaction", indeterminate = true))
        val transaction = File(transactionRoot, "preview-${UUID.randomUUID()}").apply { mkdirs() }
        val archive = File(transaction, "source.archive")
        val extracted = File(transaction, "extracted").apply { mkdirs() }

        onProgress(ImportProgress("Reading archive", indeterminate = true))
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(archive).use { output -> copyBounded(input, output, 8L * 1024 * 1024 * 1024) }
        } ?: error("Unable to open selected archive")

        onProgress(ImportProgress("Extracting", indeterminate = true))
        extractArchive(archive, extracted)
        onProgress(ImportProgress("Inspecting", indeterminate = true))
        val inspection = inspect(extracted)
        if (inspection.allRoots.isEmpty()) error("No worlds found in archive")

        val root = inspection.worldRoot ?: inspection.allRoots.first()
        WorldImportPreview(
            serverUuid = serverUuid,
            sourceName = displayName,
            detectedWorldName = root.name,
            format = inspection.format,
            sizeBytes = inspection.sizeBytes,
            requiredSpace = calculateRequiredSpace(extracted),
            availableSpace = getAvailableStorage(),
            transactionDir = transaction,
            isArchive = true,
            possibleWorlds = inspection.allRoots.associateBy { it.name },
            bedrockMetadata = inspection.bedrockMetadata
        )
    }

    fun prepareFolder(
        treeUri: Uri,
        serverUuid: String,
        onProgress: (ImportProgress) -> Unit = {}
    ): Result<WorldImportPreview> = runCatching {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Unable to read the selected folder")
        if (!tree.isDirectory || !tree.canRead()) error("Selected folder is not readable")

        onProgress(ImportProgress("Preparing transaction", indeterminate = true))
        val transaction = File(transactionRoot, "preview-${UUID.randomUUID()}").apply { mkdirs() }
        val copied = File(transaction, "folder").apply { mkdirs() }
        
        onProgress(ImportProgress("Copying folder", indeterminate = true))
        copyDocumentTree(tree, copied, depth = 0, budget = ImportBudget())
        onProgress(ImportProgress("Inspecting", indeterminate = true))
        val inspection = inspect(copied)
        if (inspection.allRoots.isEmpty()) error("No worlds found in selected folder")

        val root = inspection.worldRoot ?: inspection.allRoots.first()
        WorldImportPreview(
            serverUuid = serverUuid,
            sourceName = tree.name ?: "selected_folder",
            detectedWorldName = root.name,
            format = inspection.format,
            sizeBytes = inspection.sizeBytes,
            requiredSpace = calculateRequiredSpace(copied),
            availableSpace = getAvailableStorage(),
            transactionDir = transaction,
            isArchive = false,
            possibleWorlds = inspection.allRoots.associateBy { it.name },
            bedrockMetadata = inspection.bedrockMetadata
        )
    }

    fun finalizeImport(
        preview: WorldImportPreview,
        mode: ImportMode,
        finalName: String,
        selectedWorldName: String? = null,
        onProgress: (ImportProgress) -> Unit = {}
    ): OperationResult {
        return runCatching {
            val root = if (selectedWorldName != null) {
                preview.possibleWorlds[selectedWorldName] ?: error("Selected world root no longer exists")
            } else {
                preview.possibleWorlds[preview.detectedWorldName] ?: preview.possibleWorlds.values.first()
            }
            onProgress(ImportProgress("Installing", indeterminate = true))
            installInspectedWorld(root, mode, finalName, onProgress)
        }.getOrElse { OperationResult(false, it.message ?: "Import finalization failed") }
            .also { 
                onProgress(ImportProgress("Cleaning up", indeterminate = true))
                preview.transactionDir.deleteRecursively() 
            }
    }

    fun importArchive(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mode: ImportMode,
        requestedName: String? = null
    ): OperationResult {
        return prepareArchive(resolver, uri, displayName, "legacy").map { preview ->
            finalizeImport(preview, mode, requestedName ?: preview.detectedWorldName)
        }.getOrElse { OperationResult(false, it.message ?: "Import failed") }
    }

    fun importFolder(
        treeUri: Uri,
        mode: ImportMode,
        requestedName: String? = null
    ): OperationResult {
        return prepareFolder(treeUri, "legacy").map { preview ->
            finalizeImport(preview, mode, requestedName ?: preview.detectedWorldName)
        }.getOrElse { OperationResult(false, it.message ?: "Import failed") }
    }

    /** Import a verified local archive already downloaded into app-private storage. */
    fun importLocalArchive(
        archive: File,
        displayName: String,
        mode: ImportMode,
        requestedName: String? = null
    ): OperationResult {
        val lower = displayName.lowercase()
        if (!archive.isFile || archive.length() <= 0L || (!lower.endsWith(".zip") && !lower.endsWith(".mcworld"))) {
            return OperationResult(false, "Local world archive is invalid")
        }
        val transaction = File(transactionRoot, "local-${UUID.randomUUID()}").apply { mkdirs() }
        val extracted = File(transaction, "extracted").apply { mkdirs() }
        return runCatching {
            extractArchive(archive, extracted)
            installInspectedWorld(extracted, mode, requestedName)
        }.getOrElse { OperationResult(false, it.message ?: "World import failed") }
            .also { transaction.deleteRecursively() }
    }

    /** Import a validated local folder from an app-private marketplace transaction. */
    fun importLocalFolder(
        sourceDirectory: File,
        mode: ImportMode,
        requestedName: String? = null
    ): OperationResult {
        if (!sourceDirectory.isDirectory) return OperationResult(false, "Local world folder is missing")
        return installInspectedWorld(sourceDirectory, mode, requestedName)
    }

    fun inspect(candidate: File): WorldInspection {
        if (!candidate.exists()) return WorldInspection(false, null, "", 0L, "World source is missing")
        val roots = candidate.walkTopDown()
            .maxDepth(6)
            .filter { it.isDirectory }
            .filter { dir -> File(dir, "level.dat").isFile }
            .toList()

        if (roots.isEmpty()) {
            return WorldInspection(
                false,
                null,
                candidate.name,
                size(candidate),
                "No valid world root containing level.dat was found",
                WorldFormat.UNKNOWN
            )
        }

        val results = roots.map { root ->
            val hasDb = File(root, "db").isDirectory
            val hasRegion = File(root, "region").isDirectory
            val format = when {
                hasDb -> WorldFormat.BEDROCK_LEVELDB
                hasRegion -> WorldFormat.JAVA_ANVIL
                else -> WorldFormat.UNKNOWN
            }
            root to format
        }

        // Prioritize Bedrock/Nukkit format
        val bedrockResults = results.filter { it.second == WorldFormat.BEDROCK_LEVELDB }
        
        val best = bedrockResults.firstOrNull() ?: results.first()

        val root = best.first
        val format = best.second

        if (format == WorldFormat.JAVA_ANVIL) {
            return WorldInspection(
                false,
                root,
                root.name,
                size(root),
                "This appears to be a Java Edition world. The selected Bedrock-compatible server engine requires a Bedrock/Nukkit LevelDB world.",
                format,
                allRoots = roots
            )
        }

        if (format == WorldFormat.UNKNOWN) {
            return WorldInspection(
                false,
                root,
                root.name,
                size(root),
                "The world structure is unrecognized (missing db/ or region/ folders).",
                format,
                allRoots = roots
            )
        }

        val metadata = BedrockLevelDatReader.read(File(root, "level.dat")).getOrElse { error ->
            return WorldInspection(
                valid = false,
                worldRoot = root,
                detectedName = root.name.ifBlank { "imported_world" },
                sizeBytes = size(root),
                message = "Bedrock level.dat is unreadable: ${error.message}",
                format = format,
                allRoots = roots
            )
        }

        return WorldInspection(
            valid = true,
            worldRoot = root,
            detectedName = root.name.ifBlank { "imported_world" },
            sizeBytes = size(root),
            message = buildString {
                append("World validated")
                metadata.displayVersion?.let { append(" (Bedrock $it)") }
            },
            format = format,
            allRoots = roots,
            bedrockMetadata = metadata
        )
    }

    fun exportWorld(resolver: ContentResolver, worldName: String, destination: Uri): OperationResult {
        val world = resolveWorld(worldName) ?: return OperationResult(false, "World not found")
        return runCatching {
            resolver.openOutputStream(destination, "w")?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    addDirectory(zip, world, "")
                }
            } ?: error("Unable to open export destination")
            OperationResult(true, "World exported: ${world.name}.mcworld")
        }.getOrElse { OperationResult(false, it.message ?: "World export failed") }
    }

    fun renameWorld(oldName: String, newName: String): OperationResult {
        val source = resolveWorld(oldName) ?: return OperationResult(false, "World not found")
        val clean = sanitizeWorldName(newName)
        if (clean.isBlank()) return OperationResult(false, "Enter a valid world name")
        val target = File(worldsRoot, clean)
        if (target.exists()) return OperationResult(false, "A world named $clean already exists")

        val originalName = source.name
        val wasActive = activeWorldName().equals(originalName, ignoreCase = true)
        val sidecars = captureSidecars(originalName)
        var worldMoved = false
        var sidecarsMoved = false
        var activeWorldChanged = false
        return runCatching {
            moveOrCopy(source, target)
            worldMoved = true
            moveSidecars(originalName, clean, sidecars)
            sidecarsMoved = true
            if (wasActive) {
                val activeResult = setActiveWorld(clean)
                check(activeResult.success) { activeResult.message }
                activeWorldChanged = true
            }
            OperationResult(true, "World renamed to $clean")
        }.getOrElse { error ->
            val rollbackErrors = mutableListOf<String>()
            if (activeWorldChanged) {
                val restoreActive = setActiveWorld(originalName)
                if (!restoreActive.success) rollbackErrors += restoreActive.message
            }
            if (sidecarsMoved) {
                runCatching { moveSidecars(clean, originalName, sidecars) }
                    .onFailure { rollbackErrors += "sidecars: ${it.message}" }
            }
            if (worldMoved && target.exists() && !source.exists()) {
                runCatching { moveOrCopy(target, source) }
                    .onFailure { rollbackErrors += "world folder: ${it.message}" }
            }
            val suffix = rollbackErrors.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "; rollback warnings: ")
                .orEmpty()
            OperationResult(false, (error.message ?: "Unable to rename world") + suffix)
        }
    }

    fun duplicateWorld(worldName: String, requestedName: String? = null): OperationResult {
        if (ServerRunStateStore.isRunning(serverRoot)) {
            return OperationResult(false, "Stop the server before duplicating a world. Copying a live LevelDB database is unsafe.")
        }
        val source = resolveWorld(worldName)
            ?: return OperationResult(false, "World not found")
        val targetName = uniqueName(
            sanitizeWorldName(requestedName ?: "${source.name}_copy")
        )

        // Duplication uses the exact same durable import transaction as archive
        // and folder imports. The duplicate is never exposed under worlds/
        // before inspection, immutable-original preparation, final fingerprint
        // verification, and protection-sidecar commit succeed.
        return installInspectedWorld(
            source = source,
            mode = ImportMode.IMPORT_AS_ANOTHER,
            requestedName = targetName,
        ).let { result ->
            if (result.success) {
                result.copy(message = "World duplicated and protected as $targetName")
            } else {
                result
            }
        }
    }

    fun deleteWorld(worldName: String): OperationResult {
        val world = resolveWorld(worldName) ?: return OperationResult(false, "World not found")
        if (activeWorldName().equals(world.name, true)) {
            return OperationResult(false, "The active world cannot be deleted. Select another world first.")
        }
        val backup = createSafetyBackup("before-world-delete")
        if (!backup.success) return OperationResult(false, "Delete cancelled: ${backup.message}")
        if (!world.deleteRecursively() || world.exists()) {
            return OperationResult(false, "Unable to delete world")
        }
        val cleanupErrors = cleanupSidecars(world.name)
        return if (cleanupErrors.isEmpty()) {
            OperationResult(true, "World and compatibility records deleted")
        } else {
            OperationResult(
                true,
                "World deleted, but stale compatibility records could not be fully removed: ${cleanupErrors.joinToString("; ")}",
            )
        }
    }

    fun setActiveWorld(worldName: String): OperationResult {
        val world = resolveWorld(worldName) ?: return OperationResult(false, "World not found")
        val propertiesFile = File(serverRoot, "server.properties")
        val properties = Properties()
        if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
        properties["level-name"] = world.name
        val part = File(serverRoot, "server.properties.part")
        return runCatching {
            part.outputStream().use { output ->
                properties.store(output, "MineHost server settings")
                output.fd.sync()
            }
            try {
                Files.move(
                    part.toPath(),
                    propertiesFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Throwable) {
                Files.move(part.toPath(), propertiesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(propertiesFile.isFile) { "server.properties was not installed" }
            OperationResult(true, "Active world set to ${world.name}; restart required")
        }.getOrElse {
            part.delete()
            OperationResult(false, it.message ?: "Unable to set active world")
        }
    }

    fun activeWorldName(): String {
        val properties = Properties()
        val file = File(serverRoot, "server.properties")
        if (file.isFile) runCatching { file.inputStream().use(properties::load) }
        return properties.getProperty("level-name", "world").ifBlank { "world" }
    }

    fun calculateRequiredSpace(source: File): Long {
        val inspection = inspect(source)
        if (!inspection.valid || inspection.worldRoot == null) return inspection.sizeBytes
        val sourceSize = inspection.sizeBytes.coerceAtLeast(0L)
        // Staging plus the immutable original require two additional copies.
        // Content-addressed older originals stay in place and are not copied
        // into rollback storage. Saturate on overflow and fail the preflight.
        val copies = if (sourceSize > (Long.MAX_VALUE - STORAGE_MARGIN_BYTES) / 2L) {
            Long.MAX_VALUE
        } else {
            sourceSize * 2L + STORAGE_MARGIN_BYTES
        }
        return copies
    }

    fun getAvailableStorage(): Long = context.filesDir.usableSpace

    fun isValidWorld(folder: File): Boolean {
        if (!folder.isDirectory) return false
        val name = folder.name.lowercase()
        if (name.startsWith(".minehost") || name.startsWith(".") || name.contains("transaction") || name.contains("rollback") || name.contains("staging")) return false
        if (!File(folder, "level.dat").isFile) return false
        if (!File(folder, "db").isDirectory) return false
        if (WorldImportJournalManager.hasIncompleteTransactionForWorld(serverRoot, folder.name)) return false
        val inspection = inspect(folder)
        return inspection.valid && inspection.format == WorldFormat.BEDROCK_LEVELDB
    }

    private data class WorldSidecars(
        val protectedOriginal: Boolean,
        val verificationMarker: Boolean,
        val playerMigration: Boolean,
    ) {
        val isEmpty: Boolean
            get() = !protectedOriginal && !verificationMarker && !playerMigration
    }

    private fun captureSidecars(worldName: String): WorldSidecars = WorldSidecars(
        protectedOriginal = WorldWorkingCopyManager(serverRoot).registrationExists(worldName),
        verificationMarker = ImportedWorldVerificationStore.markerExists(serverRoot, worldName),
        playerMigration = ImportedPlayerMigrationStore(serverRoot).profileExists(worldName),
    )

    private fun moveSidecars(oldWorldName: String, newWorldName: String, sidecars: WorldSidecars) {
        if (sidecars.isEmpty || oldWorldName == newWorldName) return
        val workingCopies = WorldWorkingCopyManager(serverRoot)
        val players = ImportedPlayerMigrationStore(serverRoot)
        var protectedMoved = false
        var markerMoved = false
        var playerMoved = false
        try {
            if (sidecars.protectedOriginal) {
                check(workingCopies.renameRegistration(oldWorldName, newWorldName)) {
                    "Protected-world registration disappeared during rename"
                }
                protectedMoved = true
            }
            if (sidecars.verificationMarker) {
                check(ImportedWorldVerificationStore.rename(serverRoot, oldWorldName, newWorldName)) {
                    "Imported-world verification marker disappeared during rename"
                }
                markerMoved = true
            }
            if (sidecars.playerMigration) {
                check(players.rename(oldWorldName, newWorldName)) {
                    "Imported-player migration profile disappeared during rename"
                }
                playerMoved = true
            }
        } catch (error: Throwable) {
            val rollbackErrors = mutableListOf<String>()
            if (playerMoved) runCatching { players.rename(newWorldName, oldWorldName) }
                .onFailure { rollbackErrors += "player profile: ${it.message}" }
            if (markerMoved) runCatching {
                ImportedWorldVerificationStore.rename(serverRoot, newWorldName, oldWorldName)
            }.onFailure { rollbackErrors += "verification marker: ${it.message}" }
            if (protectedMoved) runCatching { workingCopies.renameRegistration(newWorldName, oldWorldName) }
                .onFailure { rollbackErrors += "protected original: ${it.message}" }
            val suffix = rollbackErrors.takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "; sidecar rollback warnings: ")
                .orEmpty()
            throw IllegalStateException((error.message ?: "Unable to move world compatibility records") + suffix, error)
        }
    }

    private fun cleanupSidecars(worldName: String): List<String> {
        val sidecars = captureSidecars(worldName)
        val errors = mutableListOf<String>()
        if (sidecars.playerMigration) runCatching {
            check(ImportedPlayerMigrationStore(serverRoot).delete(worldName))
        }.onFailure { errors += "player profile: ${it.message}" }
        if (sidecars.verificationMarker) runCatching {
            check(ImportedWorldVerificationStore.delete(serverRoot, worldName))
        }.onFailure { errors += "verification marker: ${it.message}" }
        if (sidecars.protectedOriginal) runCatching {
            check(WorldWorkingCopyManager(serverRoot).deleteRegistration(worldName))
        }.onFailure { errors += "protected original: ${it.message}" }
        return errors
    }

    private fun installInspectedWorld(
        source: File,
        mode: ImportMode,
        requestedName: String?,
        onProgress: (ImportProgress) -> Unit = {}
    ): OperationResult {
        if (ServerRunStateStore.isRunning(serverRoot)) {
            return OperationResult(
                false,
                "Stop the server before importing, replacing, or duplicating a world. " +
                    "Committing files while LevelDB is open is unsafe.",
            )
        }
        val inspection = inspect(source)
        if (!inspection.valid || inspection.worldRoot == null) return OperationResult(false, inspection.message)
        worldsRoot.mkdirs()
        val active = activeWorldName()
        val targetName = when (mode) {
            ImportMode.REPLACE_ACTIVE -> active
            ImportMode.IMPORT_AS_ANOTHER -> uniqueName(sanitizeWorldName(requestedName ?: inspection.detectedName))
        }
        val target = File(worldsRoot, targetName)
        val requiredSpace = calculateRequiredSpace(source)
        val availableSpace = getAvailableStorage()
        if (availableSpace < requiredSpace) {
            return OperationResult(
                false,
                "Not enough storage for a protected import. Required ${requiredSpace / (1024 * 1024)} MB, " +
                    "available ${availableSpace / (1024 * 1024)} MB.",
            )
        }
        if (target.exists()) {
            val backup = createSafetyBackup("before-world-import")
            if (!backup.success) return OperationResult(false, "Import cancelled: ${backup.message}")
        }

        var journal = WorldImportJournalManager.startTransaction(
            serverRoot = serverRoot,
            serverId = "local",
            sourceName = source.name,
            targetWorldName = targetName,
            previousActiveWorld = active,
            replaceActive = mode == ImportMode.REPLACE_ACTIVE
        )
        val txDir = File(WorldImportJournalManager.transactionRoot(serverRoot), journal.transactionId)
        val stagedWorld = File(File(txDir, "staging"), targetName)
        val rollback = File(journal.rollbackPath!!)
        val rollbackSidecarsDir = File(journal.rollbackSidecarPath!!)

        return runCatching {
            // 1. COPYING
            journal = journal.copy(phase = WorldImportPhase.COPYING)
            WorldImportJournalManager.updateJournal(txDir, journal)
            onProgress(ImportProgress("COPYING", indeterminate = true))
            stagedWorld.parentFile?.mkdirs()
            inspection.worldRoot.copyRecursively(stagedWorld, overwrite = true)
            val stagedInspection = inspect(stagedWorld)
            check(stagedInspection.valid) { "Staged world validation failed: ${stagedInspection.message}" }

            // 2. INSPECTING
            journal = journal.copy(phase = WorldImportPhase.INSPECTING)
            WorldImportJournalManager.updateJournal(txDir, journal)
            onProgress(ImportProgress("INSPECTING", indeterminate = true))
            val deepInspection = BedrockWorldInspector().inspect(stagedWorld) { progress ->
                onProgress(
                    ImportProgress(
                        phase = "INSPECTING_LEVELDB",
                        bytesProcessed = progress.bytesProcessed,
                        totalBytes = progress.totalBytes,
                        filesProcessed = progress.filesProcessed,
                        totalFiles = progress.totalFiles,
                        currentFile = progress.currentFile,
                        indeterminate = progress.totalBytes <= 0L,
                    )
                )
            }.getOrElse { error ->
                throw IllegalStateException("Read-only Bedrock inspection failed: ${error.message}", error)
            }
            check(!deepInspection.report.hasFatalInspectionError) {
                "Imported world inspection found fatal errors: ${deepInspection.report.errors.joinToString()}"
            }

            // 3. FINGERPRINTING
            journal = journal.copy(phase = WorldImportPhase.FINGERPRINTING)
            WorldImportJournalManager.updateJournal(txDir, journal)
            onProgress(ImportProgress("FINGERPRINTING", indeterminate = true))
            val stagedHash = WorldFileIntegrity.fingerprint(stagedWorld).rootHash
            check(stagedHash == deepInspection.report.sourceWorldHash) {
                "Staged world fingerprint mismatch after inspection"
            }

            val workingCopyManager = WorldWorkingCopyManager(serverRoot)
            val levelMetadata = stagedInspection.bedrockMetadata ?: BedrockLevelMetadata(
                levelDatVersion = 10,
                declaredPayloadLength = 0,
                levelName = targetName,
                storageVersion = deepInspection.report.storageVersion,
                networkVersion = deepInspection.report.networkVersion,
                lastOpenedWithVersion = deepInspection.report.lastOpenedMinecraftVersion?.split('.')?.mapNotNull { it.toIntOrNull() } ?: emptyList(),
                minimumCompatibleClientVersion = emptyList(),
                spawnX = null,
                spawnY = null,
                spawnZ = null,
                generator = null,
                randomSeed = null,
            )

            // 4. PROTECTING_ORIGINAL
            journal = journal.copy(phase = WorldImportPhase.PROTECTING_ORIGINAL)
            WorldImportJournalManager.updateJournal(txDir, journal)
            onProgress(ImportProgress("PROTECTING_ORIGINAL", indeterminate = true))

            val preparedProtection = workingCopyManager.prepareImportedWorldProtection(
                transactionId = journal.transactionId,
                worldName = targetName,
                stagedWorldDirectory = stagedWorld,
                inspection = deepInspection,
                levelMetadata = levelMetadata,
                transactionDirectory = txDir,
            )

            journal = journal.copy(
                stagedFingerprint = stagedHash,
                preparedOriginalPath = preparedProtection.preparedOriginalDirectory.absolutePath,
                preparedOriginalFingerprint = preparedProtection.sourceFingerprint.rootHash,
                protectionPrepared = true,
            )
            WorldImportJournalManager.updateJournal(txDir, journal)

            val replacingExisting = target.exists()
            if (replacingExisting) {
                journal = journal.copy(oldWorldMoveStarted = true, oldSidecarsMoveStarted = true)
                WorldImportJournalManager.updateJournal(txDir, journal)

                moveOrCopy(target, rollback)
                journal = journal.copy(oldWorldMoved = true)
                WorldImportJournalManager.updateJournal(txDir, journal)

                val sidecarManifest = workingCopyManager.sidecarManifest(targetName)
                val hasSidecars = listOf(
                    sidecarManifest.protectionMetadataRelativePath,
                    sidecarManifest.verificationMarkerRelativePath,
                    sidecarManifest.playerMigrationRelativePath,
                ).any { it != null }
                if (hasSidecars) {
                    rollbackSidecarsDir.mkdirs()
                    workingCopyManager.backupSidecars(
                        manifest = sidecarManifest,
                        rollbackRoot = rollbackSidecarsDir,
                    )
                    // Mutable records must not be silently overwritten by the new import.
                    // The immutable original stays in place and is either safely reused or
                    // superseded by a separately fingerprinted original bucket.
                    workingCopyManager.removeMutableSidecars(targetName)
                }
                journal = journal.copy(oldSidecarsMoved = hasSidecars)
                WorldImportJournalManager.updateJournal(txDir, journal)
            }

            // 5. COMMITTING_WORLD
            journal = journal.copy(
                phase = WorldImportPhase.COMMITTING_WORLD,
                worldCommitStarted = true,
            )
            WorldImportJournalManager.updateJournal(txDir, journal)
            onProgress(ImportProgress("COMMITTING_WORLD", indeterminate = true))

            moveOrCopy(stagedWorld, target)
            val installedHash = WorldFileIntegrity.fingerprint(target).rootHash
            check(installedHash == stagedHash) {
                "Installed world fingerprint mismatch"
            }
            journal = journal.copy(worldCommitted = true)
            WorldImportJournalManager.updateJournal(txDir, journal)

            // 6. CREATING_COMPATIBILITY_RECORDS (sidecars)
            val plannedOriginalRelativePath = preparedProtection.finalOriginalDirectory
                .canonicalFile
                .relativeTo(serverRoot.canonicalFile)
                .path
            journal = journal.copy(
                phase = WorldImportPhase.CREATING_COMPATIBILITY_RECORDS,
                sidecarCommitStarted = true,
                newOriginalCreated = preparedProtection.preparedOriginalOwnedByTransaction,
                newOriginalRelativePath = plannedOriginalRelativePath,
            )
            WorldImportJournalManager.updateJournal(txDir, journal)
            onProgress(ImportProgress("CREATING_COMPATIBILITY_RECORDS", indeterminate = true))

            workingCopyManager.commitImportedWorldProtection(preparedProtection, target)

            journal = journal.copy(
                immutableOriginalCommitted = true,
                protectionMetadataCommitted = true,
                verificationMarkerCommitted = true,
                playerMigrationCommitted = deepInspection.localPlayerRecord != null,
            )
            WorldImportJournalManager.updateJournal(txDir, journal)

            // 7. SETTING_ACTIVE_WORLD
            if (mode == ImportMode.REPLACE_ACTIVE || active.isBlank()) {
                journal = journal.copy(
                    phase = WorldImportPhase.SETTING_ACTIVE_WORLD,
                    activeWorldChangeStarted = true,
                )
                WorldImportJournalManager.updateJournal(txDir, journal)
                onProgress(ImportProgress("SETTING_ACTIVE_WORLD", indeterminate = true))

                val activeResult = setActiveWorld(targetName)
                check(activeResult.success) { activeResult.message }

                journal = journal.copy(activeWorldSet = true)
                WorldImportJournalManager.updateJournal(txDir, journal)
            }

            // 8. CLEANING_UP / COMPLETED
            journal = journal.copy(phase = WorldImportPhase.COMPLETED)
            WorldImportJournalManager.updateJournal(txDir, journal)

            txDir.deleteRecursively()

            OperationResult(true, "World '$targetName' imported and protected")
        }.getOrElse { error ->
            val recovery = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
            val recoverySuffix = if (recovery.success) {
                ""
            } else {
                "; automatic rollback is blocked: " + recovery.blockedTransactions.joinToString()
            }
            OperationResult(false, (error.message ?: "World import failed") + recoverySuffix)
        }
    }

    private fun rollbackSidecarPath(dir: File, name: String): File = File(dir, name)

    private fun copyDocumentTree(source: DocumentFile, destination: File, depth: Int, budget: ImportBudget) {
        check(depth <= MAX_FOLDER_DEPTH) { "Selected folder is nested too deeply" }
        if (source.isDirectory) {
            check(destination.mkdirs() || destination.isDirectory) { "Unable to create import directory" }
            val usedNames = hashSetOf<String>()
            source.listFiles().forEach { child ->
                budget.files++
                check(budget.files <= MAX_FOLDER_ENTRIES) { "Selected folder contains too many files" }
                val original = child.name ?: "unnamed"
                val name = sanitizeFileName(original)
                check(name.isNotBlank()) { "Imported file has an invalid name" }
                val collisionKey = name.lowercase(Locale.ROOT)
                check(usedNames.add(collisionKey)) {
                    "Two imported entries resolve to the same safe filename: $name"
                }
                copyDocumentTree(child, File(destination, name), depth + 1, budget)
            }
        } else if (source.isFile) {
            destination.parentFile?.mkdirs()
            check(!destination.exists()) { "Imported filename collision: ${destination.name}" }
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        fileBytes += read
                        budget.bytes += read
                        check(fileBytes <= MAX_SINGLE_FILE_BYTES) { "Imported file is too large: ${destination.name}" }
                        check(budget.bytes <= MAX_FOLDER_TOTAL_BYTES) { "Selected folder exceeds the import size limit" }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: error("Unable to read ${source.name}")
        }
    }

    private fun extractArchive(archive: File, destination: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            var total = 0L
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                check(count <= 100_000) { "Archive contains too many files" }
                
                check(isSafePath(entry.name, entry.isDirectory)) { "Unsafe archive path: ${entry.name}" }
                
                val normalizedPath = entry.name.replace('\\', '/')
                val target = File(destination, normalizedPath).canonicalFile
                val base = destination.canonicalFile
                if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
                    error("Archive path traversal blocked: ${entry.name}")
                }
                
                if (entry.isDirectory || normalizedPath.endsWith('/')) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            fileBytes += read
                            total += read
                            check(fileBytes <= 2L * 1024 * 1024 * 1024) { "World file is too large" }
                            check(total <= 8L * 1024 * 1024 * 1024) { "World archive is too large" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        directory.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
            val name = if (prefix.isBlank()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                zip.putNextEntry(ZipEntry("$name/"))
                zip.closeEntry()
                addDirectory(zip, child, name)
            } else {
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(child).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun resolveWorld(name: String): File? {
        val clean = sanitizeWorldName(name)
        val file = File(worldsRoot, clean)
        val base = worldsRoot.canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it.isDirectory && it.path.startsWith(base.path + File.separator) }
    }

    private fun uniqueName(baseName: String): String {
        val base = baseName.ifBlank { "imported_world" }
        if (!File(worldsRoot, base).exists()) return base
        for (index in 2..9999) {
            val candidate = "${base}_$index"
            if (!File(worldsRoot, candidate).exists()) return candidate
        }
        return "${base}_${System.currentTimeMillis()}"
    }

    private fun sanitizeWorldName(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9_. -]"), "_")
        .trim('.', ' ')
        .take(80)

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("""[^A-Za-z0-9_. ()\[\]-]"""), "_")
        .trim('.', ' ')
        .take(160)
    internal fun isSafePath(path: String, isDirectory: Boolean): Boolean {
        if (path.isBlank() || '\u0000' in path) return false

        val normalized = path.replace('\\', '/')

        if (normalized.startsWith('/') || normalized.contains("//")) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return false

        val parts = normalized.split('/')
        val lastIsBlank = parts.last().isBlank()

        if (lastIsBlank && !isDirectory) return false
        
        val components = if (lastIsBlank) parts.dropLast(1) else parts
        
        if (components.isEmpty()) return false
        
        return components.all { s ->
            s.isNotBlank() && s != "." && s != ".."
        }
    }

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, max: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= max) { "Selected data exceeds safety limit" }
            output.write(buffer, 0, read)
        }
    }

    private fun size(file: File): Long = if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::size)

    private fun moveOrCopy(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.deleteRecursively()
        if (!source.renameTo(target)) {
            if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
            check(source.deleteRecursively()) { "Unable to remove staging path ${source.name}" }
        }
    }
    private companion object {
        const val MAX_FOLDER_DEPTH = 32
        const val MAX_FOLDER_ENTRIES = 100_000
        const val MAX_SINGLE_FILE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_FOLDER_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        const val STORAGE_MARGIN_BYTES = 50L * 1024 * 1024
    }

}
