package com.example.server.version

import com.example.data.StorageResult
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.jar.JarFile

data class JarValidationResult(
    val valid: Boolean,
    val hasClasses: Boolean = false,
    val manifestMainClass: String? = null,
    val requestedLaunchMode: LaunchMode = LaunchMode.JAVA_JAR,
    val requestedMainClass: String? = null,
    val missingClassEntry: String? = null,
    val error: String? = null
)

object InstalledEngineVersionRepository {
    private const val META_PATH = ".minehost/engine-installation.json"

    private fun metaStore(serverDir: File) = com.example.server.updates.AtomicJsonFileStore(File(serverDir, META_PATH))

    fun metadataExists(serverDir: File): Boolean {
        return File(serverDir, META_PATH).exists()
    }

    fun metadataIsReadable(serverDir: File): Boolean {
        return metaStore(serverDir).loadRaw() is StorageResult.Success
    }

    fun read(serverDir: File): InstalledEngineVersion? {
        val loadResult = metaStore(serverDir).loadRaw()
        val jsonString = when (loadResult) {
            is StorageResult.Success -> loadResult.value
            is StorageResult.Recovered -> loadResult.value
            else -> return null
        }

        return try {
            val json = JSONObject(jsonString)
            val jarFileName = when {
                json.has("jarFileName") -> json.optString("jarFileName")
                json.has("jarName") -> json.optString("jarName")
                else -> ""
            }
            InstalledEngineVersion(
                engineId = json.optString("engineId", ""),
                versionId = json.getString("versionId"),
                versionName = json.optString("versionName", ""),
                jarFileName = jarFileName,
                installedAt = json.optLong("installedAt", 0L),
                resolvedAt = json.optLong("resolvedAt", 0L),
                firstReadyAt = json.optLong("firstReadyAt", 0L),
                lastReadyAt = json.optLong("lastReadyAt", 0L),
                bedrockVersion = json.optString("bedrockVersion", ""),
                runtimeJavaVersion = json.optInt("runtimeJavaVersion", 21),
                sourceArtifactName = if (json.isNull("sourceArtifactName")) null else json.getString("sourceArtifactName"),
                sourceType = try { VersionSourceType.valueOf(json.optString("sourceType", "GITHUB_RELEASE")) } catch(e: Exception) { VersionSourceType.GITHUB_RELEASE },
                launchMode = try { LaunchMode.valueOf(json.optString("launchMode", "JAVA_JAR")) } catch(e: Exception) { LaunchMode.JAVA_JAR },
                mainClass = if (json.isNull("mainClass")) null else json.getString("mainClass"),
                jarSha256 = if (json.isNull("jarSha256")) null else json.getString("jarSha256"),
                sourceJobUrl = if (json.isNull("sourceJobUrl")) null else json.optString("sourceJobUrl"),
                sourceBuildNumber = if (json.isNull("sourceBuildNumber")) null else json.optString("sourceBuildNumber"),
                artifactRelativePath = if (json.isNull("artifactRelativePath")) null else json.optString("artifactRelativePath"),
                resolvedDownloadUrl = if (json.isNull("resolvedDownloadUrl")) null else json.optString("resolvedDownloadUrl"),
                manifestMainClass = if (json.isNull("manifestMainClass")) null else json.optString("manifestMainClass"),
                resolvedIdentity = if (json.isNull("resolvedIdentity")) null else {
                    val rid = json.getJSONObject("resolvedIdentity")
                    com.example.server.version.ResolvedEngineVersion(
                        catalogBaseId = rid.optString("catalogBaseId", ""),
                        effectiveVersionId = rid.optString("effectiveVersionId", ""),
                        engineId = rid.optString("engineId", ""),
                        resolvedBuildNumber = rid.optInt("resolvedBuildNumber", -1),
                        resolvedArtifactUrl = rid.optString("resolvedArtifactUrl", ""),
                        artifactSize = rid.optLong("artifactSize", 0L),
                        sourceRepository = rid.optString("sourceRepository", ""),
                        sourceRevision = if (rid.isNull("sourceRevision")) null else rid.getString("sourceRevision"),
                        resolvedAt = rid.optLong("resolvedAt", 0L),
                        generatorId = rid.optString("generatorId", ""),
                        generatorRevision = rid.optString("generatorRevision", "")
                    )
                }
            )
        } catch (e: Exception) {
            null
        }
    }

