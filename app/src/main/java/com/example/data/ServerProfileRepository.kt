package com.example.data

import android.content.Context
import android.util.Log
import com.example.server.Downloader
import com.example.server.PortAllocator
import com.example.server.updates.AtomicJsonFileStore
import com.example.server.version.EngineVersionCatalogRepository
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.EngineInstallability
import com.example.server.version.ResolvedEngineVersion
import com.example.server.version.installability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * UUID-authoritative profile store. Every mutation is serialized through one
 * mutex and writes both the master list and per-server metadata transactionally.
 */
class ServerProfileRepository(
    private val context: Context,
    private val versionCatalog: EngineVersionCatalogRepository? = null,
) {
    private val serversDir = File(context.filesDir, "servers").apply { mkdirs() }
    private val profilesStore = AtomicJsonFileStore(File(serversDir, "profiles.json"))
    private val mutationMutex = Mutex()
    @Volatile private var loadedOnce = false

    private val _profiles = MutableStateFlow<List<ServerProfile>>(emptyList())
    val profiles: StateFlow<List<ServerProfile>> = _profiles.asStateFlow()

    suspend fun loadProfiles() = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val loaded = loadProfilesLocked()
            _profiles.value = loaded
            loadedOnce = true
            reconcileDeleteTransactionsLocked(loaded)
        }
    }

    suspend fun createProfile(draft: ServerCreationDraft): Result<ServerProfile> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            versionCatalog?.let { catalog ->
                val selected = catalog.findVersion(draft.engineVersionId)
                    ?: return@withLock Result.failure(IOException("Selected engine version is not in the verified catalog"))
                if (selected.engineId != draft.engineId) {
                    return@withLock Result.failure(IOException("Selected engine version belongs to another engine"))
                }
                when (selected.installability()) {
                    EngineInstallability.UNAVAILABLE -> return@withLock Result.failure(
                        IOException(selected.unavailableReason ?: "Selected engine version is unavailable")
                    )
                    EngineInstallability.MANUAL_VERIFICATION_REQUIRED -> {
                        if (Downloader.getTrustedChecksumForInstall(context, selected) == null) {
                            return@withLock Result.failure(
                                IOException("This engine has no publisher SHA-256 and no exact manually verified cached JAR")
                            )
                        }
                    }
                    EngineInstallability.AUTOMATIC_DOWNLOAD -> Unit
                }
            }

            val current = ensureLoadedLocked()
            // Allocation must use the network type that will be STORED, not
            // the raw draft: java_paper profiles persist as JAVA_TCP, so
            // allocating on a UDP draft view lets two paper profiles collide
            // on the same TCP port (surfaced by updateProfile's conflict guard).
            val storedNetworkType =
                if (draft.engineId == "java_paper") ServerNetworkType.JAVA_TCP else draft.networkType
            val transport = storedNetworkType.portTransport()
            val usedPorts = current.asSequence()
                .filter { it.networkType.portTransport() == transport }
                .mapTo(linkedSetOf()) { it.port }
            val port = PortAllocator.findAvailable(draft.port, usedPorts, transport)
                ?: return@withLock Result.failure(IOException("No available ${transport.name} port was found"))

            val id = UUID.randomUUID().toString()
            val profileDir = File(serversDir, id).canonicalFile
            check(profileDir.parentFile == serversDir.canonicalFile) { "Unsafe profile directory" }
            if (!profileDir.mkdirs() && !profileDir.isDirectory) {
                return@withLock Result.failure(IOException("Failed to create server directory"))
            }

            val now = System.currentTimeMillis()
            val profile = ServerProfile(
                id = id,
                name = draft.name.trim().ifBlank { "MineHost Server" },
                engineId = draft.engineId,
                engineVersionId = draft.engineVersionId,
                bedrockVersion = draft.bedrockVersion,
                serverDirectory = profileDir.absolutePath,
                levelName = draft.levelName.safeLevelName(),
                worldSeed = draft.worldSeed,
                worldSeedMode = draft.worldSeedMode,
                worldSeedKnown = draft.worldSeedKnown,
                gameMode = draft.gameMode,
                difficulty = draft.difficulty,
                levelType = draft.levelType,
                iconPath = draft.iconPath,
                port = port,
                memoryMb = draft.memoryMb.coerceIn(ServerProfile.MIN_MEMORY_MB, ServerProfile.MAX_MEMORY_MB),
                maxPlayers = draft.maxPlayers.coerceIn(1, 100),
                createdAt = now,
                updatedAt = now,
                onlineMode = draft.onlineMode,
                autoRestart = draft.autoRestart,
                autoBackup = draft.autoBackup,
                edition = if (draft.engineId == "java_paper") ServerEdition.JAVA else draft.edition,
                networkType = storedNetworkType,
                minecraftVersion = draft.minecraftVersion ?: draft.bedrockVersion,
                minecraftEulaAccepted = draft.minecraftEulaAccepted,
            )

            val metadataResult = saveProfileMetadataLocked(profile)
            if (metadataResult.isFailure) {
                profileDir.deleteRecursively()
                return@withLock Result.failure(metadataResult.exceptionOrNull() ?: IOException("Failed to save profile metadata"))
            }

            val next = current + profile
            val masterResult = saveProfilesLocked(next)
            if (masterResult.isFailure) {
                profileDir.deleteRecursively()
                return@withLock Result.failure(masterResult.exceptionOrNull() ?: IOException("Failed to save profiles"))
            }
            _profiles.value = next
            Result.success(profile)
        }
    }

    suspend fun updateProfile(serverId: String, changes: ServerProfileChanges): Result<ServerProfile> =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val currentList = ensureLoadedLocked()
                val index = currentList.indexOfFirst { it.id == serverId }
                if (index < 0) return@withLock Result.failure(IOException("Profile not found"))
                val current = currentList[index]

                val requestedEdition = changes.edition ?: current.edition
                val requestedNetworkType = changes.networkType ?: current.networkType
                val requestedMinecraftVersion = changes.minecraftVersion ?: current.minecraftVersion
                val transport = requestedNetworkType.portTransport()

                val requestedPort = changes.port ?: current.port
                val otherPorts = currentList.asSequence()
                    .filter { it.id != serverId && it.networkType.portTransport() == transport }
                    .mapTo(linkedSetOf()) { it.port }
                if (requestedPort in otherPorts) {
                    val suggestion = PortAllocator.findAvailable(requestedPort + 1, otherPorts, transport)
                    return@withLock Result.failure(
                        IOException("${transport.name} port $requestedPort belongs to another profile." + suggestion?.let { " Suggested port: $it." }.orEmpty()),
                    )
                }
                if (requestedPort != current.port) {
                    val portResult = PortAllocator.validatePort(requestedPort, transport, otherPorts)
                    if (!portResult.available) {
                        return@withLock Result.failure(
                            IOException((portResult.reason ?: "${transport.name} port is unavailable") + portResult.suggestedPort?.let { " Suggested port: $it." }.orEmpty()),
                        )
                    }
                }

                // Part 4 Seed Guard: Block seed changes if world already contains generated chunks
                if (changes.worldSeed != null && changes.worldSeed != current.worldSeed) {
                    if (worldHasGeneratedChunks(File(current.serverDirectory), current.levelName)) {
                        return@withLock Result.failure(
                            IOException("This world already contains generated chunks. Changing the seed without regenerating the world would mix terrain generated from different seeds. Back up and regenerate the world first.")
                        )
                    }
                }

                val updated = current.copy(
                    name = changes.name?.trim()?.ifBlank { current.name } ?: current.name,
                    engineId = changes.engineId ?: current.engineId,
                    engineVersionId = changes.engineVersionId ?: current.engineVersionId,
                    bedrockVersion = changes.bedrockVersion ?: current.bedrockVersion,
                    levelName = changes.levelName?.safeLevelName() ?: current.levelName,
                    worldSeed = changes.worldSeed ?: current.worldSeed,
                    worldSeedMode = changes.worldSeedMode ?: current.worldSeedMode,
                    worldSeedKnown = changes.worldSeedKnown ?: current.worldSeedKnown,
                    gameMode = changes.gameMode ?: current.gameMode,
                    difficulty = changes.difficulty ?: current.difficulty,
                    levelType = changes.levelType ?: current.levelType,
                    iconPath = changes.iconPath ?: current.iconPath,
                    port = requestedPort,
                    memoryMb = (changes.memoryMb ?: current.memoryMb)
                        .coerceIn(ServerProfile.MIN_MEMORY_MB, ServerProfile.MAX_MEMORY_MB),
                    maxPlayers = (changes.maxPlayers ?: current.maxPlayers).coerceIn(1, 100),
                    isFavorite = changes.isFavorite ?: current.isFavorite,
                    onlineMode = changes.onlineMode ?: current.onlineMode,
                    autoRestart = changes.autoRestart ?: current.autoRestart,
                    autoBackup = changes.autoBackup ?: current.autoBackup,
                    lastStartedSessionId = changes.lastStartedSessionId ?: current.lastStartedSessionId,
                    lastStartedAt = changes.lastStartedAt ?: current.lastStartedAt,
                    edition = requestedEdition,
                    networkType = requestedNetworkType,
                    minecraftVersion = requestedMinecraftVersion,
                    updatedAt = System.currentTimeMillis(),
                    resolvedIdentity = changes.resolvedIdentity ?: current.resolvedIdentity,
                    minecraftEulaAccepted = changes.minecraftEulaAccepted ?: current.minecraftEulaAccepted,
                )

                val profileDir = File(current.serverDirectory).canonicalFile
                val profileMetaFile = File(profileDir, ".minehost/profile.json")
                val engineMetaFile = File(profileDir, ".minehost/engine-installation.json")
                val oldProfileMeta = profileMetaFile.takeIf(File::isFile)?.readText()
                val oldEngineMeta = engineMetaFile.takeIf(File::isFile)?.readText()

                val metadataResult = saveProfileMetadataLocked(updated)
                if (metadataResult.isFailure) return@withLock metadataResult.map { updated }

                if (updated.engineVersionId != current.engineVersionId || updated.bedrockVersion != current.bedrockVersion) {
                    val installed = InstalledEngineVersionRepository.read(profileDir)
                    val version = versionCatalog?.findVersion(updated.engineVersionId)
                    if (installed != null && version != null) {
                        val installationUpdated = installed.copy(
                            engineId = updated.engineId,
                            versionId = updated.engineVersionId,
                            versionName = version.versionName,
                            bedrockVersion = updated.bedrockVersion,
                            jarFileName = version.jarFileName,
                            runtimeJavaVersion = version.runtimeJavaVersion,
                            launchMode = version.launchMode,
                            mainClass = version.mainClass,
                        )
                        if (!InstalledEngineVersionRepository.write(profileDir, installationUpdated)) {
                            restoreJsonFile(profileMetaFile, oldProfileMeta)
                            return@withLock Result.failure(IOException("Failed to update engine installation metadata"))
                        }
                    }
                }

                val next = currentList.toMutableList().apply { set(index, updated) }
                val masterResult = saveProfilesLocked(next)
                if (masterResult.isFailure) {
                    restoreJsonFile(profileMetaFile, oldProfileMeta)
                    restoreJsonFile(engineMetaFile, oldEngineMeta)
                    return@withLock Result.failure(masterResult.exceptionOrNull() ?: IOException("Failed to save profiles"))
                }

                _profiles.value = next
                Result.success(updated)
            }
        }

    suspend fun deleteProfile(serverId: String, deleteFiles: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            val current = ensureLoadedLocked()
            val profile = current.find { it.id == serverId }
                ?: return@withLock Result.failure(IOException("Profile not found"))
            val profileRoot = File(profile.serverDirectory).canonicalFile
            val marker = File(profileRoot, DELETE_MARKER)

            if (deleteFiles) {
                marker.parentFile?.mkdirs()
                val markerResult = AtomicJsonFileStore(marker).save(
                    JSONObject()
                        .put("serverUuid", serverId)
                        .put("createdAt", System.currentTimeMillis())
                        .toString(2),
                )
                if (markerResult !is StorageResult.Success) {
                    return@withLock Result.failure(IOException("Unable to prepare profile deletion"))
                }
            }

            val next = current.filterNot { it.id == serverId }
            val masterResult = saveProfilesLocked(next)
            if (masterResult.isFailure) {
                marker.delete()
                return@withLock masterResult
            }

            _profiles.value = next
            if (deleteFiles && profileRoot.exists() && !profileRoot.deleteRecursively()) {
                Log.w(TAG, "Profile metadata was removed but server directory could not be deleted: ${profileRoot.absolutePath}")
            }
            Result.success(Unit)
        }
    }

    suspend fun getProfile(serverId: String): ServerProfile? = profiles.value.find { it.id == serverId }

    suspend fun setFavorite(serverId: String, favorite: Boolean): Result<Unit> =
        updateProfile(serverId, ServerProfileChanges(isFavorite = favorite)).map { Unit }

    fun getServerProfileRoot(serverId: String): File {
        require(UUID_REGEX.matches(serverId)) { "Unsafe server ID" }
        val root = File(serversDir, serverId).canonicalFile
        require(root.parentFile == serversDir.canonicalFile) { "Unsafe server ID" }
        return root
    }

    private fun ensureLoadedLocked(): List<ServerProfile> {
        if (loadedOnce) return _profiles.value
        val loaded = loadProfilesLocked()
        _profiles.value = loaded
        loadedOnce = true
        reconcileDeleteTransactionsLocked(loaded)
        return loaded
    }

    private fun loadProfilesLocked(): List<ServerProfile> {
        val loadResult = profilesStore.loadRaw()
        val json = when (loadResult) {
            is StorageResult.Success -> loadResult.value
            is StorageResult.Recovered -> {
                Log.w(TAG, loadResult.warning)
                loadResult.value
            }
            is StorageResult.Missing -> {
                val recovered = emergencyScanIndividualProfilesLocked()
                if (recovered.isNotEmpty()) saveProfilesLocked(recovered)
                return recovered
            }
            is StorageResult.Corrupt -> {
                Log.e(TAG, "Profiles file corrupt: ${loadResult.warning}")
                val recovered = emergencyScanIndividualProfilesLocked()
                if (recovered.isNotEmpty()) saveProfilesLocked(recovered)
                return recovered
            }
            is StorageResult.Failure -> {
                Log.e(TAG, "Profiles load failed: ${loadResult.message}")
                val recovered = emergencyScanIndividualProfilesLocked()
                if (recovered.isNotEmpty()) saveProfilesLocked(recovered)
                return recovered
            }
        }

        return try {
            val array = JSONArray(json)
            val parsed = mutableListOf<ServerProfile>()
            var migrationNeeded = loadResult is StorageResult.Recovered
            val seenIds = hashSetOf<String>()
            for (index in 0 until array.length()) {
                val obj = runCatching { array.getJSONObject(index) }.getOrNull() ?: continue
                val id = obj.optString("id")
                val engineId = obj.optString("engineId")
                if (!UUID_REGEX.matches(id) || engineId.isBlank() || !seenIds.add(id)) {
                    migrationNeeded = true
                    continue
                }
                val versionId = obj.optString("engineVersionId").ifBlank {
                    migrationNeeded = true
                    versionCatalog?.getDefaultVersion(engineId)?.id.orEmpty()
                }
                if (versionId.isBlank()) continue
                val catalogVersion = versionCatalog?.findVersion(versionId)
                val bedrockVersion = obj.optString("bedrockVersion").ifBlank {
                    migrationNeeded = true
                    if (catalogVersion?.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) {
                        "AUTO"
                    } else {
                        catalogVersion?.recommendedBedrockVersion.orEmpty()
                    }
                }
                runCatching { parseProfile(obj, versionId, bedrockVersion) }
                    .onSuccess(parsed::add)
                    .onFailure { Log.e(TAG, "Skipping malformed profile $id: ${it.message}") }
            }
            val knownIds = parsed.mapTo(hashSetOf()) { it.id }
            val orphaned = emergencyScanIndividualProfilesLocked().filter { it.id !in knownIds }
            if (orphaned.isNotEmpty()) {
                parsed += orphaned
                migrationNeeded = true
            }
            val stable = parsed.distinctBy { it.id }.sortedBy { it.createdAt }
            if (migrationNeeded) saveProfilesLocked(stable)
            stable
        } catch (error: Throwable) {
            Log.e(TAG, "Master profile JSON could not be parsed: ${error.message}")
            emergencyScanIndividualProfilesLocked().also { if (it.isNotEmpty()) saveProfilesLocked(it) }
        }
    }

    private fun emergencyScanIndividualProfilesLocked(): List<ServerProfile> {
        return serversDir.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") && UUID_REGEX.matches(it.name) }
            .filterNot { File(it, DELETE_MARKER).exists() }
            .mapNotNull { folder ->
                val store = AtomicJsonFileStore(File(folder, ".minehost/profile.json"))
                val raw = when (val result = store.loadRaw()) {
                    is StorageResult.Success -> result.value
                    is StorageResult.Recovered -> result.value
                    else -> return@mapNotNull null
                }
                runCatching {
                    val obj = JSONObject(raw)
                    val id = obj.optString("id", folder.name)
                    if (id != folder.name) return@runCatching null
                    val engineId = obj.getString("engineId")
                    val versionId = obj.optString("engineVersionId").ifBlank {
                        versionCatalog?.getDefaultVersion(engineId)?.id.orEmpty()
                    }
                    if (versionId.isBlank()) return@runCatching null
                    val bedrock = obj.optString("bedrockVersion").ifBlank { "AUTO" }
                    parseProfile(obj.put("serverDirectory", folder.absolutePath), versionId, bedrock)
                }.getOrNull()
            }
            .sortedBy { it.createdAt }
            .toList()
    }

    private fun reconcileDeleteTransactionsLocked(liveProfiles: List<ServerProfile>) {
        val liveIds = liveProfiles.mapTo(hashSetOf()) { it.id }
        serversDir.listFiles().orEmpty()
            .filter { it.isDirectory && UUID_REGEX.matches(it.name) }
            .forEach { folder ->
                val marker = File(folder, DELETE_MARKER)
                if (!marker.exists()) return@forEach
                if (folder.name in liveIds) {
                    marker.delete()
                } else {
                    folder.deleteRecursively()
                }
            }
    }

    private fun saveProfilesLocked(list: List<ServerProfile>): Result<Unit> {
        val array = JSONArray()
        list.sortedBy { it.createdAt }.forEach { array.put(serializeProfile(it)) }
        return when (val result = profilesStore.save(array.toString(2))) {
            is StorageResult.Success -> Result.success(Unit)
            is StorageResult.Failure -> Result.failure(result.cause ?: IOException(result.message))
            is StorageResult.Corrupt -> Result.failure(result.cause ?: IOException(result.warning))
            else -> Result.failure(IOException("Unexpected profile storage result"))
        }
    }

    private fun saveProfileMetadataLocked(profile: ServerProfile): Result<Unit> {
        val root = getServerProfileRoot(profile.id)
        if (!root.exists() && !root.mkdirs()) return Result.failure(IOException("Unable to create profile directory"))
        val result = AtomicJsonFileStore(File(root, ".minehost/profile.json")).save(serializeProfile(profile).toString(2))
        return if (result is StorageResult.Success) Result.success(Unit)
        else Result.failure(IOException("Unable to save per-server profile metadata"))
    }

    private fun restoreJsonFile(file: File, oldContent: String?) {
        if (oldContent == null) {
            file.delete()
        } else {
            AtomicJsonFileStore(file).save(oldContent)
        }
    }

    private fun parseProfile(obj: JSONObject, versionId: String, bedrockVersion: String): ServerProfile {
        val id = obj.getString("id")
        require(UUID_REGEX.matches(id)) { "Invalid profile UUID" }
        val expectedRoot = getServerProfileRoot(id)
        val storedRoot = obj.optString("serverDirectory")
            .takeIf(String::isNotBlank)
            ?.let(::File)
            ?.let { runCatching { it.canonicalFile }.getOrNull() }
        val root = if (storedRoot?.parentFile == serversDir.canonicalFile && storedRoot.name == id) storedRoot else expectedRoot

        val hasSeed = obj.has("worldSeed")
        val worldSeed = if (hasSeed) obj.getLong("worldSeed") else 0L
        val worldSeedMode = if (obj.has("worldSeedMode")) {
            runCatching { com.example.server.engine.WorldSeedMode.valueOf(obj.optString("worldSeedMode")) }
                .getOrDefault(com.example.server.engine.WorldSeedMode.RANDOM)
        } else com.example.server.engine.WorldSeedMode.RANDOM
        val worldSeedKnown = if (obj.has("worldSeedKnown")) obj.getBoolean("worldSeedKnown") else false

        return ServerProfile(
            id = id,
            name = obj.optString("name", "MineHost Server").ifBlank { "MineHost Server" },
            engineId = obj.getString("engineId"),
            engineVersionId = versionId,
            bedrockVersion = bedrockVersion,
            serverDirectory = root.absolutePath,
            levelName = obj.optString("levelName", "world").safeLevelName(),
            worldSeed = worldSeed,
            worldSeedMode = worldSeedMode,
            worldSeedKnown = worldSeedKnown,
            gameMode = obj.optString("gameMode", "0"),
            difficulty = obj.optString("difficulty", "1"),
            levelType = obj.optString("levelType", "DEFAULT"),
            iconPath = if (obj.isNull("iconPath")) null else obj.optString("iconPath").takeIf(String::isNotBlank),
            port = obj.optInt("port", PortAllocator.DEFAULT_PORT).coerceIn(1024, 65535),
            memoryMb = obj.optInt("memoryMb", 600).coerceIn(ServerProfile.MIN_MEMORY_MB, ServerProfile.MAX_MEMORY_MB),
            maxPlayers = obj.optInt("maxPlayers", 10).coerceIn(1, 100),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
            isFavorite = obj.optBoolean("isFavorite", false),
            onlineMode = obj.optBoolean("onlineMode", true),
            autoRestart = obj.optBoolean("autoRestart", true),
            autoBackup = obj.optBoolean("autoBackup", false),
            lastStartedSessionId = if (obj.isNull("lastStartedSessionId")) null else obj.optString("lastStartedSessionId").takeIf(String::isNotBlank),
            lastStartedAt = if (obj.isNull("lastStartedAt")) null else obj.optLong("lastStartedAt").takeIf { it > 0 },
            edition = runCatching { ServerEdition.valueOf(obj.optString("edition")) }.getOrDefault(ServerEdition.BEDROCK),
            networkType = runCatching { ServerNetworkType.valueOf(obj.optString("networkType")) }.getOrDefault(ServerNetworkType.BEDROCK_RAKNET_UDP),
            minecraftVersion = obj.optString("minecraftVersion").takeIf(String::isNotBlank) ?: bedrockVersion,
            resolvedIdentity = if (obj.has("resolvedIdentity") && !obj.isNull("resolvedIdentity")) parseResolvedIdentity(obj.getJSONObject("resolvedIdentity")) else null,
            minecraftEulaAccepted = obj.optBoolean("minecraftEulaAccepted", false),
        )
    }

    private fun parseResolvedIdentity(obj: JSONObject): ResolvedEngineVersion {
        return ResolvedEngineVersion(
            catalogBaseId = obj.getString("catalogBaseId"),
            effectiveVersionId = obj.getString("effectiveVersionId"),
            engineId = obj.getString("engineId"),
            resolvedBuildNumber = obj.optInt("resolvedBuildNumber", 0),
            resolvedArtifactUrl = obj.getString("resolvedArtifactUrl"),
            artifactSize = obj.getLong("artifactSize"),
            sourceRepository = obj.optString("sourceRepository", "https://github.com/MemoriesOfTime/Nukkit-MOT"),
            sourceRevision = obj.optString("sourceRevision").takeIf { it.isNotBlank() },
            resolvedAt = obj.optLong("resolvedAt", 0L),
            generatorId = obj.optString("generatorId", "nukkit-mot/normal"),
            generatorRevision = obj.optString("generatorRevision", "jenkins-build:${obj.optInt("resolvedBuildNumber")}")
        )
    }

    private fun serializeProfile(profile: ServerProfile): JSONObject = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("engineId", profile.engineId)
        put("engineVersionId", profile.engineVersionId)
        put("bedrockVersion", profile.bedrockVersion)
        put("serverDirectory", profile.serverDirectory)
        put("levelName", profile.levelName)
        if (profile.worldSeedKnown) {
            put("worldSeed", profile.worldSeed)
            put("worldSeedMode", profile.worldSeedMode.name)
            put("worldSeedKnown", true)
        } else {
            put("worldSeedKnown", false)
        }
        put("gameMode", profile.gameMode)
        put("difficulty", profile.difficulty)
        put("levelType", profile.levelType)
        put("iconPath", profile.iconPath)
        put("port", profile.port)
        put("memoryMb", profile.memoryMb.coerceIn(ServerProfile.MIN_MEMORY_MB, ServerProfile.MAX_MEMORY_MB))
        put("maxPlayers", profile.maxPlayers)
        put("createdAt", profile.createdAt)
        put("updatedAt", profile.updatedAt)
        put("isFavorite", profile.isFavorite)
        put("onlineMode", profile.onlineMode)
        put("autoRestart", profile.autoRestart)
        put("autoBackup", profile.autoBackup)
        put("lastStartedSessionId", profile.lastStartedSessionId)
        put("lastStartedAt", profile.lastStartedAt)
        put("edition", profile.edition.name)
        put("networkType", profile.networkType.name)
        put("minecraftVersion", profile.minecraftVersion ?: profile.bedrockVersion)
        put("minecraftEulaAccepted", profile.minecraftEulaAccepted)
        profile.resolvedIdentity?.let { put("resolvedIdentity", serializeResolvedIdentity(it)) }
    }

    private fun serializeResolvedIdentity(id: ResolvedEngineVersion): JSONObject = JSONObject().apply {
        put("catalogBaseId", id.catalogBaseId)
        put("effectiveVersionId", id.effectiveVersionId)
        put("engineId", id.engineId)
        put("resolvedBuildNumber", id.resolvedBuildNumber)
        put("resolvedArtifactUrl", id.resolvedArtifactUrl)
        put("artifactSize", id.artifactSize)
        put("sourceRepository", id.sourceRepository)
        put("sourceRevision", id.sourceRevision)
        put("resolvedAt", id.resolvedAt)
        put("generatorId", id.generatorId)
        put("generatorRevision", id.generatorRevision)
    }

    private fun worldHasGeneratedChunks(serverDir: File, levelName: String): Boolean {
        val worldDir = File(serverDir, "worlds/$levelName")
        if (!worldDir.exists() || !worldDir.isDirectory) return false
        val levelDat = File(worldDir, "level.dat")
        val dbDir = File(worldDir, "db")
        return levelDat.exists() || (dbDir.isDirectory && dbDir.list()?.isNotEmpty() == true)
    }

    private fun String.safeLevelName(): String = trim()
        .replace('/', '_')
        .replace('\\', '_')
        .take(128)
        .ifBlank { "world" }

    companion object {
        private const val TAG = "ServerProfileRepository"
        private const val DELETE_MARKER = ".minehost/delete-transaction.json"
        private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
    }
}
