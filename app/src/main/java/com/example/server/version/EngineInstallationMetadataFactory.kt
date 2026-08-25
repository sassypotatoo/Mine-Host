package com.example.server.version

import java.io.File
import java.security.MessageDigest

/**
 * Builds installation metadata from the bytes that were actually committed.
 * No caller is allowed to substitute a catalog checksum for a resolved artifact.
 */
object EngineInstallationMetadataFactory {
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    fun sha256OrThrow(file: File): String {
        require(file.isFile && file.length() > 0L) {
            "Engine artifact is missing or empty: ${file.absolutePath}"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }

        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }.also { sha ->
            check(SHA256_PATTERN.matches(sha)) {
                "Engine artifact SHA-256 is invalid"
            }
        }
    }

    fun create(
        version: EngineVersion,
        bedrockVersion: String,
        jarFile: File,
        validation: JarValidationResult,
        resolvedIdentity: ResolvedEngineVersion?,
        runtimeJavaVersion: Int = version.runtimeJavaVersion,
        installedAt: Long = System.currentTimeMillis(),
    ): InstalledEngineVersion {
        require(validation.valid) {
            validation.error ?: "Engine artifact validation failed"
        }
        if (version.engineId == "java_paper" || version.sourceType == VersionSourceType.PAPER_API) {
            require(runtimeJavaVersion in setOf(17, 21, 25)) {
                "Unsupported MineHost Java runtime $runtimeJavaVersion"
            }
        }

        val actualSha256 = sha256OrThrow(jarFile)
        validateResolvedIdentity(version, jarFile, resolvedIdentity)

        val effectiveVersionName = resolvedIdentity?.let {
            "${version.versionName} (resolved build ${it.resolvedBuildNumber})"
        } ?: version.versionName

        val sourceBuildNumber = resolvedIdentity
            ?.resolvedBuildNumber
            ?.toString()
            ?: version.buildNumber

        val resolvedUrl = resolvedIdentity
            ?.resolvedArtifactUrl
            ?: version.downloadUrl

        val artifactRelativePath = resolvedIdentity
            ?.resolvedArtifactUrl
            ?.substringAfter("/artifact/", missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?: version.artifactName
            ?: version.jarFileName

        val sourceJobUrl = resolvedIdentity
            ?.resolvedArtifactUrl
            ?.substringBefore("/artifact/", missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)

        return InstalledEngineVersion(
            engineId = version.engineId,
            versionId = version.id,
            versionName = effectiveVersionName,
            jarFileName = version.jarFileName,
            installedAt = installedAt,
            resolvedAt = resolvedIdentity?.resolvedAt ?: 0L,
            bedrockVersion = bedrockVersion,
            runtimeJavaVersion = runtimeJavaVersion,
            sourceArtifactName = version.artifactName ?: version.jarFileName,
            sourceType = version.sourceType,
            launchMode = version.launchMode,
            mainClass = version.mainClass,
            jarSha256 = actualSha256,
            sourceJobUrl = sourceJobUrl,
            sourceBuildNumber = sourceBuildNumber,
            artifactRelativePath = artifactRelativePath,
            resolvedDownloadUrl = resolvedUrl,
            manifestMainClass = validation.manifestMainClass ?: version.manifestMainClass,
            resolvedIdentity = resolvedIdentity,
        )
    }

    private fun validateResolvedIdentity(
        version: EngineVersion,
        jarFile: File,
        identity: ResolvedEngineVersion?,
    ) {
        if (identity == null) return

        require(identity.catalogBaseId == version.id) {
            "Resolved artifact catalog base does not match ${version.id}"
        }
        require(identity.engineId == version.engineId) {
            "Resolved artifact belongs to another engine"
        }
        require(identity.resolvedBuildNumber > 0) {
            "Resolved artifact has an invalid build number"
        }
        require(identity.resolvedArtifactUrl.isNotBlank()) {
            "Resolved artifact URL is missing"
        }
        require(identity.artifactSize == jarFile.length()) {
            "Resolved artifact size differs from committed JAR"
        }
        require(identity.resolvedAt > 0L) {
            "Resolved artifact timestamp is invalid"
        }
    }
}
