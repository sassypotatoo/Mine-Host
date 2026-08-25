package com.example.server

import android.content.Context
import android.util.Log
import com.example.server.version.EngineVersion
import com.example.server.version.validationPolicy
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.VersionSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.example.server.version.ResolvedEngineVersion
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

enum class ArtifactResolutionPolicy {
    PINNED,
    LATEST_SUCCESSFUL
}

sealed interface ServerJarDownloadResult {
    data class Success(val identity: ResolvedEngineVersion?) : ServerJarDownloadResult
    data class Failure(val message: String) : ServerJarDownloadResult
    data object Cancelled : ServerJarDownloadResult
}

data class VerifiedCachedEngineArtifact(
    val file: File,
    val sha256: String,
    val resolvedIdentity: ResolvedEngineVersion?,
)

object Downloader {
    private val client by lazy { OkHttpClient() }
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun cancel(id: String) {
        activeCalls.remove(id)?.cancel()
    }

    fun getCacheDir(context: Context, engineId: String, versionId: String): File {
        val safeEngineId = engineId.replace(Regex("[:/\\\\]"), "_")
        val safeVersionId = versionId.replace(Regex("[:/\\\\]"), "_")
        return File(context.filesDir, "engine-cache/$safeEngineId/$safeVersionId")
    }

    fun getCacheFile(
        context: Context,
        engineId: String,
        versionId: String,
        jarName: String
    ): File {
        return File(getCacheDir(context, engineId, versionId), jarName)
    }

    private fun getManualVerificationFile(context: Context, version: EngineVersion): File {
        return File(
            getCacheDir(context, version.engineId, version.id),
            "${version.jarFileName}.manual-verification.json"
        )
    }


    private fun getResolvedCacheMetadataFile(
        context: Context,
        version: EngineVersion,
    ): File = File(
        getCacheDir(context, version.engineId, version.id),
        "${version.jarFileName}.resolved-identity.json",
    )

