package com.example.server.updates

import com.example.server.version.VersionSourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class JenkinsReleaseSource(
    private val client: OkHttpClient,
    private val jenkinsJobUrl: String,
    override val engineId: String
) : EngineReleaseSource {

    override val sourceName: String = "Jenkins (${jenkinsJobUrl.trimEnd('/').substringAfterLast("/")})"
    override val sourceKey: String = "jenkins:${jenkinsJobUrl.trimEnd('/')}"

    override suspend fun checkReleases(knownSourceIds: Set<String>): SourceCheckResult {
        val releases = mutableListOf<DetectedEngineRelease>()
        // API URL to get builds with artifacts, limited to 20 recent builds (Part 7)
        val apiUrl = "${jenkinsJobUrl.trimEnd('/')}/api/json?tree=name,displayName,builds[number,result,timestamp,artifacts[relativePath,fileName],displayName]{0,20}"

        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "MineHost-Android")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val retryable = when (response.code) {
                        408, 429, 500, 502, 503, 504 -> true
                        else -> false
                    }
                    val message = if (response.code == 404) {
                        "Jenkins job not found: ${jenkinsJobUrl}"
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
                val json = JSONObject(body)
                val jobName = json.optString("displayName", json.optString("name", getEngineNameForId(engineId)))
                val builds = json.optJSONArray("builds") ?: return SourceCheckResult.Success(emptyList())

                for (i in 0 until builds.length()) {
                    val build = builds.getJSONObject(i)
                    val result = build.optString("result", "UNKNOWN")
                    
                    // Jenkins "result" is "SUCCESS" for completed successful builds
                    if (result != "SUCCESS") continue

                    val buildNumber = build.getInt("number")
                    val sourceId = "jenkins:${engineId}:${buildNumber}"
                    
                    val timestamp = build.optLong("timestamp", 0L)
                    val displayName = build.optString("displayName", "#$buildNumber")

                    // Find JAR artifacts
                    val artifacts = build.optJSONArray("artifacts")
                    if (artifacts != null) {
                        val REJECT_PATTERNS = listOf(
                            "sources", "source", "javadoc", "api", "tests", "test", "original",
                            "slim", "library", "lib", "docs", "example", "installer"
                        )

                        val candidateJars = mutableListOf<JSONObject>()
                        for (j in 0 until artifacts.length()) {
                            val artifact = artifacts.getJSONObject(j)
                            val fileName = artifact.getString("fileName").lowercase()
                            if (fileName.endsWith(".jar")) {
                                val isRejected = REJECT_PATTERNS.any { fileName.contains(it) }
                                if (isRejected) continue
                                
                                candidateJars.add(artifact)
                            }
                        }

                        if (candidateJars.isEmpty()) continue

                        val selectedArtifact = if (candidateJars.size == 1) {
                            candidateJars[0]
                        } else {
                            // Part 9: Ambiguity handling
                            val names = candidateJars.map { it.getString("fileName") }
                            android.util.Log.e("JenkinsReleaseSource", "Ambiguous runnable JARs for build $buildNumber: $names")
                            releases.add(
                                DetectedEngineRelease(
                                    sourceId = sourceId,
                                    engineId = engineId,
                                    engineName = jobName,
                                    releaseName = "Jenkins Build ${build.optString("displayName", "#$buildNumber")}",
                                    releaseTag = buildNumber.toString(),
                                    buildNumber = buildNumber,
                                    artifactName = null,
                                    artifactUrl = null,
                                    bedrockVersions = emptyList(),
                                    publishedAt = if (timestamp > 0) timestamp else null,
                                    prerelease = false,
                                    firstDetectedAt = System.currentTimeMillis(),
                                    lastCheckedAt = System.currentTimeMillis(),
                                    verificationStatus = ReleaseVerificationStatus.FAILED,
                                    verificationMessage = "Ambiguous runnable JARs: ${names.joinToString(", ")}",
                                    compatibilityVerified = false,
                                    verificationEvidence = null,
                                    sourceType = VersionSourceType.JENKINS_BUILD,
                                    sourceKey = sourceKey,
                                    sourceName = sourceName,
                                    sourceProject = jenkinsJobUrl.trimEnd('/').substringAfterLast("/")
                                )
                            )
                            continue
                        }

                        val fileName = selectedArtifact.getString("fileName")
                        val relativePath = selectedArtifact.getString("relativePath")
                        val artifactUrl = "${jenkinsJobUrl.trimEnd('/')}/$buildNumber/artifact/$relativePath"
                        val compatibility = CompatibilityParser.parse(engineId, buildNumber.toString(), build.optString("displayName", ""))
                        
                        releases.add(
                            DetectedEngineRelease(
                                sourceId = sourceId,
                                engineId = engineId,
                                engineName = jobName,
                                releaseName = "Jenkins Build $displayName",
                                releaseTag = buildNumber.toString(),
                                buildNumber = buildNumber,
                                artifactName = fileName,
                                artifactUrl = artifactUrl,
                                bedrockVersions = compatibility.versions,
                                publishedAt = if (timestamp > 0) timestamp else null,
                                prerelease = false,
                                firstDetectedAt = System.currentTimeMillis(),
                                lastCheckedAt = System.currentTimeMillis(),
                                verificationStatus = ReleaseVerificationStatus.DETECTED,
                                verificationMessage = if (compatibility.verified) {
                                    "New build detected. Verified Minecraft compatibility: ${compatibility.versions.joinToString(", ")}"
                                } else {
                                    "New build detected via Jenkins. Minecraft compatibility is not clearly documented."
                                },
                                compatibilityVerified = compatibility.verified,
                                verificationEvidence = compatibility.evidence,
                                sourceType = VersionSourceType.JENKINS_BUILD,
                                sourceKey = sourceKey,
                                sourceName = sourceName,
                                sourceProject = jenkinsJobUrl.trimEnd('/').substringAfterLast("/")
                            )
                        )
                    }
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
