package com.example.server.version

import android.content.Context
import android.util.Log
import com.example.data.DynamicCatalog
import com.example.data.StorageResult
import com.example.server.updates.AtomicJsonFileStore
import com.example.server.updates.DetectedEngineRelease
import com.example.server.updates.ReleaseVerifier
import com.example.server.template.TemplateRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader

enum class CatalogOrigin { BUNDLED, DYNAMIC }

sealed class CatalogStatus {
    object Idle : CatalogStatus()
    object Loading : CatalogStatus()
    object Success : CatalogStatus()
    data class Error(val message: String) : CatalogStatus()
}

class EngineVersionCatalogRepository(private val context: Context) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val catalogAdapter = moshi.adapter(DynamicCatalog::class.java)
    private val catalogDir = File(context.filesDir, "version-catalog")
    private val dynamicCatalogStore = AtomicJsonFileStore(File(catalogDir, "verified_remote_versions.json"))
    private val catalogMutex = Mutex()

    private val _versions = MutableStateFlow<List<EngineVersion>>(emptyList())
    val versions: StateFlow<List<EngineVersion>> = _versions.asStateFlow()

    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    val status: StateFlow<CatalogStatus> = _status.asStateFlow()

    init {
        reloadRemoteCatalogue()
    }

    fun getAllVersions(): List<EngineVersion> = _versions.value

    fun getVersionsForEngine(engineId: String): List<EngineVersion> {
        return _versions.value.filter { it.engineId == engineId }
    }

    suspend fun promoteRelease(release: DetectedEngineRelease): StorageResult<Unit> {
        return catalogMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val template = TemplateRegistry.getTemplate(release.engineId)
                    if (template != null && !template.available) {
                        return@withContext StorageResult.Failure(
                            template.unavailableReason ?: "Promotion failed: engine is disabled"
                        )
                    }

                    // Requirement 10: Strict validation before promotion
                    if (release.verifiedJavaVersion == null) return@withContext StorageResult.Failure("Promotion failed: missing verified Java version")
                    if (release.verifiedLaunchMode == null) return@withContext StorageResult.Failure("Promotion failed: missing verified launch mode")
                    if (release.artifactName == null) return@withContext StorageResult.Failure("Promotion failed: missing artifact name")
                    if (release.artifactUrl == null) return@withContext StorageResult.Failure("Promotion failed: missing artifact URL")
                    if (release.sourceType == null) return@withContext StorageResult.Failure("Promotion failed: missing source type")
                    if (release.releaseTag == null && release.buildNumber == null) return@withContext StorageResult.Failure("Promotion failed: missing release tag or build number")
                    if (!release.compatibilityVerified) return@withContext StorageResult.Failure("Promotion failed: compatibility not verified")
                    if (release.verifiedBedrockVersions.isEmpty()) return@withContext StorageResult.Failure("Promotion failed: no verified Bedrock versions")
                    val publisherSha256 = release.publisherSha256
                        ?.lowercase()
                        ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                        ?: return@withContext StorageResult.Failure(
                            "Promotion failed: missing trusted publisher SHA-256 checksum"
                        )
                    
                    val launchMode = try { LaunchMode.valueOf(release.verifiedLaunchMode!!) } catch(e: Exception) { null }
                    if (launchMode == null) return@withContext StorageResult.Failure("Promotion failed: invalid launch mode ${release.verifiedLaunchMode}")
                    if (launchMode == LaunchMode.MAIN_CLASS && (release.verifiedMainClass == null || release.verifiedMainClass!!.isBlank())) {
                        return@withContext StorageResult.Failure("Promotion failed: MAIN_CLASS requires verified mainClass")
                    }

                    // Part 12: Stable identity
                    val stableId = when (release.sourceType) {
                        VersionSourceType.GITHUB_RELEASE -> "${release.engineId}:${release.releaseTag}"
                        VersionSourceType.JENKINS_BUILD -> "${release.engineId}:${release.buildNumber}"
                        else -> "${release.engineId}:${release.sourceId}"
                    }
                    
                    val newVersion = EngineVersion(
                        id = stableId,
                        engineId = release.engineId,
                        versionName = release.releaseName,
                        displayName = "${release.engineName} ${release.releaseName}",
                        downloadUrl = release.artifactUrl!!,
                        jarFileName = release.artifactName!!,
                        requiredJavaVersion = release.verifiedJavaVersion!!,
                        runtimeJavaVersion = JavaRuntimeSelector.select(release.verifiedJavaVersion!!)
                            ?: return@withContext StorageResult.Failure("Promotion failed: MineHost has no packaged runtime for Java ${release.verifiedJavaVersion}"),
                        compatibilityLabel = "Verified Build",
                        recommended = false,
                        compatibilityMode = if (release.engineId == "nukkit-mot") CompatibilityMode.MULTI_VERSION else CompatibilityMode.SINGLE_VERSION,
                        supportedBedrockVersions = release.verifiedBedrockVersions,
                        recommendedBedrockVersion = release.verifiedRecommendedBedrockVersion ?: release.verifiedBedrockVersions.firstOrNull(),
                        dynamic = true,
                        historical = false,
                        releaseTag = release.releaseTag,
                        sourceType = release.sourceType!!,
                        launchMode = launchMode,
                        mainClass = release.verifiedMainClass,
                        compatibilitySummary = release.verificationEvidence ?: release.verificationMessage,
                        sourceKey = release.sourceKey,
                        buildNumber = release.buildNumber?.toString(),
                        sourceProject = release.sourceProject,
                        channel = ReleaseChannel.STABLE,
                        sha256 = publisherSha256,
                        releaseDateEpochMillis = release.publishedAt
                    )

                    // Load current remote versions
                    val currentRemote = loadRemoteCatalog().toMutableList()
                    currentRemote.removeAll { it.id == stableId }
                    currentRemote.add(newVersion)
                    
                    val catalog = DynamicCatalog(versions = currentRemote)
                    val saveRes = dynamicCatalogStore.save(catalogAdapter.toJson(catalog))
                    if (saveRes.isFailure) {
                        return@withContext saveRes
                    }

                    // Reload the shared repository
                    reloadRemoteCatalogue()
                    
                    // Confirm findVersion(candidate.id) returns the exact saved candidate
                    val verified = findVersion(stableId)
                    if (verified == null || verified.downloadUrl != newVersion.downloadUrl ||
                        !verified.sha256.equals(publisherSha256, ignoreCase = true)) {
                        return@withContext StorageResult.Failure("Reload verification failed for $stableId")
                    }

                    StorageResult.Success(Unit)
                } catch (e: Exception) {
                    StorageResult.Failure("Failed to promote release: ${e.message}", e)
                }
            }
        }
    }

    fun findVersion(versionId: String): EngineVersion? {
        return _versions.value.find { it.id == versionId }
    }

    fun getDefaultVersion(engineId: String): EngineVersion? {
        val engineVersions = getVersionsForEngine(engineId)
        return engineVersions.find { it.recommended && it.available }
            ?: engineVersions.firstOrNull { it.available }
    }

    fun hasSelectableVersions(engineId: String): Boolean {
        return getVersionsForEngine(engineId).any { it.available }
    }

    fun reloadRemoteCatalogue() {
        _status.value = CatalogStatus.Loading
        val static = loadStaticCatalog()
        val remote = loadRemoteCatalog()
        
        val merged = mergeVersions(static, remote)
        _versions.value = merged
        _status.value = CatalogStatus.Success
    }

    private fun mergeVersions(static: List<EngineVersion>, remote: List<EngineVersion>): List<EngineVersion> {
        val result = mutableListOf<EngineVersion>()
        val seenIds = mutableSetOf<String>()

        // 1. Static versions first
        for (v in static) {
            if (seenIds.contains(v.id)) {
                Log.e("CatalogRepository", "Conflicting duplicate ID in bundled catalog: ${v.id}")
                continue
            }
            result.add(v)
            seenIds.add(v.id)
        }

        // 2. Remote versions
        for (v in remote) {
            if (seenIds.contains(v.id)) {
                val existing = result.find { it.id == v.id }
                if (existing?.downloadUrl == v.downloadUrl) {
                    // Identical duplicate, collapse
                    continue
                }
                Log.e("CatalogRepository", "Remote entry ${v.id} conflicts with existing bundled or remote entry. Rejecting.")
                continue
            }
            result.add(v)
            seenIds.add(v.id)
        }

        // 3. Final validation: Only one recommended per engine
        val finalResult = mutableListOf<EngineVersion>()
        val recommendedEngines = mutableSetOf<String>()
        
        // Prioritize static recommendations if multiple exist (though should be one)
        for (v in result.sortedBy { it.dynamic }) {
            if (v.recommended) {
                if (recommendedEngines.contains(v.engineId)) {
                    Log.w("CatalogRepository", "Multiple recommended versions for engine ${v.engineId}. Unmarking ${v.id}.")
                    finalResult.add(v.copy(recommended = false))
                } else {
                    finalResult.add(v)
                    recommendedEngines.add(v.engineId)
                }
            } else {
                finalResult.add(v)
            }
        }

        return finalResult
    }

    private fun loadStaticCatalog(): List<EngineVersion> {
        return try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("engine_versions", "raw", context.packageName)
            )
            val jsonString = InputStreamReader(inputStream).use { it.readText() }
            parseCatalogJson(jsonString, CatalogOrigin.BUNDLED)
        } catch (e: Exception) {
            Log.e("CatalogRepository", "Error loading static catalog: ${e.message}")
            emptyList()
        }
    }

    private fun loadRemoteCatalog(): List<EngineVersion> {
        val loadResult = dynamicCatalogStore.loadRaw()
        val json = when (loadResult) {
            is StorageResult.Success -> loadResult.value
            is StorageResult.Recovered -> {
                Log.w("CatalogRepository", "Recovered remote catalog from backup: ${loadResult.warning}")
                loadResult.value
            }
            is StorageResult.Missing -> return emptyList()
            is StorageResult.Corrupt -> {
                Log.e("CatalogRepository", "Remote catalog file corrupt: ${loadResult.warning}")
                return emptyList()
            }
            is StorageResult.Failure -> {
                Log.e("CatalogRepository", "Failed to load remote catalog: ${loadResult.message}")
                return emptyList()
            }
        }
        return parseCatalogJson(json, CatalogOrigin.DYNAMIC)
    }

    private fun parseCatalogJson(jsonString: String, origin: CatalogOrigin): List<EngineVersion> {
        val list = mutableListOf<EngineVersion>()
        val root = try { JSONObject(jsonString) } catch (e: Exception) { return emptyList() }
        val versionsArray = root.optJSONArray("versions") ?: return emptyList()
        
        for (i in 0 until versionsArray.length()) {
            try {
                val obj = versionsArray.getJSONObject(i)
                val version = parseVersionObject(obj, origin)
                if (version != null) {
                    list.add(version)
                }
            } catch (e: Exception) {
                Log.e("CatalogRepository", "Error parsing catalog entry: ${e.message}")
            }
        }
        return list
    }

    private fun parseVersionObject(obj: JSONObject, origin: CatalogOrigin): EngineVersion? {
        val id = obj.optString("id", "")
        val engineId = obj.optString("engineId", "")
        val downloadUrl = obj.optString("downloadUrl", "")
        val jarFileName = obj.optString("jarFileName", "")
        val displayName = obj.optString("displayName", "")

        val sourceTypeStr = obj.optString("sourceType", "GITHUB_RELEASE")
        val sourceType = try { VersionSourceType.valueOf(sourceTypeStr) } catch(e: Exception) { 
            Log.e("CatalogRepository", "Rejecting entry $id: Unknown sourceType $sourceTypeStr")
            return null
        }

        val isDynamicApi = sourceType == VersionSourceType.PAPER_API
        
        if (id.isBlank() || engineId.isBlank() || displayName.isBlank() || (!isDynamicApi && downloadUrl.isBlank()) || jarFileName.isBlank()) {
            Log.e("CatalogRepository", "Rejecting entry: Missing required fields (id: $id, engineId: $engineId)")
            return null
        }

        // Part 19: Artifact name validation
        if (!jarFileName.lowercase().endsWith(".jar")) {
            Log.e("CatalogRepository", "Rejecting entry $id: Invalid JAR file name: $jarFileName")
            return null
        }

        // Trust domain check
        if (!isDynamicApi) {
            val url = try { java.net.URL(downloadUrl) } catch (e: Exception) { null }
            if (url == null || url.protocol != "https" || !ReleaseVerifier.isAnyTrustedHost(url.host)) {
                Log.e("CatalogRepository", "Rejecting entry $id: Untrusted or insecure download URL: $downloadUrl")
                return null
            }
        }

        val requiredJava = obj.optInt("requiredJavaVersion", 21)
        if (requiredJava < 8 || requiredJava > 25) {
            Log.e("CatalogRepository", "Rejecting entry $id: Unsupported minimum Java requirement $requiredJava")
            return null
        }
        val runtimeJava = obj.optInt(
            "runtimeJavaVersion",
            JavaRuntimeSelector.select(requiredJava) ?: -1
        )
        if (!JavaRuntimeSelector.isValid(requiredJava, runtimeJava)) {
            Log.e("CatalogRepository", "Rejecting entry $id: Java $runtimeJava cannot satisfy minimum Java $requiredJava")
            return null
        }

        val compatibilityModeStr = obj.optString("compatibilityMode", "SINGLE_VERSION")
        val compatibilityMode = try { CompatibilityMode.valueOf(compatibilityModeStr) } catch(e: Exception) { 
            Log.e("CatalogRepository", "Rejecting entry $id: Unknown compatibilityMode $compatibilityModeStr")
            return null 
        }
        
        val recommendedBedrockVersion = if (obj.isNull("recommendedBedrockVersion")) null else obj.getString("recommendedBedrockVersion")
        val compatibilitySummary = if (obj.isNull("compatibilitySummary")) null else obj.getString("compatibilitySummary")
        
        val supportedArray = obj.optJSONArray("supportedBedrockVersions")
        val supportedList = mutableListOf<String>()
        if (supportedArray != null) {
            for (j in 0 until supportedArray.length()) {
                val v = supportedArray.getString(j)
                if (v.isNotBlank()) supportedList.add(v)
            }
        }

        if (compatibilityMode == CompatibilityMode.SINGLE_VERSION) {
            if (recommendedBedrockVersion == null || recommendedBedrockVersion == "AUTO") {
                Log.e("CatalogRepository", "Rejecting entry $id: SINGLE_VERSION requires specific recommendedBedrockVersion")
                return null
            }
            if (supportedList.isEmpty()) {
                Log.e("CatalogRepository", "Rejecting entry $id: SINGLE_VERSION requires supportedBedrockVersions")
                return null
            }
            if (!supportedList.contains(recommendedBedrockVersion)) {
                Log.e("CatalogRepository", "Rejecting entry $id: Recommended version $recommendedBedrockVersion not in supported list")
                return null
            }
        }

        if (compatibilityMode == CompatibilityMode.MULTI_VERSION && !isDynamicApi) {
            if (compatibilitySummary == null || compatibilitySummary.isBlank()) {
                Log.e("CatalogRepository", "Rejecting entry $id: MULTI_VERSION requires compatibilitySummary")
                return null
            }
            val minRange = if (obj.isNull("minimumSupportedBedrockVersion")) null else obj.getString("minimumSupportedBedrockVersion")
            val maxRange = if (obj.isNull("maximumSupportedBedrockVersion")) null else obj.getString("maximumSupportedBedrockVersion")
            if (supportedList.isEmpty() && (minRange == null || maxRange == null)) {
                Log.e("CatalogRepository", "Rejecting entry $id: MULTI_VERSION requires range or supported list")
                return null
            }
        }

        val launchModeStr = obj.optString("launchMode", "JAVA_JAR")
        val launchMode = try { LaunchMode.valueOf(launchModeStr) } catch(e: Exception) { 
            Log.e("CatalogRepository", "Rejecting entry $id: Unknown launchMode $launchModeStr")
            return null 
        }
        val mainClass = if (obj.isNull("mainClass")) null else obj.getString("mainClass")

        if (launchMode == LaunchMode.MAIN_CLASS && (mainClass == null || mainClass.isBlank())) {
            Log.e("CatalogRepository", "Rejecting entry $id: MAIN_CLASS requires mainClass")
            return null
        }

        val protocolArray = obj.optJSONArray("protocolVersions")
        val protocolVersions = mutableListOf<Int>()
        if (protocolArray != null) {
            for (j in 0 until protocolArray.length()) {
                val protocol = protocolArray.optInt(j, -1)
                if (protocol > 0) protocolVersions += protocol
            }
        }

        return EngineVersion(
            id = id,
            engineId = engineId,
            versionName = obj.optString("versionName", displayName),
            displayName = displayName,
            channel = try { ReleaseChannel.valueOf(obj.optString("channel", "STABLE")) } catch(e: Exception) { ReleaseChannel.STABLE },
            releaseChannel = try { EngineReleaseChannel.valueOf(obj.optString("releaseChannel", "STABLE")) } catch(e: Exception) { EngineReleaseChannel.STABLE },
            downloadUrl = downloadUrl,
            jarFileName = jarFileName,
            requiredJavaVersion = requiredJava,
            compatibilityLabel = obj.optString("compatibilityLabel", ""),
            recommended = obj.optBoolean("recommended", false),
            supportedBedrockVersions = supportedList,
            recommendedBedrockVersion = recommendedBedrockVersion,
            compatibilityMode = compatibilityMode,
            compatibilitySummary = compatibilitySummary,
            minimumSupportedBedrockVersion = if (obj.isNull("minimumSupportedBedrockVersion")) null else obj.getString("minimumSupportedBedrockVersion"),
            maximumSupportedBedrockVersion = if (obj.isNull("maximumSupportedBedrockVersion")) null else obj.getString("maximumSupportedBedrockVersion"),
            historical = obj.optBoolean("historical", false),
            deprecated = obj.optBoolean("deprecated", false),
            deprecationReason = if (obj.isNull("deprecationReason")) null else obj.getString("deprecationReason"),
            releaseTag = if (obj.isNull("releaseTag")) null else obj.getString("releaseTag"),
            buildNumber = if (obj.isNull("buildNumber")) null else obj.getString("buildNumber"),
            sourceProject = if (obj.isNull("sourceProject")) null else obj.getString("sourceProject"),
            sourceKey = if (obj.isNull("sourceKey")) null else obj.getString("sourceKey"),
            sourceType = sourceType,
            artifactName = if (obj.isNull("artifactName")) null else obj.getString("artifactName"),
            runtimeJavaVersion = runtimeJava,
            launchMode = launchMode,
            mainClass = mainClass,
            availabilityCheckUrl = if (obj.isNull("availabilityCheckUrl")) null else obj.getString("availabilityCheckUrl"),
            dynamic = (origin == CatalogOrigin.DYNAMIC || sourceType == VersionSourceType.PAPER_API),
            available = obj.optBoolean("available", true),
            unavailableReason = if (obj.isNull("unavailableReason")) null else obj.getString("unavailableReason"),
            sha256 = if (obj.isNull("sha256")) null else obj.getString("sha256"),
            protocolVersions = protocolVersions.distinct(),
            releaseDateEpochMillis = if (obj.has("releaseDateEpochMillis") && !obj.isNull("releaseDateEpochMillis")) obj.optLong("releaseDateEpochMillis") else null,
            minimumMineHostVersionCode = obj.optInt("minimumMineHostVersionCode", 1).coerceAtLeast(1),
            generatorId = if (obj.has("generatorId") && !obj.isNull("generatorId")) obj.getString("generatorId") else null,
            generatorRevision = if (obj.has("generatorRevision") && !obj.isNull("generatorRevision")) obj.getString("generatorRevision") else null
        )
    }
}
