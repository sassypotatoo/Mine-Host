package com.example.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import com.example.backup.BackupManagerV2
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.updates.AtomicJsonFileStore
import com.example.world.WorldManagerV2
import com.example.plugins.LocalPluginInstallRequest
import com.example.plugins.PluginInstallResult
import com.example.plugins.PluginInstaller
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.example.server.template.TemplateRegistry

class LocalServerDataService(
    private val rootProvider: () -> File,
    private val contextProvider: (() -> Context)? = null,
    private val profileProvider: (() -> ServerProfile?)? = null,
) {
    private val root: File get() = rootProvider().apply { mkdirs() }

    private fun resolve(relativePath: String): File? {
        return runCatching {
            val base = root.canonicalFile
            val file = File(base, relativePath).canonicalFile
            if (file.path == base.path || file.path.startsWith(base.path + File.separator)) file else null
        }.getOrNull()
    }

    fun list(relativePath: String = ""): List<File> =
        resolve(relativePath)?.takeIf { it.isDirectory }?.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()

    fun readText(relativePath: String, maxBytes: Long = 2L * 1024 * 1024): OperationResult {
        val file = resolve(relativePath) ?: return OperationResult(false, "Unsafe file path")
        if (!file.isFile) return OperationResult(false, "File does not exist")
        if (file.length() > maxBytes) return OperationResult(false, "File is too large to edit")
        return runCatching { OperationResult(true, file.readText()) }
            .getOrElse { OperationResult(false, it.message ?: "Unable to read file") }
    }

    fun writeText(relativePath: String, content: String): OperationResult {
        val file = resolve(relativePath) ?: return OperationResult(false, "Unsafe file path")
        return runCatching {
            file.parentFile?.mkdirs()
            atomicWrite(file, content.toByteArray(Charsets.UTF_8))
            OperationResult(true, "Saved ${file.name}")
        }.getOrElse { OperationResult(false, it.message ?: "Unable to save file") }
    }

    fun createFolder(parentPath: String, name: String): OperationResult {
        val cleanName = name.trim().replace('/', '_').replace('\\', '_')
        if (cleanName.isBlank()) return OperationResult(false, "Enter a folder name")
        val folder = resolve(if (parentPath.isBlank()) cleanName else "$parentPath/$cleanName")
            ?: return OperationResult(false, "Unsafe folder path")
        return if (folder.mkdirs()) OperationResult(true, "Folder created")
        else OperationResult(false, if (folder.exists()) "Folder already exists" else "Unable to create folder")
    }

    fun createFile(parentPath: String, name: String): OperationResult {
        val cleanName = name.trim().replace('/', '_').replace('\\', '_')
        if (cleanName.isBlank()) return OperationResult(false, "Enter a file name")
        val file = resolve(if (parentPath.isBlank()) cleanName else "$parentPath/$cleanName")
            ?: return OperationResult(false, "Unsafe file path")
        return runCatching {
            file.parentFile?.mkdirs()
            if (!file.createNewFile()) return OperationResult(false, "File already exists")
            OperationResult(true, "File created")
        }.getOrElse { OperationResult(false, it.message ?: "Unable to create file") }
    }

    fun rename(relativePath: String, newName: String): OperationResult {
        val source = resolve(relativePath) ?: return OperationResult(false, "Unsafe file path")
        val cleanName = newName.trim().replace('/', '_').replace('\\', '_')
        if (cleanName.isBlank()) return OperationResult(false, "Enter a new name")
        val target = File(source.parentFile, cleanName)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            return OperationResult(false, "Unsafe destination")
        }
        return if (source.renameTo(target)) OperationResult(true, "Renamed to $cleanName")
        else OperationResult(false, "Unable to rename")
    }

    fun delete(relativePath: String): OperationResult {
        val target = resolve(relativePath) ?: return OperationResult(false, "Unsafe file path")
        if (target.canonicalPath == root.canonicalPath) return OperationResult(false, "Cannot delete server root")
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        return OperationResult(ok, if (ok) "Deleted ${target.name}" else "Unable to delete ${target.name}")
    }

    fun importDocument(resolver: ContentResolver, uri: Uri, parentPath: String): OperationResult {
        val originalName = queryDisplayName(resolver, uri) ?: "imported_file"
        val name = sanitizeImportedName(originalName)
        if (name.isBlank()) return OperationResult(false, "Selected file has an invalid name")
        val target = resolve(if (parentPath.isBlank()) name else "$parentPath/$name")
            ?: return OperationResult(false, "Unsafe import destination")
        if (target.exists()) return OperationResult(false, "A file named $name already exists")
        return runCatching {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, ".${target.name}.${java.util.UUID.randomUUID()}.part")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(part).use { output ->
                    copyBounded(input, output, MAX_IMPORTED_FILE_BYTES)
                    output.fd.sync()
                }
            } ?: return OperationResult(false, "Unable to open selected file")
            moveAtomically(part, target)
            OperationResult(true, "Imported $name")
        }.getOrElse { OperationResult(false, it.message ?: "Import failed") }
    }

    fun listPlugins(): List<PluginEntry> {
        val dir = File(root, "plugins").apply { mkdirs() }
        return dir.listFiles()?.filter { it.isFile && (it.name.endsWith(".jar", true) || it.name.endsWith(".jar.disabled", true)) }
            ?.sortedBy { it.name.lowercase() }
            ?.map {
                val enabled = it.name.endsWith(".jar", true)
                val base = it.name.removeSuffix(".disabled").removeSuffix(".jar")
                PluginEntry(base, it.name, it.length(), enabled, it.lastModified())
            } ?: emptyList()
    }

    fun togglePlugin(fileName: String, enable: Boolean): OperationResult {
        val pluginDir = File(root, "plugins").apply { mkdirs() }
        val source = File(pluginDir, fileName)
        if (!source.exists() || source.parentFile?.canonicalFile != pluginDir.canonicalFile) {
            return OperationResult(false, "Plugin file not found")
        }
        val targetName = when {
            enable && source.name.endsWith(".disabled") -> source.name.removeSuffix(".disabled")
            !enable && !source.name.endsWith(".disabled") -> source.name + ".disabled"
            else -> source.name
        }
        if (targetName == source.name) return OperationResult(true, "No change required")
        val backup = BackupManagerV2(root).create(backupMetadata(), "before-plugin-toggle")
        if (!backup.success) return OperationResult(false, "Plugin change cancelled: ${backup.message}")
        val target = File(pluginDir, targetName)
        if (target.exists()) return OperationResult(false, "Plugin destination already exists")
        val ok = source.renameTo(target)
        return OperationResult(ok, if (ok) "Plugin ${if (enable) "enabled" else "disabled"}; restart required" else "Unable to update plugin")
    }

    fun importPlugin(resolver: ContentResolver, uri: Uri): OperationResult {
        val displayName = queryDisplayName(resolver, uri) ?: "plugin.jar"
        if (!displayName.endsWith(".jar", true)) return OperationResult(false, "Select a .jar plugin file")
        val profile = profileProvider?.invoke() ?: return OperationResult(false, "Select the exact server profile first")
        val transaction = File(root, ".minehost/local-plugin-import/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val source = File(transaction, "plugin.jar")
        return try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 512L * 1024 * 1024) { "Plugin JAR exceeds the safety limit" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return OperationResult(false, "Unable to open selected plugin")
            val installed = InstalledEngineVersionRepository.read(root)
            val installer = PluginInstaller(createBackup = { reason -> BackupManagerV2(root).create(backupMetadata(), reason) })
            when (val result = installer.installLocal(
                LocalPluginInstallRequest(
                    serverUuid = profile.id,
                    engineId = profile.engineId,
                    engineVersionId = profile.engineVersionId,
                    minecraftVersion = profile.bedrockVersion,
                    javaVersion = installed?.runtimeJavaVersion ?: 17,
                    serverDirectory = root,
                    sourceJar = source,
                ),
            )) {
                is PluginInstallResult.Success -> OperationResult(true, result.message)
                is PluginInstallResult.PendingVerification -> OperationResult(false, result.message + " Start the exact server and verify its real startup logs before MineHost marks this plugin installed.")
                is PluginInstallResult.Failure -> OperationResult(false, result.message)
            }
        } catch (error: Throwable) {
            OperationResult(false, error.message ?: "Plugin import failed")
        } finally {
            transaction.deleteRecursively()
        }
    }

    private fun isJavaProfile(): Boolean {
        val profile = profileProvider?.invoke()
        if (profile != null) {
            return profile.edition == ServerEdition.JAVA || TemplateRegistry.isJavaEditionEngine(profile.engineId)
        }
        val installed = InstalledEngineVersionRepository.read(root)
        if (installed != null) {
            return TemplateRegistry.isJavaEditionEngine(installed.engineId)
        }
        val profileFile = File(root, ".minehost/profile.json")
        if (profileFile.isFile) {
            runCatching {
                val json = JSONObject(profileFile.readText())
                val edition = json.optString("edition")
                val engineId = json.optString("engineId")
                if (edition.equals("JAVA", ignoreCase = true) || TemplateRegistry.isJavaEditionEngine(engineId)) return true
            }
        }
        return false
    }

    fun readProperties(): ServerSettingsState {
        val file = File(root, "server.properties")
        val p = Properties()
        if (file.exists()) runCatching { file.inputStream().use(p::load) }
        val isJava = isJavaProfile()
        val onlineModeVal = if (isJava || p.containsKey("online-mode")) {
            val raw = p.getProperty("online-mode", "true").trim()
            raw.equals("true", ignoreCase = true)
        } else {
            val raw = p.getProperty("xbox-auth", "on").trim()
            raw.equals("on", ignoreCase = true) || raw.equals("true", ignoreCase = true)
        }
        return ServerSettingsState(
            serverName = p.getProperty("motd", if (isJava) "A MineHost Java Server" else "Local Bedrock Server").ifBlank { if (isJava) "A MineHost Java Server" else "Local Bedrock Server" },
            gameMode = p.getProperty("gamemode", "survival"),
            difficulty = p.getProperty("difficulty", "normal"),
            maxPlayers = p.getProperty("max-players", "10").toIntOrNull()?.coerceIn(1, 100) ?: 10,
            whitelistEnabled = p.getProperty("white-list", "off").equals("on", true) || p.getProperty("white-list", "false").toBoolean() || p.getProperty("white-list", "off").equals("true", true),
            onlineMode = onlineModeVal,
            viewDistance = p.getProperty("view-distance", "8").toIntOrNull()?.coerceIn(2, 32) ?: 8,
            port = p.getProperty("server-port", if (isJava) "25565" else "19132").toIntOrNull()?.coerceIn(1024, 65535) ?: (if (isJava) 25565 else 19132),
            levelName = p.getProperty("level-name", "world").ifBlank { "world" }
        )
    }

    fun writeProperties(settings: ServerSettingsState): OperationResult {
        val file = File(root, "server.properties")
        val p = Properties()
        if (file.exists()) runCatching { file.inputStream().use(p::load) }
        val isJava = isJavaProfile()
        p["motd"] = settings.serverName
        p["gamemode"] = settings.gameMode
        p["difficulty"] = settings.difficulty
        p["max-players"] = settings.maxPlayers.toString()
        p["view-distance"] = settings.viewDistance.toString()
        p["server-port"] = settings.port.toString()
        p["server-ip"] = "0.0.0.0"
        p["level-name"] = settings.levelName
        if (isJava) {
            p["white-list"] = if (settings.whitelistEnabled) "true" else "false"
            p["online-mode"] = if (settings.onlineMode) "true" else "false"
            p.remove("xbox-auth")
        } else {
            p["white-list"] = if (settings.whitelistEnabled) "on" else "off"
            p["xbox-auth"] = if (settings.onlineMode) "on" else "off"
        }
        return runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, ".server.properties.${java.util.UUID.randomUUID()}.part")
            FileOutputStream(part).use { output ->
                p.store(output, "MineHost server settings")
                output.fd.sync()
            }
            moveAtomically(part, file)
            OperationResult(true, "Server settings saved")
        }.getOrElse { OperationResult(false, it.message ?: "Unable to save settings") }
    }

    fun listBackups(): List<BackupEntry> {
        val dir = File(root, "backups").apply { mkdirs() }
        return dir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupEntry(it.name, it.length(), it.lastModified(), it.absolutePath) }
            ?: emptyList()
    }

    fun createBackup(): OperationResult = createBackup("manual")

    fun createBackup(reason: String): OperationResult =
        BackupManagerV2(root).create(backupMetadata(), reason)

    fun restoreBackup(fileName: String): OperationResult {
        val backup = File(File(root, "backups"), fileName)
        if (!backup.isFile) return OperationResult(false, "Backup not found")
        return BackupManagerV2(root).restore(backup, backupMetadata())
    }

    fun deleteBackup(fileName: String): OperationResult = BackupManagerV2(root).delete(fileName)

    private fun backupMetadata(): BackupManagerV2.Metadata {
        val profile = profileProvider?.invoke()
        val installed = InstalledEngineVersionRepository.read(root)
        val local = runCatching {
            val file = File(root, ".minehost/profile.json")
            if (file.isFile) JSONObject(file.readText()) else null
        }.getOrNull()
        return BackupManagerV2.Metadata(
            serverUuid = profile?.id ?: local?.optString("id")?.takeIf(String::isNotBlank) ?: root.name,
            engineId = profile?.engineId ?: local?.optString("engineId").orEmpty().ifBlank { installed?.engineId ?: "unknown" },
            engineVersionId = profile?.engineVersionId ?: local?.optString("engineVersionId").orEmpty().ifBlank { installed?.versionId ?: "unknown" },
            javaVersion = installed?.runtimeJavaVersion ?: 17,
            levelName = profile?.levelName ?: readProperties().levelName,
        )
    }

    fun cleanAbandonedTransactions(): OperationResult {
        val context = contextProvider?.invoke()
            ?: return OperationResult(false, "Android context is unavailable")
        val recovery = WorldManagerV2(context, root) { OperationResult(true, "") }
            .cleanAbandonedTransactions()
        return if (recovery.success) {
            OperationResult(true, recovery.messages.joinToString().ifBlank { "World import recovery is clear" })
        } else {
            OperationResult(
                false,
                "World import recovery requires attention: ${recovery.blockedTransactions.joinToString()}",
            )
        }
    }

    fun listWorlds(activeLevelName: String): List<WorldEntry> {
        val context = contextProvider?.invoke() ?: return emptyList()
        val manager = WorldManagerV2(context, root) { OperationResult(true, "") }
        
        val dimensionFolders = setOf("nether", "the_end", "dim-1", "dim1")
        
        val candidates = buildList {
            File(root, "worlds").takeIf { it.isDirectory }?.listFiles()?.filter { 
                it.isDirectory && it.name.lowercase() !in dimensionFolders 
            }?.let(::addAll)
            
            root.listFiles()?.filter { 
                it.isDirectory && 
                it.name.lowercase() !in dimensionFolders &&
                (it.name.equals("world", true) || it.name.startsWith("world_") || it.name.equals(activeLevelName, true)) 
            }?.let(::addAll)
        }.distinctBy { it.canonicalPath }
        
        return candidates.filter { manager.isValidWorld(it) }
            .sortedBy { it.name.lowercase() }
            .map {
                WorldEntry(it.name, it.absolutePath, directorySize(it), it.lastModified(), it.name.equals(activeLevelName, true))
            }
    }

    fun prepareWorldImport(resolver: ContentResolver, uri: Uri, onProgress: (WorldManagerV2.ImportProgress) -> Unit = {}): Result<WorldManagerV2.WorldImportPreview> {
        val displayName = queryDisplayName(resolver, uri) ?: "world.mcworld"
        val context = contextProvider?.invoke() ?: return Result.failure(Exception("Context missing"))
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .prepareArchive(resolver, uri, displayName, profileProvider?.invoke()?.id ?: "unknown", onProgress)
    }

    fun prepareWorldFolderImport(treeUri: Uri, onProgress: (WorldManagerV2.ImportProgress) -> Unit = {}): Result<WorldManagerV2.WorldImportPreview> {
        val context = contextProvider?.invoke() ?: return Result.failure(Exception("Context missing"))
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .prepareFolder(treeUri, profileProvider?.invoke()?.id ?: "unknown", onProgress)
    }

    fun finalizeWorldImport(preview: WorldManagerV2.WorldImportPreview, mode: WorldManagerV2.ImportMode, finalName: String, selectedWorldName: String? = null, onProgress: (WorldManagerV2.ImportProgress) -> Unit = {}): OperationResult {
        val context = contextProvider?.invoke() ?: return OperationResult(false, "Context missing")
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .finalizeImport(preview, mode, finalName, selectedWorldName, onProgress)
    }

    fun importWorldZip(resolver: ContentResolver, uri: Uri): OperationResult {
        val displayName = queryDisplayName(resolver, uri) ?: "world.mcworld"
        val context = contextProvider?.invoke()
            ?: return OperationResult(false, "World Import v2 requires an Android context")
        return WorldManagerV2(
            context = context,
            serverRoot = root,
            createSafetyBackup = { reason -> BackupManagerV2(root).create(backupMetadata(), reason) },
        ).importArchive(
            resolver = resolver,
            uri = uri,
            displayName = displayName,
            mode = WorldManagerV2.ImportMode.IMPORT_AS_ANOTHER,
        )
    }

    fun importWorldFolder(treeUri: Uri, replaceActive: Boolean = false): OperationResult {
        val context = contextProvider?.invoke()
            ?: return OperationResult(false, "World Import v2 requires an Android context")
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .importFolder(
                treeUri = treeUri,
                mode = if (replaceActive) WorldManagerV2.ImportMode.REPLACE_ACTIVE else WorldManagerV2.ImportMode.IMPORT_AS_ANOTHER,
            )
    }

    fun exportWorld(resolver: ContentResolver, worldName: String, destination: Uri): OperationResult {
        val context = contextProvider?.invoke()
            ?: return OperationResult(false, "World Export v2 requires an Android context")
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .exportWorld(resolver, worldName, destination)
    }

    fun renameWorld(oldName: String, newName: String): OperationResult {
        val context = contextProvider?.invoke() ?: return OperationResult(false, "World Manager v2 requires an Android context")
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .renameWorld(oldName, newName)
    }

    fun duplicateWorld(worldName: String, requestedName: String? = null): OperationResult {
        val context = contextProvider?.invoke() ?: return OperationResult(false, "World Manager v2 requires an Android context")
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .duplicateWorld(worldName, requestedName)
    }

    fun deleteWorld(worldName: String): OperationResult {
        val context = contextProvider?.invoke() ?: return OperationResult(false, "World Manager v2 requires an Android context")
        return WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .deleteWorld(worldName)
    }

    fun setActiveWorld(worldName: String): OperationResult {
        val context = contextProvider?.invoke() ?: return OperationResult(false, "World Manager v2 requires an Android context")
        val result = WorldManagerV2(context, root) { reason -> BackupManagerV2(root).create(backupMetadata(), reason) }
            .setActiveWorld(worldName)
        
        if (result.success) {
            val settings = readProperties()
            val profile = readLocalServerProfile(context)
            if (profile != null) {
                saveLocalServerProfile(
                    settings = settings,
                    templateId = profile.templateId,
                    engineVersionId = profile.engineVersionId,
                    bedrockVersion = profile.bedrockVersion,
                    iconPath = profile.iconPath
                )
            }
        }
        return result
    }

    fun directorySize(file: File = root): Long {
        val stack = java.util.ArrayDeque<File>()
        stack.push(file)
        var total = 0L
        val visited = mutableSetOf<String>()

        while (stack.isNotEmpty()) {
            val current = stack.pop()
            val path = try { current.canonicalPath } catch (e: Exception) { current.absolutePath }
            if (!visited.add(path)) continue

            if (current.isFile) {
                total += current.length()
            } else if (current.isDirectory) {
                // Don't follow symlinks for size calculation to avoid loops and double counting
                if (!java.nio.file.Files.isSymbolicLink(current.toPath())) {
                    current.listFiles()?.forEach { stack.push(it) }
                }
            }
        }
        return total
    }

    fun hasLocalServerProfile(): Boolean =
        File(root, ".minehost/profile.json").isFile || File(root, ".minehost/profile.properties").isFile

    fun saveLocalServerProfile(
        settings: ServerSettingsState,
        templateId: String,
        engineVersionId: String,
        bedrockVersion: String,
        iconPath: String?
    ): OperationResult {
        val dir = File(root, ".minehost").apply { mkdirs() }
        val file = File(dir, "profile.json")
        val existing = runCatching { if (file.isFile) JSONObject(file.readText()) else JSONObject() }.getOrDefault(JSONObject())
        val profile = profileProvider?.invoke()
        val obj = existing.apply {
            profile?.let {
                put("id", it.id)
                put("serverDirectory", it.serverDirectory)
                put("port", it.port)
                put("memoryMb", it.memoryMb)
                put("maxPlayers", it.maxPlayers)
                put("onlineMode", it.onlineMode)
                put("autoRestart", it.autoRestart)
                put("autoBackup", it.autoBackup)
            }
            put("name", settings.serverName)
            put("engineId", templateId)
            put("engineVersionId", engineVersionId)
            put("bedrockVersion", bedrockVersion)
            put("levelName", settings.levelName)
            put("iconPath", iconPath)
            if (!has("createdAt")) put("createdAt", System.currentTimeMillis())
            put("updatedAt", System.currentTimeMillis())
        }
        return when (val result = AtomicJsonFileStore(file).save(obj.toString(2))) {
            is StorageResult.Success -> OperationResult(true, "Profile saved")
            else -> OperationResult(false, "Unable to save profile metadata")
        }
    }

    fun readLocalServerProfile(context: android.content.Context): LocalServerProfile? {
        val file = File(root, ".minehost/profile.json")
        if (!file.exists()) {
            // Check legacy properties
            val legacyFile = File(root, ".minehost/profile.properties")
            if (legacyFile.exists()) return readLegacyProfile(context, legacyFile)
            return null
        }
        
        return runCatching {
            val json = JSONObject(file.readText())
            val templateId = json.getString("engineId")
            val engineVersionId = json.getString("engineVersionId")
            val bedrockVersion = json.getString("bedrockVersion")

            LocalServerProfile(
                created = true,
                serverName = json.getString("name"),
                templateId = templateId,
                engineVersionId = engineVersionId,
                bedrockVersion = bedrockVersion,
                levelName = json.getString("levelName"),
                iconPath = if (json.isNull("iconPath")) null else json.getString("iconPath"),
                createdAt = json.getLong("createdAt")
            )
        }.getOrNull()
    }

    private fun readLegacyProfile(context: android.content.Context, file: File): LocalServerProfile? {
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }.getOrNull() ?: return null

        val templateId = p.getProperty("templateId", "")
        val engineVersionId = p.getProperty("engineVersionId", "")
        val bedrockVersion = p.getProperty("bedrockVersion", "")

        return LocalServerProfile(
            created = p.getProperty("created", "false").toBoolean(),
            serverName = p.getProperty("serverName", "Local Bedrock Server"),
            templateId = templateId,
            engineVersionId = engineVersionId,
            bedrockVersion = bedrockVersion,
            levelName = p.getProperty("levelName", "world"),
            iconPath = p.getProperty("iconPath").takeIf { it.isNotBlank() },
            createdAt = p.getProperty("createdAt", "0").toLongOrNull() ?: 0L
        )
    }

    fun saveServerIcon(resolver: ContentResolver, uri: Uri): OperationResult {
        val dir = File(root, ".minehost").apply { mkdirs() }
        val target = File(dir, "server-card-icon") // Internal name without extension for simplicity, or we can keep it
        return runCatching {
            val part = File(dir, ".server-card-icon.${java.util.UUID.randomUUID()}.part")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(part).use { output ->
                    copyBounded(input, output, 20L * 1024 * 1024)
                    output.fd.sync()
                }
            } ?: return OperationResult(false, "Unable to open selected icon")
            moveAtomically(part, target)
            OperationResult(true, target.absolutePath)
        }.getOrElse { OperationResult(false, it.message ?: "Icon save failed") }
    }

    private fun sanitizeImportedName(value: String): String = value.trim()
        .replace(Regex("""[^A-Za-z0-9_. ()\[\]-]"""), "_")
        .trim('.', ' ')
        .take(160)

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            check(total <= maxBytes) { "Selected file exceeds the safety limit" }
            output.write(buffer, 0, read)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val part = File(target.parentFile, ".${target.name}.${java.util.UUID.randomUUID()}.part")
        FileOutputStream(part).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveAtomically(part, target)
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Throwable) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        check(target.isFile) { "Atomic file installation failed" }
    }

    private fun addToZip(zip: ZipOutputStream, source: File, entryName: String) {
        if (source.isDirectory) {
            val children = source.listFiles()
            if (children.isNullOrEmpty()) {
                zip.putNextEntry(ZipEntry("$entryName/"))
                zip.closeEntry()
            } else children.forEach { addToZip(zip, it, "$entryName/${it.name}") }
        } else {
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(source).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else uri.lastPathSegment?.substringAfterLast('/')
        } finally {
            cursor?.close()
        }
    }
    private companion object {
        const val MAX_IMPORTED_FILE_BYTES = 512L * 1024 * 1024
    }

}