package com.example.server

import android.content.Context
import android.util.Log
import com.example.server.version.EngineVersion
import com.example.server.version.ResolvedEngineVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock

data class ResolvedNukkitMotCandidate(
    val buildNumber: Int,
    val buildUrl: String,
    val artifactUrl: String,
    val artifactRelativePath: String,
    val artifactSize: Long?,
    val resolvedAt: Long,
    val sourceRepository: String?,
    val sourceRevision: String?,
    val generatorId: String = "nukkit-mot/normal",
    val generatorRevision: String,
    val expectedSha256: String? = null
)

class NukkitMotResolver(
    private val client: OkHttpClient,
    private val jobRoot: HttpUrl,
    private val clock: Clock
) {
    companion object {
        const val OFFICIAL_REPO_URL = "https://github.com/MemoriesOfTime/Nukkit-MOT"
        const val DEFAULT_JOB_ROOT = "https://motci.cn/job/Nukkit-MOT/job/master"

        fun getLastKnownGoodFile(context: Context): File {
            val dir = File(context.filesDir, "engine-resolution/nukkit-mot")
            dir.mkdirs()
            return File(dir, "last-known-good.json")
        }
    }

    suspend fun resolveCandidates(
        context: Context,
        version: EngineVersion,
        failedBuildNumber: String? = null,
        onProgress: (String) -> Unit
    ): List<ResolvedNukkitMotCandidate> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ResolvedNukkitMotCandidate>()

        // 1. Try app-private last-known-good resolution if present
        val lkgFile = getLastKnownGoodFile(context)
        if (lkgFile.isFile) {
            try {
                val json = JSONObject(lkgFile.readText())
                val engineId = json.optString("engineId", "")
                val catalogBaseId = json.optString("catalogBaseId", "")
                val buildNum = json.optInt("resolvedBuildNumber", -1)
                val artUrl = json.optString("artifactUrl", "")
                val expectedSha256 = json
                    .optString("artifactSha256", "")
                    .trim()
                    .lowercase()
                    .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                val sourceRepository = normalizeOfficialRepository(
                    json.optString("sourceRepository", OFFICIAL_REPO_URL)
                )

                if (
                    engineId == "nukkit-mot" &&
                    catalogBaseId == version.id &&
                    buildNum > 0 &&
                    artUrl.isNotBlank() &&
                    isOfficialArtifactUrl(artUrl, buildNum) &&
                    expectedSha256 != null &&
                    sourceRepository != null &&
                    buildNum.toString() != failedBuildNumber
                ) {
                    onProgress("Checking last-known-good Nukkit-MOT build #$buildNum...")
                    if (checkUrlReachable(artUrl)) {
                        onProgress("Last-known-good Nukkit-MOT build #$buildNum is reachable.")
                        candidates.add(ResolvedNukkitMotCandidate(
                            buildNumber = buildNum,
                            buildUrl = json.optString("resolvedBuildUrl", "$jobRoot/$buildNum/"),
                            artifactUrl = artUrl,
                            artifactRelativePath = json.optString("artifactRelativePath", "target/Nukkit-MOT-SNAPSHOT.jar"),
                            artifactSize = json.optLong("artifactSize").takeIf { it > 0 },
                            resolvedAt = json.optLong("resolvedTime", clock.millis()),
                            sourceRepository = sourceRepository,
                            sourceRevision = json.optString("sourceRevision").takeIf { it.isNotBlank() },
                            generatorRevision = "jenkins-build:$buildNum",
                            expectedSha256 = expectedSha256
                        ))
                    } else {
                        onProgress("Last-known-good URL $artUrl is unreachable. Invalidating entry.")
                        lkgFile.delete()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w("NukkitMotResolver", "Failed to parse last-known-good.json", e)
                lkgFile.delete()
            }
        }

        // 2. Query official Jenkins API
        try {
            val apiUrl = jobRoot.newBuilder().addPathSegment("api").addPathSegment("json")
                .addQueryParameter("tree", "builds[number,url,result,actions[lastBuiltRevision[SHA1],remoteUrls],artifacts[relativePath]]")
                .build()
            
            onProgress("Querying Jenkins API: $apiUrl")
            val request = Request.Builder().url(apiUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onProgress("Jenkins API error: HTTP ${response.code}")
                    return@use
                }
                
                val body = response.body?.string() ?: return@use
                val jobObj = JSONObject(body)
                val buildsArr = jobObj.optJSONArray("builds") ?: return@use
                
                for (i in 0 until buildsArr.length()) {
                    if (candidates.size >= 5) break
                    
                    val bItem = buildsArr.optJSONObject(i) ?: continue
                    val bNum = bItem.optInt("number", -1)
                    val bUrl = bItem.optString("url", "")
                    if (bNum <= 0 || bNum.toString() == failedBuildNumber) continue
                    
                    val resultStr = bItem.optString("result", "")
                    if (resultStr != "SUCCESS") continue

                    val artifactsArr = bItem.optJSONArray("artifacts") ?: continue
                    var targetRelPath: String? = null
                    for (j in 0 until artifactsArr.length()) {
                        val art = artifactsArr.optJSONObject(j) ?: continue
                        val relPath = art.optString("relativePath", "")
                        if (relPath == "target/Nukkit-MOT-SNAPSHOT.jar") {
                            targetRelPath = relPath
                            break
                        }
                    }

                    if (targetRelPath != null) {
                        val artifactUrl = if (bUrl.endsWith("/")) "${bUrl}artifact/$targetRelPath" else "$bUrl/artifact/$targetRelPath"
                        
                        if (!isOfficialArtifactUrl(artifactUrl, bNum)) continue

                        // Check if this URL is already in candidates
                        if (candidates.any { it.artifactUrl == artifactUrl }) continue

                        var gitRepo: String? = null
                        var gitRev: String? = null
                        var repositoryWasDeclared = false

                        val actionsArr = bItem.optJSONArray("actions")
                        if (actionsArr != null) {
                            for (k in 0 until actionsArr.length()) {
                                val action = actionsArr.optJSONObject(k) ?: continue
                                val lastRev = action.optJSONObject("lastBuiltRevision")
                                if (lastRev != null) {
                                    gitRev = lastRev.optString("SHA1").takeIf { it.isNotBlank() }
                                }
                                val remoteUrls = action.optJSONArray("remoteUrls")
                                if (remoteUrls != null && remoteUrls.length() > 0) {
                                    val firstUrl = remoteUrls.optString(0, "")
                                    if (firstUrl.isNotBlank()) {
                                        repositoryWasDeclared = true
                                        gitRepo = normalizeOfficialRepository(firstUrl)
                                    }
                                }
                            }
                        }

                        if (repositoryWasDeclared && gitRepo == null) {
                            onProgress(
                                "Rejecting Jenkins build #$bNum because its " +
                                    "declared source repository is not official."
                            )
                            continue
                        }

                        candidates.add(ResolvedNukkitMotCandidate(
                            buildNumber = bNum,
                            buildUrl = bUrl,
                            artifactUrl = artifactUrl,
                            artifactRelativePath = targetRelPath,
                            artifactSize = null,
                            resolvedAt = clock.millis(),
                            sourceRepository = gitRepo ?: OFFICIAL_REPO_URL,
                            sourceRevision = gitRev,
                            generatorRevision = "jenkins-build:$bNum"
                        ))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            onProgress("Error querying Jenkins API: ${e.message}")
        }

        candidates
    }

    fun saveLastKnownGood(
        context: Context,
        version: EngineVersion,
        resolved: ResolvedNukkitMotCandidate,
        actualFileSize: Long,
        actualSha256: String,
        mainClass: String?,
    ) {
        require(version.engineId == "nukkit-mot") {
            "Last-known-good metadata belongs to another engine"
        }
        require(actualFileSize > 1024L) {
            "Invalid Nukkit-MOT artifact size"
        }
        require(actualSha256.matches(Regex("^[0-9a-f]{64}$"))) {
            "Invalid Nukkit-MOT last-known-good SHA-256"
        }
        require(
            isOfficialArtifactUrl(
                resolved.artifactUrl,
                resolved.buildNumber,
            )
        ) {
            "Refusing to persist a non-official Nukkit-MOT artifact URL"
        }
        require(
            normalizeOfficialRepository(
                resolved.sourceRepository ?: OFFICIAL_REPO_URL
            ) != null
        ) {
            "Refusing to persist a non-official Nukkit-MOT source repository"
        }
        require(
            resolved.generatorId == "nukkit-mot/normal" &&
                resolved.generatorRevision ==
                "jenkins-build:${resolved.buildNumber}"
        ) {
            "Resolved Nukkit-MOT generator identity is inconsistent"
        }

        val file = getLastKnownGoodFile(context)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val json = JSONObject().apply {
            put("schemaVersion", 1)
            put("engineId", "nukkit-mot")
            put("catalogBaseId", version.id)
            put("resolvedBuildNumber", resolved.buildNumber)
            put("resolvedBuildUrl", resolved.buildUrl)
            put("artifactUrl", resolved.artifactUrl)
            put("artifactRelativePath", resolved.artifactRelativePath)
            put("artifactSize", actualFileSize)
            put("artifactSha256", actualSha256)
            put(
                "sourceRepository",
                OFFICIAL_REPO_URL,
            )
            put("sourceRevision", resolved.sourceRevision.orEmpty())
            put("mainClass", mainClass ?: "cn.nukkit.Nukkit")
            put("minimumJavaVersion", version.runtimeJavaVersion)
            put("resolvedTime", resolved.resolvedAt)
        }

        file.parentFile?.mkdirs()
        temporary.writeText(json.toString(2))
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun normalizeOfficialRepository(
        value: String,
    ): String? {
        val normalized = value
            .trim()
            .removeSuffix(".git")
            .removeSuffix("/")

        return when (normalized) {
            "https://github.com/MemoriesOfTime/Nukkit-MOT",
            "git@github.com:MemoriesOfTime/Nukkit-MOT" ->
                OFFICIAL_REPO_URL
            else -> null
        }
    }

    private fun isOfficialArtifactUrl(
        urlString: String,
        expectedBuildNumber: Int? = null,
    ): Boolean {
        val url = runCatching {
            urlString.toHttpUrl()
        }.getOrNull() ?: return false

        if (url.host != jobRoot.host) return false
        if (jobRoot.host == "motci.cn" && url.scheme != "https") {
            return false
        }
        if (url.scheme != jobRoot.scheme) return false

        val rootPath = jobRoot.encodedPath.trimEnd('/')
        val artifactPath = url.encodedPath
        val expectedPrefix = if (rootPath.isEmpty()) "/" else "$rootPath/"

        val buildMatches = expectedBuildNumber == null ||
            artifactPath.contains(
                "/$expectedBuildNumber/artifact/"
            )

        return artifactPath.startsWith(expectedPrefix) &&
            buildMatches &&
            artifactPath.endsWith(
                "/artifact/target/Nukkit-MOT-SNAPSHOT.jar"
            )
    }

    private fun checkUrlReachable(
        urlString: String,
    ): Boolean {
        if (!isOfficialArtifactUrl(urlString)) return false

        return try {
            val request = Request.Builder()
                .url(urlString)
                .header("Range", "bytes=0-3")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@use false
                }

                val contentType = response
                    .header("Content-Type")
                    ?.lowercase()
                    .orEmpty()

                if (
                    contentType.contains("text/html") ||
                    contentType.contains("text/plain") ||
                    contentType.contains("application/json") ||
                    contentType.contains("application/xml")
                ) {
                    return@use false
                }

                val body = response.body ?: return@use false
                val signature = ByteArray(4)
                val count = body.byteStream().use { input ->
                    input.read(signature)
                }

                count == 4 &&
                    signature[0] == 'P'.code.toByte() &&
                    signature[1] == 'K'.code.toByte() &&
                    (
                        signature[2] == 0x03.toByte() ||
                            signature[2] == 0x05.toByte() ||
                            signature[2] == 0x07.toByte()
                    )
            }
        } catch (_: Exception) {
            false
        }
    }
}
