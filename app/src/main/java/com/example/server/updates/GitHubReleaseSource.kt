package com.example.server.updates

import com.example.server.version.VersionSourceType
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GitHubReleaseSource(
    private val client: OkHttpClient,
    private val owner: String,
    private val repo: String,
    override val engineId: String
) : EngineReleaseSource {

    override val sourceName: String = "GitHub ($owner/$repo)"
    override val sourceKey: String = "github:$owner/$repo"

    override suspend fun checkReleases(knownSourceIds: Set<String>): SourceCheckResult {
        val releases = mutableListOf<DetectedEngineRelease>()
        val url = "https://api.github.com/repos/$owner/$repo/releases"
        
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "MineHost-Android")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val retryable = when (response.code) {
                        408, 429, 500, 502, 503, 504 -> true
                        else -> false
                    }
                    val message = if (response.code == 403 && response.header("X-RateLimit-Remaining") == "0") {
                        "GitHub API rate limit exceeded."
                    } else {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    return SourceCheckResult.Failure(
                        ReleaseSourceFailure(engineId, sourceName, retryable, message, response.code)
                    )
                }

                val body = response.body?.string() ?: return SourceCheckResult.Failure(
                    ReleaseSourceFailure(engineId, sourceName, false, "Empty response body", response.code)
                )
                val jsonArray = JSONArray(body)

                for (i in 0 until jsonArray.length()) {
                    val releaseJson = jsonArray.getJSONObject(i)
                    if (releaseJson.getBoolean("draft")) continue

                    val tagName = releaseJson.getString("tag_name")
                    val sourceId = "github:$engineId:$tagName"
                    
                    val releaseName = releaseJson.optString("name", tagName)
                    val prerelease = releaseJson.getBoolean("prerelease")
                    val publishedAtStr = releaseJson.optString("published_at", "")
                    val publishedAt = if (publishedAtStr.isNotEmpty()) {
                        try {
                            java.time.Instant.parse(publishedAtStr).toEpochMilli()
                        } catch (e: Exception) {
                            null
                        }
                    } else null

                    // Selective artifact picking (Part 9)
                    val assets = releaseJson.getJSONArray("assets")
                    val candidateJars = mutableListOf<JSONObject>()
                    
                    val REJECT_PATTERNS = listOf(
                        "sources", "source", "javadoc", "api", "tests", "test", "original",
                        "slim", "library", "lib", "docs", "example", "installer"
                    )

                    for (j in 0 until assets.length()) {
                        val asset = assets.getJSONObject(j)
                        val name = asset.getString("name").lowercase()
                        if (name.endsWith(".jar")) {
                            // Reject known bad patterns
                            val isRejected = REJECT_PATTERNS.any { name.contains(it) }
                            if (isRejected) continue
                            
                            candidateJars.add(asset)
                        }
                    }

                    if (candidateJars.isEmpty()) continue
                    
                    // Requirement: exactly one unambiguous runnable JAR.
                    val selectedAsset = if (candidateJars.size == 1) {
                        candidateJars[0]
                    } else {
                        // Part 9: When multiple plausible runnable candidates remain:
                        // Return an ambiguity error. Do not guess.
                        val names = candidateJars.map { it.getString("name") }
                        Log.e("GitHubReleaseSource", "Ambiguous runnable JARs for $tagName: $names")
                        releases.add(
                            DetectedEngineRelease(
                                sourceId = sourceId,
                                engineId = engineId,
                                engineName = engineId,
                                releaseName = releaseJson.optString("name", tagName),
                                releaseTag = tagName,
                                buildNumber = null,
                                artifactName = null,
                                artifactUrl = null,
                                bedrockVersions = emptyList(),
                                publishedAt = publishedAt,
                                prerelease = prerelease,
                                firstDetectedAt = System.currentTimeMillis(),
                                lastCheckedAt = System.currentTimeMillis(),
                                verificationStatus = ReleaseVerificationStatus.FAILED,
                                verificationMessage = "Ambiguous runnable JARs: ${names.joinToString(", ")}",
                                compatibilityVerified = false,
                                verificationEvidence = null,
                                sourceType = VersionSourceType.GITHUB_RELEASE,
                                sourceKey = sourceKey,
                                sourceName = sourceName,
                                sourceProject = sourceKey
                            )
                        )
                        continue // Skip this release, effectively an error for this release
                    }

                    val compatibility = CompatibilityParser.parse(engineId, tagName, releaseJson.optString("body", ""))
                    val publisherSha256 = selectedAsset.optString("digest")
                        .removePrefix("sha256:")
                        .lowercase()
                        .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                    
                    releases.add(
                        DetectedEngineRelease(
                            sourceId = sourceId,
                            engineId = engineId,
                            engineName = getEngineNameForId(engineId),
                            releaseName = releaseName,
                            releaseTag = tagName,
                            buildNumber = null,
                            artifactName = selectedAsset.getString("name"),
                            artifactUrl = selectedAsset.getString("browser_download_url"),
                            artifactSize = selectedAsset.optLong("size", 0L),
                            bedrockVersions = compatibility.versions,
                            publishedAt = publishedAt,
                            prerelease = prerelease,
                            firstDetectedAt = System.currentTimeMillis(),
                            lastCheckedAt = System.currentTimeMillis(),
                            verificationStatus = ReleaseVerificationStatus.DETECTED,
                            verificationMessage = if (compatibility.verified) {
                                "New build detected. Verified Minecraft compatibility: ${compatibility.versions.joinToString(", ")}"
                            } else {
                                "New build detected. Minecraft compatibility is not clearly documented."
                            },
                            compatibilityVerified = compatibility.verified,
                            verificationEvidence = compatibility.evidence,
                            sourceType = VersionSourceType.GITHUB_RELEASE,
                            sourceKey = sourceKey,
                            sourceName = sourceName,
                            sourceProject = "$owner/$repo",
                            publisherSha256 = publisherSha256
                        )
                    )
                }
                return SourceCheckResult.Success(releases)
            }
        } catch (e: Exception) {
            val retryable = e is IOException
            return SourceCheckResult.Failure(
                ReleaseSourceFailure(engineId, sourceName, retryable, e.message ?: "Unknown error")
            )
        }
    }

    private fun getEngineNameForId(id: String): String {
        return when (id) {
            "bedrock_power_nukkit_x" -> "PowerNukkitX"
            "bedrock_power_nukkit" -> "PowerNukkit"
            "bedrock_nukkit" -> "Nukkit PM1E"
            "bedrock_cloudburst_nukkit" -> "Cloudburst"
            "nukkit-mot" -> "Nukkit-MOT"
            else -> "Minecraft Engine"
        }
    }
}