    fun write(
        serverDir: File,
        installed: InstalledEngineVersion,
    ): Boolean {
        return try {
            val jarFile = File(serverDir, installed.jarFileName)
            if (!jarFile.isFile || jarFile.length() <= 0L) return false

            val actualSha = normalizedSha256(calculateSha256(jarFile))
                ?: return false
            val requestedSha = normalizedSha256(installed.jarSha256)
                ?: return false
            if (requestedSha != actualSha) return false

            installed.resolvedIdentity?.let { identity ->
                if (identity.engineId != installed.engineId) return false
                if (identity.catalogBaseId != installed.versionId) return false
                if (identity.artifactSize != jarFile.length()) return false
                if (identity.resolvedAt <= 0L) return false
                if (installed.resolvedDownloadUrl != identity.resolvedArtifactUrl) {
                    return false
                }
                if (
                    installed.sourceBuildNumber !=
                    identity.resolvedBuildNumber.toString()
                ) return false
            }

            val json = JSONObject().apply {
                put("engineId", installed.engineId)
                put("versionId", installed.versionId)
                put("versionName", installed.versionName)
                put("jarFileName", installed.jarFileName)
                put("installedAt", installed.installedAt)
                put("resolvedAt", installed.resolvedAt)
                put("firstReadyAt", installed.firstReadyAt)
                put("lastReadyAt", installed.lastReadyAt)
                put("bedrockVersion", installed.bedrockVersion)
                put("runtimeJavaVersion", installed.runtimeJavaVersion)
                put("sourceArtifactName", installed.sourceArtifactName)
                put("sourceType", installed.sourceType.name)
                put("launchMode", installed.launchMode.name)
                put("mainClass", installed.mainClass)
                put("jarSha256", actualSha)
                put("sourceJobUrl", installed.sourceJobUrl)
                put("sourceBuildNumber", installed.sourceBuildNumber)
                put("artifactRelativePath", installed.artifactRelativePath)
                put("resolvedDownloadUrl", installed.resolvedDownloadUrl)
                put("manifestMainClass", installed.manifestMainClass)
                installed.resolvedIdentity?.let { identity ->
                    put("resolvedIdentity", JSONObject().apply {
                        put("catalogBaseId", identity.catalogBaseId)
                        put("effectiveVersionId", identity.effectiveVersionId)
                        put("engineId", identity.engineId)
                        put("resolvedBuildNumber", identity.resolvedBuildNumber)
                        put("resolvedArtifactUrl", identity.resolvedArtifactUrl)
                        put("artifactSize", identity.artifactSize)
                        put("sourceRepository", identity.sourceRepository)
                        put("sourceRevision", identity.sourceRevision)
                        put("resolvedAt", identity.resolvedAt)
                        put("generatorId", identity.generatorId)
                        put("generatorRevision", identity.generatorRevision)
                    })
                }
            }

            metaStore(serverDir).save(json.toString(2)) is StorageResult.Success
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateSha256(file: File): String? {
        if (!file.exists()) return null
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun matches(
        serverDir: File,
        selectedVersion: EngineVersion,
        selectedBedrockVersion: String
    ): Boolean {
        val metadata = read(serverDir) ?: return false
        
        if (metadata.engineId != selectedVersion.engineId) return false
        if (metadata.versionId != selectedVersion.id) return false
        if (metadata.jarFileName != selectedVersion.jarFileName) return false
        if (metadata.bedrockVersion != selectedBedrockVersion) return false
        val expectedRuntime = EngineRuntimeJavaPolicy.requiredMajor(selectedVersion, selectedBedrockVersion)
        if (metadata.runtimeJavaVersion != expectedRuntime) return false
        if (metadata.launchMode != selectedVersion.launchMode) return false
        if (metadata.mainClass != selectedVersion.mainClass) return false
        
        val jarFile = File(serverDir, selectedVersion.jarFileName)
        if (!jarFile.isFile || jarFile.length() <= 0L) return false

        val currentSha256 = normalizedSha256(
            calculateSha256(jarFile)
        ) ?: return false

        val storedSha256 = normalizedSha256(metadata.jarSha256)
            ?: return false

        if (storedSha256 != currentSha256) {
            return false
        }

        val trustedCatalogSha256 = normalizedSha256(selectedVersion.sha256)
        val officialResolvedInstall =
            isTrustedOfficialResolvedInstall(metadata, selectedVersion)

        metadata.resolvedIdentity?.let { identity ->
            if (!officialResolvedInstall) return false
            if (identity.artifactSize != jarFile.length()) return false
            if (metadata.resolvedAt != identity.resolvedAt) return false
            if (metadata.resolvedDownloadUrl != identity.resolvedArtifactUrl) {
                return false
            }
            if (
                metadata.sourceBuildNumber !=
                identity.resolvedBuildNumber.toString()
            ) return false
        }

        if (
            trustedCatalogSha256 != null &&
            trustedCatalogSha256 != currentSha256 &&
            !officialResolvedInstall
        ) {
            return false
        }

        if (!validateJar(jarFile, selectedVersion.launchMode, selectedVersion.mainClass).valid) {
            return false
        }

        return true
    }

    private fun normalizedSha256(value: String?): String? =
        value
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

    private fun isTrustedOfficialResolvedInstall(
        metadata: InstalledEngineVersion,
        selectedVersion: EngineVersion,
    ): Boolean {
        if (selectedVersion.engineId == "nukkit-mot") {
            return isTrustedOfficialResolvedNukkitInstall(metadata, selectedVersion)
        }
        if (selectedVersion.engineId == "java_paper") {
            return isTrustedOfficialResolvedPaperInstall(metadata, selectedVersion)
        }
        return false
    }

    private fun isTrustedOfficialResolvedPaperInstall(
        metadata: InstalledEngineVersion,
        selectedVersion: EngineVersion,
    ): Boolean {
        val identity = metadata.resolvedIdentity ?: return false
        if (identity.engineId != "java_paper") return false
        if (identity.catalogBaseId != selectedVersion.id) return false
        if (identity.resolvedBuildNumber <= 0) return false
        if (identity.artifactSize <= 1024L) return false
        if (identity.resolvedAt <= 0L) return false
        val selectedMc = metadata.bedrockVersion.trim()
        if (selectedMc.isBlank() || selectedMc.equals("AUTO", ignoreCase = true)) return false
        if (!identity.effectiveVersionId.startsWith("java_paper:${selectedMc}:")) return false
        if (!identity.generatorId.startsWith("paper/")) return false

        val uri = runCatching { URI(identity.resolvedArtifactUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase().orEmpty()
        return com.example.javaedition.PaperUserAgentProvider.isTrustedPaperHost(host)
    }

    private fun isTrustedOfficialResolvedNukkitInstall(
        metadata: InstalledEngineVersion,
        selectedVersion: EngineVersion,
    ): Boolean {
        if (selectedVersion.engineId != "nukkit-mot") return false

        val identity = metadata.resolvedIdentity ?: return false
        if (identity.engineId != selectedVersion.engineId) return false
        if (identity.catalogBaseId != selectedVersion.id) return false
        if (identity.resolvedBuildNumber <= 0) return false
        if (identity.artifactSize <= 1024L) return false
        if (identity.resolvedAt <= 0L) return false
        if (identity.effectiveVersionId !=
            "nukkit-mot:${identity.resolvedBuildNumber}"
        ) return false
        if (identity.generatorId != "nukkit-mot/normal") return false
        if (identity.generatorRevision !=
            "jenkins-build:${identity.resolvedBuildNumber}"
        ) return false

        val normalizedRepository = identity.sourceRepository
            .trim()
            .removeSuffix(".git")
            .removeSuffix("/")
        if (
            normalizedRepository !=
            "https://github.com/MemoriesOfTime/Nukkit-MOT"
        ) return false

        val uri = runCatching {
            URI(identity.resolvedArtifactUrl)
        }.getOrNull() ?: return false

        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.host.equals("motci.cn", ignoreCase = true)) return false
        if (!uri.path.contains("/job/Nukkit-MOT/job/master/")) return false
        if (
            !uri.path.contains(
                "/${identity.resolvedBuildNumber}/artifact/"
            )
        ) return false
        if (!uri.path.endsWith("/artifact/target/Nukkit-MOT-SNAPSHOT.jar")) {
            return false
        }

        return true
    }

    fun verifiedResolvedIdentity(
        serverDir: File,
        selectedVersion: EngineVersion,
        selectedBedrockVersion: String,
    ): com.example.server.version.ResolvedEngineVersion? {
        if (!matches(serverDir, selectedVersion, selectedBedrockVersion)) {
            return null
        }
        return read(serverDir)?.resolvedIdentity
    }

    fun validateJar(jarFile: File, launchMode: LaunchMode = LaunchMode.JAVA_JAR, mainClass: String? = null): JarValidationResult {
        if (!jarFile.exists()) return JarValidationResult(false, error = "File does not exist")
        if (jarFile.length() < 1024L) return JarValidationResult(false, error = "File is too small")
        
        return try {
            JarFile(jarFile).use { jar ->
                var hasClass = false
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".class")) {
                        hasClass = true
                        break
                    }
                }
                
                if (!hasClass) {
                    return JarValidationResult(false, hasClasses = false, error = "JAR contains no .class files")
                }

                if (launchMode == LaunchMode.JAVA_JAR) {
                    val manifest = jar.manifest
                    val manifestMainClass = manifest?.mainAttributes?.getValue(java.util.jar.Attributes.Name.MAIN_CLASS)
                    if (manifestMainClass.isNullOrBlank()) {
                        return JarValidationResult(false, hasClasses = true, error = "Manifest missing Main-Class")
                    }
                    
                    val classPath = manifestMainClass.replace('.', '/') + ".class"
                    if (jar.getJarEntry(classPath) == null) {
                        return JarValidationResult(
                            valid = false, 
                            hasClasses = true, 
                            manifestMainClass = manifestMainClass,
                            missingClassEntry = classPath,
                            error = "Manifest Main-Class not found in JAR"
                        )
                    }
                    JarValidationResult(true, hasClasses = true, manifestMainClass = manifestMainClass)
                } else if (launchMode == LaunchMode.MAIN_CLASS) {
                    if (mainClass.isNullOrBlank()) {
                        return JarValidationResult(false, hasClasses = true, error = "Launch mode MAIN_CLASS requires a main class")
                    }
                    val classPath = mainClass.replace('.', '/') + ".class"
                    if (jar.getJarEntry(classPath) == null) {
                         return JarValidationResult(
                            valid = false, 
                            hasClasses = true, 
                            requestedLaunchMode = LaunchMode.MAIN_CLASS,
                            requestedMainClass = mainClass,
                            missingClassEntry = classPath,
                            error = "Requested main class not found in JAR"
                        )
                    }
                    
                    // Stage 3: Nukkit-specific validation
                    if (mainClass == "cn.nukkit.Nukkit" || mainClass.contains("nukkit", ignoreCase = true)) {
                        val essentialClasses = listOf(
                            "cn/nukkit/Nukkit.class",
                            "cn/nukkit/Server.class",
                            "cn/nukkit/level/Level.class"
                        )
                        for (essential in essentialClasses) {
                            if (jar.getJarEntry(essential) == null) {
                                return JarValidationResult(false, hasClasses = true, error = "Essential Nukkit class missing: $essential")
                            }
                        }
                    }

                    JarValidationResult(true, hasClasses = true, requestedLaunchMode = LaunchMode.MAIN_CLASS, requestedMainClass = mainClass)
                } else {
                    JarValidationResult(false, hasClasses = true, error = "Unknown launch mode")
                }
            }
        } catch (e: Exception) {
            JarValidationResult(false, error = e.message)
        }
    }
}