    private fun normalizedSha256(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

    internal fun supportsOfficialNukkitMotFallback(
        version: EngineVersion,
    ): Boolean {
        if (version.engineId != "nukkit-mot") return false
        if (version.sourceType != VersionSourceType.JENKINS_BUILD) return false

        val url = runCatching { version.downloadUrl.toHttpUrl() }
            .getOrNull() ?: return false

        return url.scheme == "https" &&
            url.host == "motci.cn" &&
            url.encodedPath.contains("/job/Nukkit-MOT/job/master/") &&
            url.encodedPath.endsWith(
                "/artifact/target/Nukkit-MOT-SNAPSHOT.jar"
            )
    }

    internal fun supportsOfficialPaperFallback(
        version: EngineVersion,
    ): Boolean {
        return version.engineId == "java_paper" || version.sourceType == VersionSourceType.PAPER_API
    }

    private fun invalidateCachedArtifact(
        context: Context,
        version: EngineVersion,
        cacheFile: File,
    ) {
        cacheFile.delete()
        File(cacheFile.parentFile, "${cacheFile.name}.part").delete()
        getManualVerificationFile(context, version).delete()
        getResolvedCacheMetadataFile(context, version).delete()
    }

    private fun copyVerifiedArtifact(
        source: File,
        destination: File,
        expectedSha256: String,
    ): Boolean {
        val normalizedExpected = normalizedSha256(expectedSha256)
            ?: return false
        if (!source.isFile) return false
        if (!sha256(source).equals(normalizedExpected, ignoreCase = true)) {
            return false
        }

        val sourcePath = runCatching { source.canonicalPath }.getOrNull()
        val destinationPath = runCatching { destination.canonicalPath }.getOrNull()
        if (sourcePath != null && sourcePath == destinationPath) {
            return true
        }

        val parent = destination.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val staged = File(parent, ".${destination.name}.minehost-copy.part")
        return runCatching {
            staged.delete()
            Files.copy(
                source.toPath(),
                staged.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            require(
                sha256(staged).equals(
                    normalizedExpected,
                    ignoreCase = true,
                )
            ) {
                "Artifact checksum changed during destination staging"
            }

            try {
                Files.move(
                    staged.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    staged.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            require(
                sha256(destination).equals(
                    normalizedExpected,
                    ignoreCase = true,
                )
            ) {
                "Committed destination checksum is incorrect"
            }
            true
        }.getOrElse {
            staged.delete()
            false
        }
    }

    private fun commitCacheArtifact(
        source: File,
        cacheFile: File,
        expectedSha256: String,
    ): Boolean {
        val normalizedExpected = normalizedSha256(expectedSha256)
            ?: return false
        if (!source.isFile) return false

        val parent = cacheFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val part = File(parent, "${cacheFile.name}.part")
        return runCatching {
            part.delete()
            Files.copy(
                source.toPath(),
                part.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            require(
                sha256(part).equals(
                    normalizedExpected,
                    ignoreCase = true,
                )
            ) {
                "Cached engine SHA-256 changed during staging"
            }

            try {
                Files.move(
                    part.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    part.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            require(
                sha256(cacheFile).equals(
                    normalizedExpected,
                    ignoreCase = true,
                )
            ) {
                "Committed engine cache SHA-256 is incorrect"
            }
            true
        }.getOrElse {
            part.delete()
            false
        }
    }

    internal fun writeResolvedCacheMetadata(
        context: Context,
        version: EngineVersion,
        cacheFile: File,
        identity: ResolvedEngineVersion,
        actualSha256: String,
    ): Boolean {
        val normalizedSha = normalizedSha256(actualSha256) ?: return false
        if (!cacheFile.isFile || cacheFile.length() != identity.artifactSize) {
            return false
        }
        if (!isTrustedResolvedNukkitIdentity(version, identity) && !isTrustedResolvedPaperIdentity(version, identity)) {
            return false
        }
        if (!sha256(cacheFile).equals(normalizedSha, ignoreCase = true)) {
            return false
        }

        val target = getResolvedCacheMetadataFile(context, version)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            target.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("schemaVersion", 1)
                put("engineId", version.engineId)
                put("catalogBaseId", version.id)
                put("jarFileName", version.jarFileName)
                put("jarSha256", normalizedSha)
                put("effectiveVersionId", identity.effectiveVersionId)
                put("resolvedBuildNumber", identity.resolvedBuildNumber)
                put("resolvedArtifactUrl", identity.resolvedArtifactUrl)
                put("artifactSize", identity.artifactSize)
                put("sourceRepository", identity.sourceRepository)
                put("sourceRevision", identity.sourceRevision)
                put("resolvedAt", identity.resolvedAt)
                put("generatorId", identity.generatorId)
                put("generatorRevision", identity.generatorRevision)
            }
            temporary.writeText(json.toString(2))
            if (!temporary.renameTo(target)) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        }.getOrElse {
            temporary.delete()
            false
        }
    }

    private fun readResolvedCacheIdentity(
        context: Context,
        version: EngineVersion,
        cacheFile: File,
        selectedMinecraftVersion: String? = null,
    ): ResolvedEngineVersion? {
        if (!supportsOfficialNukkitMotFallback(version) && !supportsOfficialPaperFallback(version)) return null

        val metadataFile = getResolvedCacheMetadataFile(context, version)
        if (!cacheFile.isFile || !metadataFile.isFile) return null

        return runCatching {
            val json = JSONObject(metadataFile.readText())
            require(json.optInt("schemaVersion", -1) == 1)
            require(json.optString("engineId") == version.engineId)
            require(json.optString("catalogBaseId") == version.id)
            require(json.optString("jarFileName") == version.jarFileName)

            val storedSha = normalizedSha256(
                json.optString("jarSha256")
            ) ?: error("Resolved cache SHA-256 is invalid")
            require(sha256(cacheFile).equals(storedSha, ignoreCase = true))

            val identity = ResolvedEngineVersion(
                catalogBaseId = json.getString("catalogBaseId"),
                effectiveVersionId = json.getString("effectiveVersionId"),
                engineId = json.getString("engineId"),
                resolvedBuildNumber = json.getInt("resolvedBuildNumber"),
                resolvedArtifactUrl = json.getString("resolvedArtifactUrl"),
                artifactSize = json.getLong("artifactSize"),
                sourceRepository = json.getString("sourceRepository"),
                sourceRevision = json.optString("sourceRevision")
                    .takeIf(String::isNotBlank),
                resolvedAt = json.getLong("resolvedAt"),
                generatorId = json.getString("generatorId"),
                generatorRevision = json.getString("generatorRevision"),
            )

            require(cacheFile.length() == identity.artifactSize)
            if (supportsOfficialPaperFallback(version)) {
                val selected = selectedMinecraftVersion?.trim()?.takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
                    ?: error("Paper cache verification requires exact selected Minecraft version")
                require(identity.effectiveVersionId.startsWith("java_paper:${selected}:"))
            }
            require(isTrustedResolvedNukkitIdentity(version, identity) || isTrustedResolvedPaperIdentity(version, identity))
            require(
                InstalledEngineVersionRepository.validateJar(
                    cacheFile,
                    version.launchMode,
                    version.mainClass,
                ).valid
            )
            identity
        }.getOrNull()
    }

    internal fun isTrustedResolvedPaperIdentity(
        version: EngineVersion,
        identity: ResolvedEngineVersion,
    ): Boolean {
        if (!supportsOfficialPaperFallback(version)) return false.also { println("DEBUG: paper fallback not supported") }
        if (identity.engineId != "java_paper") return false.also { println("DEBUG: identity.engineId mismatch: ${identity.engineId}") }
        if (identity.catalogBaseId != version.id) return false.also { println("DEBUG: catalogBaseId mismatch: ${identity.catalogBaseId} vs ${version.id}") }
        if (identity.resolvedBuildNumber <= 0) return false.also { println("DEBUG: invalid build number: ${identity.resolvedBuildNumber}") }
        if (identity.artifactSize <= 1024L) return false.also { println("DEBUG: artifact too small: ${identity.artifactSize}") }
        if (identity.resolvedAt <= 0L) return false.also { println("DEBUG: invalid resolvedAt") }
        if (!identity.effectiveVersionId.startsWith("java_paper:")) return false.also { println("DEBUG: effectiveVersionId prefix mismatch: ${identity.effectiveVersionId}") }
        if (!identity.generatorId.startsWith("paper/")) return false.also { println("DEBUG: generatorId prefix mismatch: ${identity.generatorId}") }

        val url = runCatching { identity.resolvedArtifactUrl.toHttpUrl() }.getOrNull() ?: return false.also { println("DEBUG: invalid URL: ${identity.resolvedArtifactUrl}") }
        return (url.scheme == "https" && com.example.javaedition.PaperUserAgentProvider.isTrustedPaperHost(url.host)).also {
            if (!it) println("DEBUG: untrusted host: ${url.host}")
        }
    }

    private fun isTrustedResolvedNukkitIdentity(
        version: EngineVersion,
        identity: ResolvedEngineVersion,
    ): Boolean {
        if (!supportsOfficialNukkitMotFallback(version)) return false
        if (identity.engineId != version.engineId) return false
        if (identity.catalogBaseId != version.id) return false
        if (identity.resolvedBuildNumber <= 0) return false
        if (identity.effectiveVersionId !=
            "nukkit-mot:${identity.resolvedBuildNumber}"
        ) return false
        if (identity.generatorId != "nukkit-mot/normal") return false
        if (identity.generatorRevision !=
            "jenkins-build:${identity.resolvedBuildNumber}"
        ) return false
        if (identity.artifactSize <= 1024L) return false
        if (identity.resolvedAt <= 0L) return false

        val normalizedRepository = identity.sourceRepository
            .trim()
            .removeSuffix(".git")
            .removeSuffix("/")
        if (
            normalizedRepository !=
            "https://github.com/MemoriesOfTime/Nukkit-MOT"
        ) return false

        val url = runCatching {
            identity.resolvedArtifactUrl.toHttpUrl()
        }.getOrNull() ?: return false

        return url.scheme == "https" &&
            url.host == "motci.cn" &&
            url.encodedPath.contains("/job/Nukkit-MOT/job/master/") &&
            url.encodedPath.contains(
                "/${identity.resolvedBuildNumber}/artifact/"
            ) &&
            url.encodedPath.endsWith(
                "/artifact/target/Nukkit-MOT-SNAPSHOT.jar"
            )
    }

    private fun publisherChecksum(version: EngineVersion): String? = version.sha256
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

    private fun manuallyVerifiedChecksum(context: Context, version: EngineVersion): String? {
        val verificationFile = getManualVerificationFile(context, version)
        val cacheFile = getCacheFile(context, version.engineId, version.id, version.jarFileName)
        if (!verificationFile.isFile || !cacheFile.isFile) return null
        return runCatching {
            val json = JSONObject(verificationFile.readText())
            require(json.optString("engineId") == version.engineId)
            require(json.optString("versionId") == version.id)
            require(json.optString("jarFileName") == version.jarFileName)
            require(json.optString("verificationMode") == "MANUAL_USER_PROVIDED_SHA256")
            val checksum = json.optString("sha256").trim().lowercase()
            require(checksum.matches(Regex("^[0-9a-f]{64}$")))
            require(sha256(cacheFile).equals(checksum, ignoreCase = true))
            require(
                InstalledEngineVersionRepository.validateJar(
                    cacheFile,
                    version.launchMode,
                    version.mainClass
                ).valid
            )
            checksum
        }.getOrNull()
    }

    fun getTrustedChecksumForInstall(context: Context, version: EngineVersion): String? {
        return publisherChecksum(version) ?: manuallyVerifiedChecksum(context, version)
    }

    fun verifyCachedEngineArtifact(
        context: Context,
        version: EngineVersion,
        invalidateInvalid: Boolean = true,
        selectedMinecraftVersion: String? = null,
    ): VerifiedCachedEngineArtifact? {
        val cacheFile = getCacheFile(
            context,
            version.engineId,
            version.id,
            version.jarFileName,
        )
        if (!cacheFile.isFile) return null

        val actualSha = normalizedSha256(sha256(cacheFile))
        val trustedSha = getTrustedChecksumForInstall(context, version)
        val validation = InstalledEngineVersionRepository.validateJar(
            jarFile = cacheFile,
            launchMode = version.launchMode,
            mainClass = version.mainClass,
        )

        val pinnedOrManualValid =
            actualSha != null &&
                trustedSha != null &&
                actualSha.equals(trustedSha, ignoreCase = true) &&
                validation.valid

        if (pinnedOrManualValid) {
            return VerifiedCachedEngineArtifact(
                file = cacheFile,
                sha256 = requireNotNull(actualSha),
                resolvedIdentity = null,
            )
        }

        val resolvedIdentity = if (
            actualSha != null &&
            validation.valid &&
            (supportsOfficialNukkitMotFallback(version) || supportsOfficialPaperFallback(version))
        ) {
            readResolvedCacheIdentity(context, version, cacheFile, selectedMinecraftVersion)
        } else {
            null
        }

        if (resolvedIdentity != null) {
            return VerifiedCachedEngineArtifact(
                file = cacheFile,
                sha256 = requireNotNull(actualSha),
                resolvedIdentity = resolvedIdentity,
            )
        }

        if (invalidateInvalid) {
            invalidateCachedArtifact(context, version, cacheFile)
        }
        return null
    }

    suspend fun downloadServerJar(
        context: Context,
        version: EngineVersion,
        destination: File,
        operationId: String? = null,
        minecraftVersion: String? = null,
        onProgress: (String) -> Unit,
    ): ServerJarDownloadResult {
        val isPaper = supportsOfficialPaperFallback(version)

        if (isPaper) {
            val mcVer = minecraftVersion?.trim()?.takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
                ?: throw IllegalStateException("Paper requires an exact supported Minecraft version.")

            destination.parentFile?.mkdirs()
            val cacheFile = getCacheFile(
                context,
                version.engineId,
                version.id,
                version.jarFileName,
            )

            if (cacheFile.exists()) {
                val verifiedCache = verifyCachedEngineArtifact(
                    context = context,
                    version = version,
                    invalidateInvalid = true,
                    selectedMinecraftVersion = mcVer,
                )

                if (verifiedCache != null) {
                    val cachedIdentity = verifiedCache.resolvedIdentity
                    onProgress(
                        if (cachedIdentity != null) {
                            "[Engine] Reusing verified resolved Paper cache build #${cachedIdentity.resolvedBuildNumber}."
                        } else {
                            "[Engine] Reusing checksum-verified cached Paper artifact: ${cacheFile.name}"
                        }
                    )
                    if (
                        !copyVerifiedArtifact(
                            source = cacheFile,
                            destination = destination,
                            expectedSha256 = verifiedCache.sha256,
                        )
                    ) {
                        return ServerJarDownloadResult.Failure(
                            "Verified cached Paper artifact could not be committed to the server directory"
                        )
                    }
                    return ServerJarDownloadResult.Success(cachedIdentity)
                }

                onProgress("[Engine] Cached Paper artifact or its trust metadata was invalid; deleting cache entry.")
            }

            onProgress("[Engine] Resolving official Paper build via PaperMC API for Minecraft $mcVer...")
            val paperBuildRes = com.example.javaedition.PaperResolver.resolveLatestStableBuild(mcVer)
            if (paperBuildRes.isFailure) {
                val err = paperBuildRes.exceptionOrNull()?.message ?: "Paper API resolution failed"
                onProgress("ERROR: Paper resolution failed: $err")
                return ServerJarDownloadResult.Failure("Paper API build resolution failed: $err")
            }

            val buildInfo = paperBuildRes.getOrThrow()
            onProgress("[Engine] Resolved Paper build #${buildInfo.buildNumber} (${buildInfo.downloadName}, sha256=${buildInfo.sha256.take(8)}...)")

            val paperHeaders = mapOf(
                "User-Agent" to com.example.javaedition.PaperUserAgentProvider.value(),
                "Accept" to "application/java-archive, application/octet-stream, */*",
            )

            when (
                downloadFile(
                    url = buildInfo.downloadUrl,
                    destination = destination,
                    onProgress = onProgress,
                    name = destination.name,
                    isJar = true,
                    operationId = operationId,
                    expectedJarNameInsideZip = version.jarFileName,
                    requestHeaders = paperHeaders,
                )
            ) {
                is ArtifactDownloadResult.Cancelled ->
                    return ServerJarDownloadResult.Cancelled

                is ArtifactDownloadResult.Success -> {
                    if (buildInfo.fileSize > 0L && destination.length() != buildInfo.fileSize) {
                        onProgress("ERROR: Paper artifact size mismatch: expected ${buildInfo.fileSize}, got ${destination.length()}")
                        destination.delete()
                        return ServerJarDownloadResult.Failure("Paper artifact size mismatch: expected ${buildInfo.fileSize}, got ${destination.length()}")
                    }

                    val actualSha256 = normalizedSha256(sha256(destination))
                    if (actualSha256 == null) {
                        onProgress("ERROR: Downloaded Paper JAR could not be hashed.")
                        destination.delete()
                        return ServerJarDownloadResult.Failure("Downloaded Paper JAR could not be hashed")
                    }

                    if (!actualSha256.equals(buildInfo.sha256, ignoreCase = true)) {
                        onProgress("ERROR: SHA-256 mismatch for Paper build #${buildInfo.buildNumber}. Expected ${buildInfo.sha256}, got $actualSha256.")
                        destination.delete()
                        return ServerJarDownloadResult.Failure("SHA-256 checksum mismatch for Paper download")
                    }

                    val validation = InstalledEngineVersionRepository.validateJar(
                        jarFile = destination,
                        launchMode = version.launchMode,
                        mainClass = version.mainClass,
                    )

                    if (!validation.valid) {
                        onProgress("ERROR: Structural JAR validation failed for Paper: ${validation.error}")
                        destination.delete()
                        return ServerJarDownloadResult.Failure("Structural JAR validation failed: ${validation.error}")
                    }

                    val resolvedIdentity = ResolvedEngineVersion(
                        catalogBaseId = version.id,
                        effectiveVersionId = "java_paper:$mcVer:${buildInfo.buildNumber}",
                        engineId = "java_paper",
                        resolvedBuildNumber = buildInfo.buildNumber,
                        resolvedArtifactUrl = buildInfo.downloadUrl,
                        artifactSize = destination.length(),
                        sourceRepository = "https://fill.papermc.io",
                        sourceRevision = "build-${buildInfo.buildNumber}",
                        resolvedAt = System.currentTimeMillis(),
                        generatorId = "paper/standard",
                        generatorRevision = "build:${buildInfo.buildNumber}"
                    )

                    getResolvedCacheMetadataFile(context, version).delete()
                    if (!commitCacheArtifact(destination, cacheFile, actualSha256)) {
                        onProgress("ERROR: Verified Paper JAR could not be committed to cache.")
                        invalidateCachedArtifact(context, version, cacheFile)
                        destination.delete()
                        return ServerJarDownloadResult.Failure("Verified Paper JAR could not be committed to cache")
                    }

                    writeResolvedCacheMetadata(context, version, cacheFile, resolvedIdentity, actualSha256)
                    onProgress("[Engine] Paper build #${buildInfo.buildNumber} SHA-256 verified and installed successfully.")
                    return ServerJarDownloadResult.Success(resolvedIdentity)
                }

                is ArtifactDownloadResult.HttpFailure,
                is ArtifactDownloadResult.NetworkFailure,
                is ArtifactDownloadResult.ValidationFailure -> {
                    onProgress("ERROR: Failed to download Paper artifact from ${buildInfo.downloadUrl}")
                    return ServerJarDownloadResult.Failure("Failed to download Paper artifact from ${buildInfo.downloadUrl}")
                }
            }
        }

        val trustedSha256 = getTrustedChecksumForInstall(context, version)
        val supportsResolvedFallback =
            supportsOfficialNukkitMotFallback(version)

        if (trustedSha256 == null && !supportsResolvedFallback) {
            onProgress(
                "ERROR: Installation blocked because ${version.displayName} " +
                    "has no trusted publisher SHA-256 or supported " +
                    "official resolved-artifact policy."
            )
            return ServerJarDownloadResult.Failure(
                "Trusted SHA-256 missing for engine artifact"
            )
        }

        destination.parentFile?.mkdirs()
        val cacheFile = getCacheFile(
            context,
            version.engineId,
            version.id,
            version.jarFileName,
        )

        if (cacheFile.exists()) {
            val verifiedCache = verifyCachedEngineArtifact(
                context = context,
                version = version,
                invalidateInvalid = true,
            )

            if (verifiedCache != null) {
                val cachedIdentity = verifiedCache.resolvedIdentity
                onProgress(
                    if (cachedIdentity != null) {
                        "[Engine] Reusing verified resolved cache build " +
                            "#${cachedIdentity.resolvedBuildNumber}."
                    } else {
                        "[Engine] Reusing checksum-verified cached artifact: " +
                            cacheFile.name
                    }
                )
                if (
                    !copyVerifiedArtifact(
                        source = cacheFile,
                        destination = destination,
                        expectedSha256 = verifiedCache.sha256,
                    )
                ) {
                    return ServerJarDownloadResult.Failure(
                        "Verified cached artifact could not be committed " +
                            "to the server directory"
                    )
                }
                return ServerJarDownloadResult.Success(cachedIdentity)
            }

            onProgress(
                "[Engine] Cached artifact or its trust metadata was invalid; " +
                    "the complete cache entry was deleted."
            )
        }

        val candidateUrls = when (version.sourceType) {
            VersionSourceType.JENKINS_BUILD -> {
                onProgress("Connecting to Jenkins CI...")
                resolveJenkinsBuildUrls(version, onProgress)
            }

            VersionSourceType.MAVEN_ARTIFACT -> {
                onProgress("Resolving Maven snapshot artifact...")
                resolveMavenUrls(version, onProgress)
            }

            else -> listOf(version.downloadUrl)
        }

        for (candidateUrl in candidateUrls.distinct()) {
            if (candidateUrl.isBlank() || candidateUrl == "PLACEHOLDER") {
                continue
            }

            if (version.sourceType == VersionSourceType.JENKINS_BUILD) {
                onProgress("Attempting Jenkins artifact: $candidateUrl")
            }

            when (
                downloadFile(
                    url = candidateUrl,
                    destination = destination,
                    onProgress = onProgress,
                    name = destination.name,
                    isJar = true,
                    operationId = operationId,
                    expectedJarNameInsideZip = version.jarFileName,
                )
            ) {
                is ArtifactDownloadResult.Cancelled ->
                    return ServerJarDownloadResult.Cancelled

                is ArtifactDownloadResult.Success -> {
                    val actualSha256 = normalizedSha256(
                        sha256(destination)
                    )
                    if (actualSha256 == null) {
                        onProgress(
                            "ERROR: Downloaded engine JAR could not be hashed."
                        )
                        destination.delete()
                        continue
                    }

                    if (
                        trustedSha256 != null &&
                        !actualSha256.equals(trustedSha256, ignoreCase = true)
                    ) {
                        onProgress(
                            "ERROR: SHA-256 mismatch for ${version.displayName}. " +
                                "Expected $trustedSha256, got $actualSha256."
                        )
                        destination.delete()
                        continue
                    }

                    val validation =
                        InstalledEngineVersionRepository.validateJar(
                            jarFile = destination,
                            launchMode = version.launchMode,
                            mainClass = version.mainClass,
                        )

                    if (!validation.valid) {
                        onProgress(
                            "ERROR: Structural JAR validation failed: " +
                                validation.error
                        )
                        destination.delete()
                        continue
                    }

                    getResolvedCacheMetadataFile(context, version).delete()
                    if (
                        !commitCacheArtifact(
                            source = destination,
                            cacheFile = cacheFile,
                            expectedSha256 = actualSha256,
                        )
                    ) {
                        onProgress(
                            "ERROR: Verified engine JAR could not be " +
                                "committed atomically to the shared cache."
                        )
                        invalidateCachedArtifact(
                            context,
                            version,
                            cacheFile,
                        )
                        destination.delete()
                        continue
                    }
                    onProgress("[Engine] Publisher SHA-256 verified.")
                    return ServerJarDownloadResult.Success(null)
                }

                else -> Unit
            }
        }

        if (supportsResolvedFallback) {
            onProgress(
                "[Engine] Pinned Nukkit-MOT artifact failed. " +
                    "Resolving a successful official master build..."
            )
            val resolver = NukkitMotResolver(
                client = client,
                jobRoot = NukkitMotResolver.DEFAULT_JOB_ROOT.toHttpUrl(),
                clock = java.time.Clock.systemUTC(),
            )
            val candidates = resolver.resolveCandidates(
                context = context,
                version = version,
                failedBuildNumber = version.buildNumber,
                onProgress = onProgress,
            )

            for (resolved in candidates) {
                onProgress(
                    "Downloading resolved Nukkit-MOT build " +
                        "#${resolved.buildNumber} (${resolved.artifactUrl})..."
                )

                when (
                    downloadFile(
                        url = resolved.artifactUrl,
                        destination = destination,
                        onProgress = onProgress,
                        name = destination.name,
                        isJar = true,
                        operationId = operationId,
                        expectedJarNameInsideZip = version.jarFileName,
                    )
                ) {
                    is ArtifactDownloadResult.Cancelled ->
                        return ServerJarDownloadResult.Cancelled

                    is ArtifactDownloadResult.Success -> {
                        val actualSha256 = normalizedSha256(
                            sha256(destination)
                        )
                        if (actualSha256 == null) {
                            onProgress(
                                "[Engine] ERROR: Resolved Nukkit-MOT JAR " +
                                    "could not be hashed."
                            )
                            destination.delete()
                            continue
                        }

                        if (
                            resolved.expectedSha256 != null &&
                            !resolved.expectedSha256.equals(
                                actualSha256,
                                ignoreCase = true,
                            )
                        ) {
                            onProgress(
                                "[Engine] ERROR: Last-known-good SHA-256 " +
                                    "changed for build #${resolved.buildNumber}."
                            )
                            destination.delete()
                            NukkitMotResolver
                                .getLastKnownGoodFile(context)
                                .delete()
                            continue
                        }

                        val validation =
                            InstalledEngineVersionRepository.validateJar(
                                jarFile = destination,
                                launchMode = version.launchMode,
                                mainClass = version.mainClass,
                            )
                        if (!validation.valid) {
                            onProgress(
                                "[Engine] ERROR: Resolved Nukkit-MOT JAR " +
                                    "failed validation: ${validation.error}"
                            )
                            destination.delete()
                            continue
                        }

                        val identity = ResolvedEngineVersion(
                            catalogBaseId = version.id,
                            effectiveVersionId =
                                "nukkit-mot:${resolved.buildNumber}",
                            engineId = version.engineId,
                            resolvedBuildNumber = resolved.buildNumber,
                            resolvedArtifactUrl = resolved.artifactUrl,
                            artifactSize = destination.length(),
                            sourceRepository = resolved.sourceRepository
                                ?: NukkitMotResolver.OFFICIAL_REPO_URL,
                            sourceRevision = resolved.sourceRevision,
                            resolvedAt = resolved.resolvedAt,
                            generatorId = resolved.generatorId,
                            generatorRevision = resolved.generatorRevision,
                        )

                        if (!isTrustedResolvedNukkitIdentity(version, identity)) {
                            onProgress(
                                "[Engine] ERROR: Resolved Nukkit-MOT identity " +
                                    "failed strict validation."
                            )
                            destination.delete()
                            continue
                        }

                        if (
                            !commitCacheArtifact(
                                source = destination,
                                cacheFile = cacheFile,
                                expectedSha256 = actualSha256,
                            )
                        ) {
                            onProgress(
                                "[Engine] ERROR: Resolved artifact could not " +
                                    "be committed atomically to the shared cache."
                            )
                            invalidateCachedArtifact(
                                context,
                                version,
                                cacheFile,
                            )
                            destination.delete()
                            continue
                        }

                        if (
                            !writeResolvedCacheMetadata(
                                context,
                                version,
                                cacheFile,
                                identity,
                                actualSha256,
                            )
                        ) {
                            onProgress(
                                "[Engine] WARNING: Resolved artifact cache " +
                                    "metadata could not be committed; " +
                                    "the current installation remains valid."
                            )
                            invalidateCachedArtifact(
                                context,
                                version,
                                cacheFile,
                            )
                        }

                        runCatching {
                            resolver.saveLastKnownGood(
                                context = context,
                                version = version,
                                resolved = resolved,
                                actualFileSize = destination.length(),
                                actualSha256 = actualSha256,
                                mainClass = version.mainClass,
                            )
                        }.onFailure { error ->
                            onProgress(
                                "[Engine] WARNING: Last-known-good metadata " +
                                    "could not be saved: ${error.message}"
                            )
                        }

                        return ServerJarDownloadResult.Success(identity)
                    }

                    else -> Unit
                }
            }
        }

        destination.delete()
        onProgress("ERROR: All server artifact candidates failed validation.")
        return ServerJarDownloadResult.Failure(
            "Engine download or validation failed"
        )
    }

    suspend fun validateCloudburstLink(url: String): Pair<Boolean, String?> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).head().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext false to "HTTP ${response.code}"
                    }

                    val contentType = response.header("Content-Type")?.lowercase()
                    if (contentType != null &&
                        (contentType.contains("text/html") ||
                            contentType.contains("text/plain"))
                    ) {
                        return@withContext false to
                            "Server returned HTML/Text instead of a binary JAR."
                    }

                    val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    if (length in 1 until 1024L * 1024L) {
                        return@withContext false to
                            "Artifact is too small (${length / 1024} KB)."
                    }
                    true to null
                }
            } catch (e: Exception) {
                false to e.message
            }
        }
    }

    private suspend fun resolveJenkinsBuildUrls(
        version: EngineVersion,
        onProgress: (String) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val fallback = version.downloadUrl
        val policy = if (!version.buildNumber.isNullOrBlank() || !version.dynamic)
            ArtifactResolutionPolicy.PINNED else ArtifactResolutionPolicy.LATEST_SUCCESSFUL
        
        try {
            val jobRoot = deriveJenkinsJobRoot(version.downloadUrl)
                ?: return@withContext listOf(fallback)
            
            val candidates = mutableListOf<String>()
            
            if (policy == ArtifactResolutionPolicy.PINNED) {
                onProgress("[Engine] Requested ${version.engineId} Jenkins build: ${version.buildNumber ?: "N/A"}")
                candidates.add(fallback)
            }
            
            if (policy == ArtifactResolutionPolicy.PINNED) {
                return@withContext candidates.ifEmpty { listOf(fallback) }
            }

            val apiUrl = "$jobRoot/api/json?tree=builds[number,result,artifacts[relativePath]]"

            onProgress("Fetching build list from Jenkins: $jobRoot")
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onProgress("Jenkins API warning: HTTP ${response.code}. Jenkins may be temporarily unavailable.")
                    return@withContext candidates.ifEmpty { listOf(fallback) }
                }

                val body = response.body?.string() ?: return@withContext candidates.ifEmpty { listOf(fallback) }
                val builds = JSONObject(body).optJSONArray("builds") ?: return@withContext candidates.ifEmpty { listOf(fallback) }

                var firstFound = false
                for (index in 0 until builds.length()) {
                    if (candidates.size >= 5) break
                    val build = builds.optJSONObject(index) ?: continue
                    if (!build.optString("result").equals("SUCCESS", ignoreCase = true)) {
                        continue
                    }
                    val number = build.optInt("number", -1)
                    if (number < 0) continue
                    
                    if (policy == ArtifactResolutionPolicy.PINNED && !firstFound && number.toString() != version.buildNumber) {
                        onProgress("[Engine] Requested build is unavailable or pending. Resolved fallback Jenkins build: $number")
                        firstFound = true
                    } else if (!firstFound) {
                        onProgress("[Engine] Resolved Jenkins build: $number")
                        firstFound = true
                    }

                    val artifacts = build.optJSONArray("artifacts") ?: continue
                    for (artifactIndex in 0 until artifacts.length()) {
                        val path = artifacts.optJSONObject(artifactIndex)?.optString("relativePath").orEmpty()
                        if (path.endsWith(".jar", ignoreCase = true)) {
                            val url = "$jobRoot/$number/artifact/$path"
                            if (!candidates.contains(url)) {
                                candidates.add(url)
                            }
                        }
                    }
                    
                    if (policy == ArtifactResolutionPolicy.LATEST_SUCCESSFUL && candidates.isNotEmpty()) break
                }
            }
            candidates.ifEmpty { listOf(fallback) }
        } catch (e: Exception) {
            onProgress("Error resolving Jenkins builds: ${e.message}")
            listOf(fallback)
        }
    }

    private suspend fun resolveMavenUrls(
        version: EngineVersion,
        onProgress: (String) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        if (!version.dynamic) {
            onProgress("Using immutable Maven artifact for ${version.displayName}.")
            return@withContext listOf(version.downloadUrl)
        }
        val metadataUrl = version.downloadUrl.substringBeforeLast("/") + "/maven-metadata.xml"
        onProgress("Fetching Maven metadata: $metadataUrl")
        
        try {
            val request = Request.Builder().url(metadataUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onProgress("Maven metadata warning: HTTP ${response.code}")
                    return@withContext listOf(version.downloadUrl)
                }
                
                val body = response.body?.string() ?: return@withContext listOf(version.downloadUrl)
                
                val snapshot = body.substringAfter("<snapshot>", "").substringBefore("</snapshot>", "")
                if (snapshot.isEmpty()) return@withContext listOf(version.downloadUrl)
                
                val timestamp = snapshot.substringAfter("<timestamp>", "").substringBefore("</timestamp>", "")
                val buildNumber = snapshot.substringAfter("<buildNumber>", "").substringBefore("</buildNumber>", "")
                
                if (timestamp.isEmpty() || buildNumber.isEmpty()) return@withContext listOf(version.downloadUrl)
                
                val base = version.jarFileName.substringBefore("-SNAPSHOT")
                val timestampedJarName = "$base-$timestamp-$buildNumber.jar"
                val finalUrl = version.downloadUrl.substringBeforeLast("/") + "/$timestampedJarName"
                
                onProgress("Resolved Maven snapshot: $timestampedJarName")
                listOf(finalUrl, version.downloadUrl)
            }
        } catch (e: Exception) {
            onProgress("Error resolving Maven metadata: ${e.message}")
            listOf(version.downloadUrl)
        }
    }

    private fun deriveJenkinsJobRoot(url: String): String? {
        var base = url.substringBefore("/api/json")
            .substringBefore("/lastSuccessfulBuild")
            .substringBefore("/lastBuild")
            .removeSuffix("/")

        if (base.contains("/artifact/")) {
            base = base.substringBefore("/artifact/").removeSuffix("/")
            val buildSegment = base.substringAfterLast('/')
            if (buildSegment.toIntOrNull() != null) {
                base = base.substringBeforeLast('/').removeSuffix("/")
            }
        }

        return base.takeIf { it.startsWith("https://") && "/job/" in it }
    }

    suspend fun downloadFile(
        url: String,
        destination: File,
        operationId: String? = null,
        onProgress: (String) -> Unit,
        name: String,
        isJar: Boolean = false,
        expectedJarNameInsideZip: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
    ): ArtifactDownloadResult = withContext(Dispatchers.IO) {
        if (url.startsWith("file://") || url.startsWith("file:")) {
            val filePath = if (url.startsWith("file://")) url.removePrefix("file://") else url.removePrefix("file:")
            val file = File(filePath)
            if (!file.isFile || file.length() == 0L) {
                val msg = "Download error: Local file not found or empty: $filePath"
                onProgress(msg)
                return@withContext ArtifactDownloadResult.ValidationFailure(url, msg)
            }
            if (isJar) {
                onProgress("Validating JAR integrity...")
                if (!validateGenericJar(file)) {
                    val msg = "Downloaded file is not a valid JAR"
                    onProgress(msg)
                    return@withContext ArtifactDownloadResult.ValidationFailure(url, msg)
                }
            }
            val sourceSha = sha256(file)
                ?: return@withContext ArtifactDownloadResult.ValidationFailure(
                    url,
                    "Local artifact could not be hashed",
                )
            if (
                !copyVerifiedArtifact(
                    source = file,
                    destination = destination,
                    expectedSha256 = sourceSha,
                )
            ) {
                val msg = "Local artifact could not be committed atomically"
                onProgress("Download error: $msg")
                return@withContext ArtifactDownloadResult.ValidationFailure(
                    url,
                    msg,
                )
            }
            onProgress("Download $name complete.")
            return@withContext ArtifactDownloadResult.Success(
                file = destination,
                finalUrl = url,
                contentLength = destination.length(),
                manifestMainClass = null // Not reading it here
            )
        }

        val partFile = File("${destination.absolutePath}.part")
        destination.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                val msg = "Download error: Could not create ${parent.absolutePath}"
                onProgress(msg)
                return@withContext ArtifactDownloadResult.NetworkFailure(url, msg, false)
            }
        }

        var lastResult: ArtifactDownloadResult? = null
        for (attempt in 0..2) {
            try {
                if (attempt > 0) {
                    val backoff = (Math.pow(2.0, attempt.toDouble()).toLong() * 1000L).coerceAtMost(10_000L)
                    onProgress("Retrying $name download (${attempt + 1}/3) in ${backoff / 1000}s...")
                    delay(backoff)
                } else {
                    onProgress("Connecting to download $name...")
                }

                partFile.delete()

                val requestBuilder = Request.Builder().url(url).get()
                for ((headerName, headerValue) in requestHeaders) {
                    if (headerName.isNotBlank() && headerValue.isNotBlank()) {
                        requestBuilder.header(headerName, headerValue)
                    }
                }
                val request = requestBuilder.build()
                var downloaded = 0L
                var expectedLength = -1L

                val call = client.newCall(request)
                operationId?.let { 
                    activeCalls[it] = call 
                }
                
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val code = response.code
                            val isTerminal = (code == 404 || code == 403)
                            val retryable = !isTerminal && (code == 429 || code >= 500)
                            
                            val res = ArtifactDownloadResult.HttpFailure(url, code, retryable)
                            lastResult = res
                            
                            if (isTerminal) {
                                onProgress("Terminal HTTP failure: $code")
                                return@withContext lastResult!!
                            } else if (retryable && attempt < 2) {
                                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                                if (retryAfter != null) {
                                     onProgress("Server requested retry after $retryAfter seconds.")
                                     delay(retryAfter * 1000L)
                                }
                                throw IOException("HTTP $code")
                            } else {
                                throw IOException("HTTP $code")
                            }
                        }

                        val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                        if (
                            contentType.contains("text/html") ||
                            contentType.contains("text/plain") ||
                            contentType.contains("application/json") ||
                            contentType.contains("application/xml") ||
                            contentType.contains("text/xml")
                        ) {
                            val res = ArtifactDownloadResult.ValidationFailure(url, "Server returned $contentType instead of a binary file")
                            lastResult = res
                            return@withContext lastResult!!
                        }

                        val body = response.body ?: throw IOException("Empty response body")
                        expectedLength = body.contentLength()
                        if (expectedLength == 0L) {
                            throw IOException("Received an empty file")
                        }

                        body.byteStream().use { input ->
                            FileOutputStream(partFile).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                var lastUpdate = System.currentTimeMillis()
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    downloaded += bytesRead

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate >= 1_000L) {
                                        lastUpdate = now
                                        if (expectedLength > 0L) {
                                            val progress = (downloaded * 100L / expectedLength)
                                                .coerceIn(0L, 100L)
                                            onProgress("Downloading $name ($progress%)")
                                        } else {
                                            onProgress(
                                                "Downloading $name... " +
                                                    String.format(
                                                        "%.2f MB",
                                                        downloaded / (1024.0 * 1024.0)
                                                    )
                                            )
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                    }
                } finally {
                    operationId?.let { 
                        if (activeCalls[it] == call) {
                            activeCalls.remove(it)
                        }
                    }
                    if (lastResult is ArtifactDownloadResult.HttpFailure && !(lastResult as ArtifactDownloadResult.HttpFailure).retryable) {
                        // Terminal failure, exit repeat
                    } else if (lastResult is ArtifactDownloadResult.ValidationFailure) {
                        // Validation failure, exit repeat
                    } else {
                        // Continue to next attempt if not Success
                    }
                }
                
                if (lastResult is ArtifactDownloadResult.HttpFailure && !(lastResult as ArtifactDownloadResult.HttpFailure).retryable) {
                    return@withContext lastResult!!
                }
                if (lastResult is ArtifactDownloadResult.ValidationFailure) {
                    return@withContext lastResult!!
                }

                if (downloaded <= 0L) throw IOException("Downloaded file is empty")
                if (expectedLength > 0L && downloaded != expectedLength) {
                    throw IOException(
                        "Download incomplete: expected $expectedLength bytes, got $downloaded"
                    )
                }

                if (isJar) {
                    onProgress("Validating JAR integrity...")
                    if (url.substringBefore("?").lowercase().endsWith(".zip") && isZipFile(partFile)) {
                        onProgress("Detected ZIP archive; extracting JAR...")
                        if (!extractJarFromZip(partFile, partFile, expectedJarNameInsideZip)) {
                             throw IOException("Could not find a valid JAR inside the downloaded ZIP archive.")
                        }
                    }
                    if (!validateGenericJar(partFile)) {
                        throw IOException("Downloaded file is not a valid JAR")
                    }
                }

                try {
                    Files.move(
                        partFile.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: Exception) {
                    Files.move(
                        partFile.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }

                onProgress("Download $name complete.")
                return@withContext ArtifactDownloadResult.Success(
                    file = destination,
                    finalUrl = url,
                    contentLength = downloaded,
                    manifestMainClass = null // Could read from JarFile if needed
                )
            } catch (e: Exception) {
                if (e is CancellationException || (e is IOException && e.message?.contains("Canceled", ignoreCase = true) == true)) {
                    partFile.delete()
                    return@withContext ArtifactDownloadResult.Cancelled
                }
                lastResult = ArtifactDownloadResult.NetworkFailure(url, e.message ?: e.javaClass.simpleName, true)
                partFile.delete()
            }
        }

        onProgress("Download error: ${if (lastResult is ArtifactDownloadResult.NetworkFailure) (lastResult as ArtifactDownloadResult.NetworkFailure).message else "unknown failure"}")
        return@withContext lastResult ?: ArtifactDownloadResult.NetworkFailure(url, "Unknown error", false)
    }

    fun sha256(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun validateGenericJar(file: File): Boolean {
        if (!file.isFile || file.length() < 1024L) return false
        return try {
            JarFile(file).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    if (entries.nextElement().name.endsWith(".class")) {
                        return true
                    }
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isZipFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 &&
                        header[0] == 'P'.code.toByte() &&
                        header[1] == 'K'.code.toByte() &&
                        header[2] == 0x03.toByte() &&
                        header[3] == 0x04.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private const val MAX_SINGLE_ENTRY_SIZE = 500L * 1024 * 1024
    private const val MAX_TOTAL_EXTRACTED_SIZE = 750L * 1024 * 1024

    private fun extractJarFromZip(zipFile: File, destinationJar: File, expectedJarName: String? = null): Boolean {
        if (expectedJarName == null) {
            Log.e("Downloader", "Extraction blocked: expectedJarName is required for strict mode.")
            return false
        }

        val tempDir = File(zipFile.parentFile, "extract_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) return false
        val canonicalTempPath = tempDir.canonicalPath
        
        var totalExtracted = 0L
        var foundJar: File? = null
        var foundMatchCount = 0

        try {
            zipFile.inputStream().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    
                    while (entry != null) {
                        val entryName = entry.name
                        if (entryName.isBlank()) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }

                        val targetFile = File(tempDir, entryName)
                        if (!targetFile.canonicalPath.startsWith(canonicalTempPath)) {
                            throw IOException("Malicious ZIP entry detected: $entryName")
                        }

                        if (!entry.isDirectory && entryName.substringAfterLast("/") == expectedJarName) {
                            foundMatchCount++
                            if (foundMatchCount > 1) {
                                throw IOException("Ambiguous ZIP: multiple entries match $expectedJarName")
                            }

                            if (entry.size > MAX_SINGLE_ENTRY_SIZE) {
                                throw IOException("ZIP entry $entryName exceeds maximum size limit.")
                            }

                            val extractedFile = File(tempDir, entryName.replace("/", "_"))
                            FileOutputStream(extractedFile).use { fos ->
                                val buffer = ByteArray(16 * 1024)
                                var count: Int
                                while (zis.read(buffer).also { count = it } != -1) {
                                    fos.write(buffer, 0, count)
                                    totalExtracted += count
                                    if (totalExtracted > MAX_TOTAL_EXTRACTED_SIZE) {
                                        throw IOException("Total extracted size exceeds limit.")
                                    }
                                }
                            }
                            
                            foundJar = extractedFile
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    if (foundJar == null) {
                        Log.e("Downloader", "Strict extraction failed: $expectedJarName not found in ZIP.")
                        return false
                    }

                    // Validate it using InstalledEngineVersionRepository (Part 13.9)
                    // We need a dummy EngineVersion or the real one if we had it here.
                    // Actually, let's just use validateGenericJar for now or pass enough info.
                    if (!validateGenericJar(foundJar!!)) {
                        return false
                    }

                    val finalPart = File(destinationJar.parentFile, "${destinationJar.name}.extracted.part")
                    Files.move(foundJar!!.toPath(), finalPart.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    
                    if (zipFile == destinationJar) {
                        zipFile.delete()
                        Files.move(finalPart.toPath(), zipFile.toPath())
                    } else {
                        Files.move(finalPart.toPath(), destinationJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("Downloader", "Failed to extract JAR from ZIP: ${e.message}")
        } finally {
            tempDir.deleteRecursively()
        }
        return false
    }

    suspend fun importManualJar(
        context: Context,
        sourceFile: File,
        version: EngineVersion,
        expectedSha256: String,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress("[Engine] Importing manually verified JAR: ${sourceFile.name}...")
        val suppliedSha256 = expectedSha256.trim().lowercase()
            .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: run {
                onProgress("ERROR: Enter a complete 64-character SHA-256 obtained independently from the publisher.")
                return@withContext false
            }
        val catalogSha256 = publisherChecksum(version)
        if (catalogSha256 != null && !catalogSha256.equals(suppliedSha256, ignoreCase = true)) {
            onProgress("ERROR: The entered SHA-256 conflicts with the trusted publisher checksum in the catalogue.")
            return@withContext false
        }
        val actualSha256 = sha256(sourceFile)
        if (!actualSha256.equals(suppliedSha256, ignoreCase = true)) {
            onProgress("ERROR: Selected JAR SHA-256 does not match the entered value.")
            return@withContext false
        }

        val validation = InstalledEngineVersionRepository.validateJar(
            jarFile = sourceFile,
            launchMode = version.launchMode,
            mainClass = version.mainClass
        )
        if (!validation.valid) {
            onProgress("ERROR: Manual JAR validation failed: ${validation.error}")
            return@withContext false
        }

        val cacheFile = getCacheFile(context, version.engineId, version.id, version.jarFileName)
        val verificationFile = getManualVerificationFile(context, version)
        val stagedFile = File(cacheFile.parentFile, "${cacheFile.name}.manual.part")
        val stagedVerification = File(verificationFile.parentFile, "${verificationFile.name}.part")
        try {
            cacheFile.parentFile?.mkdirs()
            Files.copy(sourceFile.toPath(), stagedFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (!sha256(stagedFile).equals(suppliedSha256, ignoreCase = true)) {
                throw IOException("Staged manual JAR checksum changed during copy")
            }
            val stagedValidation = InstalledEngineVersionRepository.validateJar(
                stagedFile,
                version.launchMode,
                version.mainClass
            )
            if (!stagedValidation.valid) {
                throw IOException(stagedValidation.error ?: "Staged manual JAR is invalid")
            }
            try {
                Files.move(
                    stagedFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    stagedFile.toPath(),
                    cacheFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (!sha256(cacheFile).equals(suppliedSha256, ignoreCase = true)) {
                throw IOException("Committed manual JAR checksum is incorrect")
            }
            getResolvedCacheMetadataFile(context, version).delete()
            val verificationJson = JSONObject()
                .put("engineId", version.engineId)
                .put("versionId", version.id)
                .put("jarFileName", version.jarFileName)
                .put("sha256", suppliedSha256)
                .put("verificationMode", "MANUAL_USER_PROVIDED_SHA256")
                .put("sourceFileName", sourceFile.name)
                .put("verifiedAt", System.currentTimeMillis())
                .toString(2)
            stagedVerification.writeText(verificationJson)
            try {
                Files.move(
                    stagedVerification.toPath(),
                    verificationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    stagedVerification.toPath(),
                    verificationFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            onProgress(
                "[Engine] Manual verification saved. MineHost will use only this exact checksum-verified cached JAR; remote download remains blocked without a publisher catalogue checksum."
            )
            true
        } catch (e: Exception) {
            stagedFile.delete()
            stagedVerification.delete()
            onProgress("ERROR: Failed to cache manually verified JAR: ${e.message}")
            false
        }
    }
}
