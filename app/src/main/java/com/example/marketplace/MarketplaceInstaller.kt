package com.example.marketplace

import android.content.Context
import com.example.data.OperationResult
import com.example.plugins.CompatibilityState
import com.example.plugins.MarketplacePlugin
import com.example.plugins.PluginInstallRequest
import com.example.plugins.PluginInstallResult
import com.example.plugins.PluginInstaller
import com.example.world.WorldManagerV2
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Transactional installer for the non-UI MineHost marketplace backend.
 * The catalog supplies immutable hashes and compatibility evidence. Empty or
 * unverified catalogs remain empty instead of inventing downloadable content.
 */
class MarketplaceInstaller(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    enum class WorldChoice { REPLACE_CURRENT, IMPORT_AS_ANOTHER }

    data class RequestContext(
        val serverUuid: String,
        val engineId: String,
        val engineVersionId: String,
        val minecraftVersion: String,
        val javaVersion: Int,
        val serverRoot: File,
        val createBackup: (String) -> OperationResult,
        val serverStopped: Boolean,
    )

    sealed class Result {
        data class Success(
            val message: String,
            val restartRequired: Boolean,
            val installedFiles: List<File> = emptyList(),
        ) : Result()
        data class PendingVerification(
            val message: String,
            val restartRequired: Boolean,
            val installedFiles: List<File> = emptyList(),
        ) : Result()
        data class Failure(val message: String, val rollbackAvailable: Boolean = false) : Result()
    }

    fun install(
        item: MarketplaceItem,
        catalog: MarketplaceCatalog,
        request: RequestContext,
        worldChoice: WorldChoice = WorldChoice.IMPORT_AS_ANOTHER,
        requestedWorldName: String? = null,
    ): Result = installInternal(item, catalog, request, worldChoice, requestedWorldName, linkedSetOf())

    private fun installInternal(
        item: MarketplaceItem,
        catalog: MarketplaceCatalog,
        request: RequestContext,
        worldChoice: WorldChoice,
        requestedWorldName: String?,
        dependencyStack: LinkedHashSet<String>,
    ): Result {
        if (!dependencyStack.add(item.itemId)) {
            return Result.Failure("Marketplace dependency cycle detected: ${(dependencyStack + item.itemId).joinToString(" -> ")}")
        }
        val registry = MarketplaceInstallationRegistry(request.serverRoot)
        item.dependencies.filter { it.required }.forEach { dependency ->
            if (dependency.itemId !in registry.installedIds()) {
                val dependencyItem = catalog.items.firstOrNull { it.itemId == dependency.itemId && it.enabled }
                    ?: return Result.Failure("Required marketplace dependency is unavailable: ${dependency.itemId}")
                when (val dependencyResult = installInternal(
                    dependencyItem, catalog, request, worldChoice, null, LinkedHashSet(dependencyStack),
                )) {
                    is Result.Failure -> return Result.Failure(
                        "Dependency ${dependencyItem.name} failed: ${dependencyResult.message}",
                        dependencyResult.rollbackAvailable,
                    )
                    is Result.PendingVerification -> return Result.PendingVerification(
                        "Dependency ${dependencyItem.name} was installed but must be verified before ${item.name} can be installed. ${dependencyResult.message}",
                        dependencyResult.restartRequired,
                        dependencyResult.installedFiles,
                    )
                    is Result.Success -> Unit
                }
            }
        }

        val validation = validate(item, catalog, request)
        if (validation.isNotEmpty()) return Result.Failure(validation.joinToString("\n"))
        val result = when (item.category) {
            MarketplaceCategory.PLUGIN -> installPlugin(item, catalog, request)
            MarketplaceCategory.RESOURCE_PACK -> installPack(item, request, "resource_packs")
            MarketplaceCategory.BEHAVIOR_PACK -> installPack(item, request, "behavior_packs")
            MarketplaceCategory.WORLD_TEMPLATE -> installWorld(item, request, worldChoice, requestedWorldName)
            MarketplaceCategory.SEED -> installSeed(item, request, requestedWorldName)
            MarketplaceCategory.SERVER_TEMPLATE -> installServerTemplate(item, request, requestedWorldName)
            MarketplaceCategory.JAVA_MOD -> Result.Failure("Java mods remain disabled until a real Java Edition runtime is enabled")
        }
        when (result) {
            is Result.Success -> registry.put(
                MarketplaceInstallationRegistry.Record(
                    itemId = item.itemId,
                    category = item.category,
                    version = item.version,
                    status = MarketplaceInstallationRegistry.Status.INSTALLED,
                    installedAt = System.currentTimeMillis(),
                    files = registry.relativeVerifiedFiles(result.installedFiles),
                    sha256 = item.sha256,
                    message = result.message,
                ),
            )
            is Result.PendingVerification -> registry.put(
                MarketplaceInstallationRegistry.Record(
                    itemId = item.itemId,
                    category = item.category,
                    version = item.version,
                    status = MarketplaceInstallationRegistry.Status.PENDING_VERIFICATION,
                    installedAt = System.currentTimeMillis(),
                    files = registry.relativeVerifiedFiles(result.installedFiles),
                    sha256 = item.sha256,
                    message = result.message,
                ),
            )
            is Result.Failure -> Unit
        }
        return result
    }

    fun validate(item: MarketplaceItem, catalog: MarketplaceCatalog, request: RequestContext): List<String> = buildList {
        if (!item.enabled) add("This marketplace item is disabled by the signed catalog")
        val canonicalRoot = runCatching { request.serverRoot.canonicalFile }.getOrNull()
        if (request.serverUuid.isBlank() || canonicalRoot == null || canonicalRoot.name != request.serverUuid) {
            add("The exact server UUID and directory ownership could not be verified")
        }
        val compatibility = item.compatibility[request.engineId]
        when (compatibility?.state) {
            null, CompatibilityState.UNKNOWN -> add("Compatibility is unknown for ${request.engineId}; installation is blocked")
            CompatibilityState.UNSUPPORTED -> add(compatibility.reason ?: "This item is unsupported on ${request.engineId}")
            else -> {
                listOf(
                    "engine version" to CompatibilityConstraint.version(request.engineVersionId, compatibility.engineVersionRange),
                    "Minecraft version" to CompatibilityConstraint.version(request.minecraftVersion, compatibility.minecraftVersionRange),
                    "Java version" to CompatibilityConstraint.java(request.javaVersion, compatibility.javaRange),
                ).forEach { (label, check) ->
                    if (!check.understood) add("$label compatibility could not be verified: ${check.reason}")
                    else if (!check.matches) add("$label is outside the catalog compatibility range")
                }
            }
        }
        if (item.minecraftVersions.isNotEmpty() && request.minecraftVersion !in item.minecraftVersions) {
            add("Minecraft ${request.minecraftVersion} is not listed as supported by this item")
        }
        item.dependencies.filter { it.required }.forEach { dependency ->
            if (catalog.items.none { it.itemId == dependency.itemId && it.enabled }) {
                add("Required marketplace dependency is unavailable: ${dependency.itemId}")
            }
        }
        val installed = MarketplaceInstallationRegistry(request.serverRoot).installedIds()
        item.conflicts.filter { it in installed }.forEach { conflict ->
            add("Conflicting marketplace item is installed: $conflict")
        }
        if (item.category != MarketplaceCategory.SEED) {
            if (item.downloadUrl?.startsWith("https://") != true) add("A secure download URL is required")
            if (item.sha256?.matches(Regex("[a-f0-9]{64}")) != true) add("A valid SHA-256 is required")
            if ((item.fileSize ?: 0L) <= 0L) add("A verified file size is required")
        }
        if (!request.serverStopped && item.category in setOf(
                MarketplaceCategory.WORLD_TEMPLATE,
                MarketplaceCategory.SEED,
                MarketplaceCategory.SERVER_TEMPLATE,
            )) {
            add("Stop the exact server before changing its world or template")
        }
    }

    private fun installPlugin(item: MarketplaceItem, catalog: MarketplaceCatalog, request: RequestContext): Result {
        val dependencyNames = item.dependencies.filter { it.required }.mapNotNull { dependency ->
            catalog.items.firstOrNull {
                it.itemId == dependency.itemId && it.category == MarketplaceCategory.PLUGIN
            }?.name
        }
        val plugin = MarketplacePlugin(
            pluginId = item.itemId,
            name = item.name,
            author = item.author,
            license = item.license,
            version = item.version,
            downloadUrl = requireNotNull(item.downloadUrl),
            sha256 = requireNotNull(item.sha256),
            fileSize = requireNotNull(item.fileSize),
            dependencies = dependencyNames,
            conflicts = emptyList(),
            restartRequired = true,
            compatibility = item.compatibility,
            description = item.description,
        )
        val installer = PluginInstaller(http, request.createBackup)
        return when (val result = installer.install(
            PluginInstallRequest(
                serverUuid = request.serverUuid,
                engineId = request.engineId,
                engineVersionId = request.engineVersionId,
                minecraftVersion = request.minecraftVersion,
                javaVersion = request.javaVersion,
                serverDirectory = request.serverRoot,
                plugin = plugin,
            ),
        )) {
            is PluginInstallResult.Success -> Result.Success(result.message, result.restartRequired, listOf(result.installedFile))
            is PluginInstallResult.PendingVerification -> Result.PendingVerification(result.message, true, listOf(result.installedFile))
            is PluginInstallResult.Failure -> Result.Failure(result.message, result.rollbackAvailable)
        }
    }

    private fun installWorld(
        item: MarketplaceItem,
        request: RequestContext,
        choice: WorldChoice,
        requestedName: String?,
    ): Result = withDownloadedItem(item, request.serverRoot) { archive ->
        val manager = WorldManagerV2(context, request.serverRoot, request.createBackup)
        val mode = if (choice == WorldChoice.REPLACE_CURRENT) {
            WorldManagerV2.ImportMode.REPLACE_ACTIVE
        } else WorldManagerV2.ImportMode.IMPORT_AS_ANOTHER
        val before = File(request.serverRoot, "worlds").listFiles().orEmpty().filter(File::isDirectory).mapTo(hashSetOf()) { it.canonicalPath }
        val operation = manager.importLocalArchive(archive, "${item.name}.mcworld", mode, requestedName ?: item.name)
        if (!operation.success) return@withDownloadedItem Result.Failure(operation.message, true)
        val installed = if (choice == WorldChoice.REPLACE_CURRENT) {
            listOf(File(request.serverRoot, "worlds/${manager.activeWorldName()}"))
        } else {
            File(request.serverRoot, "worlds").listFiles().orEmpty().filter(File::isDirectory)
                .filter { it.canonicalPath !in before }
        }
        if (installed.isEmpty() || installed.any { !manager.inspect(it).valid }) {
            Result.Failure("World files were copied but final world verification failed", true)
        } else Result.Success(operation.message, true, installed)
    }

    private fun installPack(item: MarketplaceItem, request: RequestContext, folderName: String): Result =
        withDownloadedItem(item, request.serverRoot) { archive ->
            val backup = request.createBackup("before-${item.category.name.lowercase()}-install")
            if (!backup.success) return@withDownloadedItem Result.Failure("Installation cancelled: ${backup.message}")
            val transaction = File(request.serverRoot, ".minehost/marketplace-transactions/${UUID.randomUUID()}")
            val extracted = File(transaction, "extracted").apply { mkdirs() }
            val targetRoot = File(request.serverRoot, folderName).apply { mkdirs() }
            val rollbackRoot = File(request.serverRoot, ".minehost/marketplace-rollbacks").apply { mkdirs() }
            try {
                extractSafely(archive, extracted)
                val packRoot = locatePackRoot(extracted)
                validatePackManifest(packRoot)
                val targetName = safeName(item.name)
                val target = File(targetRoot, targetName)
                val rollback = File(rollbackRoot, "$targetName-${System.currentTimeMillis()}")
                if (target.exists()) moveOrCopy(target, rollback)
                try {
                    moveOrCopy(packRoot, target)
                    validatePackManifest(target)
                } catch (error: Throwable) {
                    target.deleteRecursively()
                    if (rollback.exists()) moveOrCopy(rollback, target)
                    throw error
                }
                transaction.deleteRecursively()
                Result.Success("${item.name} installed and verified; restart the exact server", true, listOf(target))
            } catch (error: Throwable) {
                transaction.deleteRecursively()
                Result.Failure(error.message ?: "Pack installation failed", true)
            }
        }

    private fun installSeed(item: MarketplaceItem, request: RequestContext, requestedName: String?): Result {
        val seed = item.seedValue?.trim().orEmpty()
        if (seed.isBlank() || seed.length > 128 || seed.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return Result.Failure("Seed value is invalid")
        }
        val backup = request.createBackup("before-seed-install")
        if (!backup.success) return Result.Failure("Seed installation cancelled: ${backup.message}")
        val propertiesFile = File(request.serverRoot, "server.properties")
        val properties = Properties()
        if (propertiesFile.isFile) propertiesFile.inputStream().use(properties::load)
        val worldName = uniqueWorldName(request.serverRoot, requestedName ?: item.name)
        properties["level-name"] = worldName
        properties["level-seed"] = seed
        return writePropertiesAtomically(propertiesFile, properties).fold(
            onSuccess = { Result.Success("Seed configured for new world $worldName; restart required", true, listOf(propertiesFile)) },
            onFailure = { Result.Failure(it.message ?: "Seed installation failed", true) },
        )
    }

    private fun installServerTemplate(item: MarketplaceItem, request: RequestContext, requestedWorldName: String?): Result =
        withDownloadedItem(item, request.serverRoot) { archive ->
            val backup = request.createBackup("before-server-template-install")
            if (!backup.success) return@withDownloadedItem Result.Failure("Template installation cancelled: ${backup.message}")
            val transaction = File(request.serverRoot, ".minehost/marketplace-transactions/${UUID.randomUUID()}")
            val extracted = File(transaction, "extracted").apply { mkdirs() }
            val stagedConfig = File(transaction, "staged-config").apply { mkdirs() }
            val rollbackConfig = File(transaction, "rollback-config").apply { mkdirs() }
            val touched = linkedMapOf<File, Boolean>()
            val installedFiles = mutableListOf<File>()
            try {
                extractSafely(archive, extracted)
                val manifest = locateTemplateManifest(extracted, item.manifestPath)
                val root = manifest.parentFile?.canonicalFile ?: throw IllegalStateException("Manifest has no parent directory")
                val json = JSONObject(manifest.readText())
                require(json.optInt("schemaVersion", -1) == 1) { "Unsupported server template schema" }
                require(json.optString("engineId") == request.engineId) { "Template engine does not match selected server" }
                val allowed = setOf("server.properties", "permissions.json", "whitelist.json", "ops.json")
                val configuration = mutableListOf<String>()
                val files = json.optJSONArray("configurationFiles")
                for (index in 0 until (files?.length() ?: 0)) {
                    val relative = files!!.getString(index)
                    require(relative in allowed) { "Template attempted to replace an unapproved file: $relative" }
                    require(relative !in configuration) { "Template contains duplicate configuration file: $relative" }
                    val source = File(root, relative).canonicalFile
                    require(source.parentFile == root && source.isFile && source.length() <= 4L * 1024 * 1024) {
                        "Template file is missing or invalid: $relative"
                    }
                    val staged = File(stagedConfig, relative)
                    source.copyTo(staged, overwrite = false)
                    validateConfigurationFile(relative, staged)
                    configuration += relative
                }

                val worldsBefore = File(request.serverRoot, "worlds").listFiles().orEmpty()
                    .filter(File::isDirectory).mapTo(hashSetOf()) { it.canonicalPath }

                configuration.forEach { relative ->
                    val target = File(request.serverRoot, relative).canonicalFile
                    require(target.parentFile == request.serverRoot.canonicalFile) { "Unsafe configuration destination" }
                    val existed = target.exists()
                    touched[target] = existed
                    if (existed) target.copyTo(File(rollbackConfig, relative), overwrite = true)
                    val part = File(target.parentFile, target.name + ".template.part")
                    File(stagedConfig, relative).copyTo(part, overwrite = true)
                    atomicMove(part, target)
                    validateConfigurationFile(relative, target)
                    installedFiles += target
                }

                val worldPath = json.optString("worldPath").takeIf(String::isNotBlank)
                if (worldPath != null) {
                    val bundledWorld = File(root, worldPath).canonicalFile
                    require(
                        bundledWorld.path.startsWith(root.path + File.separator) && bundledWorld.isDirectory,
                    ) { "Template world path is unsafe or missing" }
                    val worldManager = WorldManagerV2(context, request.serverRoot, request.createBackup)
                    val op = worldManager.importLocalFolder(
                        bundledWorld,
                        WorldManagerV2.ImportMode.IMPORT_AS_ANOTHER,
                        requestedWorldName ?: item.name,
                    )
                    require(op.success) { op.message }
                    val newWorlds = File(request.serverRoot, "worlds").listFiles().orEmpty()
                        .filter(File::isDirectory).filter { it.canonicalPath !in worldsBefore }
                    require(newWorlds.isNotEmpty() && newWorlds.all { worldManager.inspect(it).valid }) {
                        "Template world final verification failed"
                    }
                    installedFiles += newWorlds
                }

                require(installedFiles.isNotEmpty()) { "Server template contains no installable configuration or world" }
                transaction.deleteRecursively()
                Result.Success("Server template installed transactionally; restart required", true, installedFiles)
            } catch (error: Throwable) {
                touched.entries.toList().asReversed().forEach { (target, existed) ->
                    runCatching {
                        target.deleteRecursively()
                        if (existed) {
                            val rollback = File(rollbackConfig, target.name)
                            require(rollback.isFile) { "Rollback copy is missing for ${target.name}" }
                            rollback.copyTo(target, overwrite = true)
                        }
                    }
                }
                transaction.deleteRecursively()
                Result.Failure(error.message ?: "Server template installation failed", true)
            }
        }

    private fun validateConfigurationFile(relative: String, file: File) {
        require(file.isFile && file.length() <= 4L * 1024 * 1024) { "Configuration file is invalid: $relative" }
        when (relative) {
            "server.properties" -> Properties().also { properties ->
                file.inputStream().use(properties::load)
                require(properties.keys.none { key -> key.toString().contains('\n') || key.toString().contains('\r') }) {
                    "server.properties contains an unsafe key"
                }
            }
            "permissions.json", "whitelist.json", "ops.json" -> {
                val text = file.readText()
                require(text.isNotBlank()) { "$relative is empty" }
                val first = text.first { !it.isWhitespace() }
                require(first == '[' || first == '{') { "$relative is not a JSON document" }
                if (first == '[') org.json.JSONArray(text) else JSONObject(text)
            }
        }
    }

    private fun withDownloadedItem(item: MarketplaceItem, serverRoot: File, block: (File) -> Result): Result {
        val transaction = File(serverRoot, ".minehost/marketplace-downloads/${UUID.randomUUID()}").apply { mkdirs() }
        val part = File(transaction, "item.part")
        val verified = File(transaction, "item.bin")
        return try {
            download(requireNotNull(item.downloadUrl), requireNotNull(item.fileSize), part)
            require(part.length() == item.fileSize) { "Downloaded size does not match catalog metadata" }
            require(sha256(part).equals(item.sha256, true)) { "Downloaded SHA-256 does not match catalog metadata" }
            atomicMove(part, verified)
            block(verified)
        } catch (error: Throwable) {
            Result.Failure(error.message ?: "Marketplace download failed")
        } finally {
            transaction.deleteRecursively()
        }
    }

    private fun download(url: String, expectedSize: Long, destination: File) {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Marketplace download failed: HTTP ${response.code}" }
            val body = response.body ?: throw IllegalStateException("Marketplace download returned no body")
            if (body.contentLength() > 0) require(body.contentLength() == expectedSize) { "Catalog size differs from server response" }
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= expectedSize) { "Download exceeded catalog size" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun extractSafely(archive: File, destination: File) {
        var total = 0L
        var entries = 0
        val seen = hashSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/').trimStart('/')
                require(path.isNotBlank() && path.split('/').none { it.isBlank() || it == ".." }) { "Unsafe archive path" }
                require(seen.add(path)) { "Duplicate archive entry: $path" }
                entries++
                require(entries <= 100_000) { "Archive contains too many entries" }
                val target = File(destination, path).canonicalFile
                require(target.path.startsWith(destination.canonicalPath + File.separator)) { "Archive path traversal blocked" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileTotal = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            fileTotal += read
                            total += read
                            require(fileTotal <= 2L * 1024 * 1024 * 1024 && total <= 8L * 1024 * 1024 * 1024) {
                                "Archive exceeds safety limits"
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun locatePackRoot(extracted: File): File {
        val manifests = extracted.walkTopDown().maxDepth(4).filter { it.isFile && it.name == "manifest.json" }.toList()
        require(manifests.size == 1) { "Pack archive must contain exactly one manifest.json" }
        return manifests.single().parentFile ?: throw IllegalStateException("Pack manifest has no parent directory")
    }

    private fun validatePackManifest(root: File) {
        val manifest = File(root, "manifest.json")
        require(manifest.isFile && manifest.length() in 2..(2L * 1024 * 1024)) { "Pack manifest is missing or invalid" }
        val json = JSONObject(manifest.readText())
        require(json.optInt("format_version", -1) > 0) { "Pack format_version is missing" }
        val header = json.optJSONObject("header") ?: throw IllegalArgumentException("Pack header is missing")
        require(header.optString("name").isNotBlank()) { "Pack name is missing" }
        UUID.fromString(header.getString("uuid"))
        require(header.optJSONArray("version")?.length() == 3) { "Pack version is invalid" }
        require(json.optJSONArray("modules")?.length()?.let { it > 0 } == true) { "Pack modules are missing" }
    }

    private fun locateTemplateManifest(extracted: File, requestedPath: String?): File {
        if (!requestedPath.isNullOrBlank()) {
            val file = File(extracted, requestedPath).canonicalFile
            require(file.path.startsWith(extracted.canonicalPath + File.separator) && file.isFile) { "Template manifest path is invalid" }
            return file
        }
        val matches = extracted.walkTopDown().maxDepth(4).filter { it.isFile && it.name == "minehost-template.json" }.toList()
        require(matches.size == 1) { "Template bundle must contain exactly one minehost-template.json" }
        return matches.single()
    }

    private fun uniqueWorldName(serverRoot: File, raw: String): String {
        val worlds = File(serverRoot, "worlds").apply { mkdirs() }
        val base = safeName(raw).ifBlank { "marketplace_world" }
        if (!File(worlds, base).exists()) return base
        for (index in 2..9999) if (!File(worlds, "${base}_$index").exists()) return "${base}_$index"
        return "${base}_${System.currentTimeMillis()}"
    }

    private fun writePropertiesAtomically(file: File, properties: Properties): kotlin.Result<Unit> = runCatching {
        val part = File(file.parentFile, file.name + ".part")
        part.outputStream().use { properties.store(it, "MineHost marketplace settings") }
        atomicMove(part, file)
    }

    private fun safeName(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9_. -]"), "_").trim('.', ' ').take(80)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicMove(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) require(target.deleteRecursively()) { "Unable to replace ${target.name}" }
        if (!source.renameTo(target)) {
            if (source.isDirectory) source.copyRecursively(target, overwrite = true) else source.copyTo(target, overwrite = true)
            require(source.deleteRecursively()) { "Unable to remove staging file" }
        }
    }

    private fun moveOrCopy(source: File, target: File) = atomicMove(source, target)
}
