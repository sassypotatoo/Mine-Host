package com.example.server.version

enum class ReleaseChannel {
    STABLE,
    SNAPSHOT,
    PREVIEW
}

enum class CompatibilityMode {
    SINGLE_VERSION,
    MULTI_VERSION,
    UNKNOWN
}

enum class EngineReleaseChannel {
    STABLE,
    LEGACY,
    EXPERIMENTAL,
    ROLLING
}

enum class VersionSourceType {
    GITHUB_RELEASE,
    JENKINS_BUILD,
    MAVEN_ARTIFACT,
    ROLLING_PROVIDER,
    USER_IMPORTED,
    MINEHOST_DERIVED,
    PAPER_API
}

enum class LaunchMode {
    JAVA_JAR,
    MAIN_CLASS
}

data class BedrockVersionOption(
    val bedrockVersion: String,
    val engineVersionId: String,
    val engineBuildName: String,
    val recommended: Boolean,
    val compatibilityMode: CompatibilityMode,
    val compatibilitySummary: String?,
    val minimumSupportedBedrockVersion: String? = null,
    val maximumSupportedBedrockVersion: String? = null,
    val historical: Boolean = false,
    val deprecated: Boolean = false,
    val channel: ReleaseChannel = ReleaseChannel.STABLE,
    val releaseChannel: EngineReleaseChannel = EngineReleaseChannel.STABLE,
    val runtimeJavaVersion: Int = 21,
    val available: Boolean = true,
    val unavailableReason: String? = null,
    val warning: String? = null,
    val installability: EngineInstallability = EngineInstallability.MANUAL_VERIFICATION_REQUIRED
)

data class EngineVersion(
    val id: String,
    val engineId: String,
    val versionName: String,
    val displayName: String,
    val channel: ReleaseChannel,
    val releaseChannel: EngineReleaseChannel = EngineReleaseChannel.STABLE,
    val downloadUrl: String,
    val jarFileName: String,
    val requiredJavaVersion: Int,
    val compatibilityLabel: String,
    val recommended: Boolean,
    val supportedBedrockVersions: List<String> = emptyList(),
    val recommendedBedrockVersion: String? = null,
    val compatibilityMode: CompatibilityMode = CompatibilityMode.SINGLE_VERSION,
    val compatibilitySummary: String? = null,
    val historical: Boolean = false,
    val deprecated: Boolean = false,
    val releaseTag: String? = null,
    val buildNumber: String? = null,
    val sourceProject: String? = null,
    val sourceKey: String? = null,
    val sourceType: VersionSourceType = VersionSourceType.GITHUB_RELEASE,
    val artifactName: String? = null,
    val runtimeJavaVersion: Int = 21,
    val launchMode: LaunchMode = LaunchMode.JAVA_JAR,
    val mainClass: String? = null,
    val manifestMainClass: String? = null,
    val availabilityCheckUrl: String? = null,
    val minimumSupportedBedrockVersion: String? = null,
    val maximumSupportedBedrockVersion: String? = null,
    val dynamic: Boolean = false,
    val available: Boolean = true,
    val unavailableReason: String? = null,
    val sha256: String? = null,
    val fileSize: Long? = null,
    val protocolVersions: List<Int> = emptyList(),
    val releaseDateEpochMillis: Long? = null,
    val minimumMineHostVersionCode: Int = 1,
    val deprecationReason: String? = null,
    val generatorId: String? = null,
    val generatorRevision: String? = null
)

enum class EngineInstallability {
    AUTOMATIC_DOWNLOAD,
    MANUAL_VERIFICATION_REQUIRED,
    UNAVAILABLE,
}

enum class ArtifactValidationPolicy {
    OFFICIAL_PINNED_JAR,
    OFFICIAL_RESOLVED_JAR,
    USER_IMPORTED_JAR,
    UNVERIFIED_MUTABLE_JAR
}

fun EngineVersion.validationPolicy(): ArtifactValidationPolicy {
    return when {
        sourceType == VersionSourceType.USER_IMPORTED -> ArtifactValidationPolicy.USER_IMPORTED_JAR
        dynamic || sourceType == VersionSourceType.PAPER_API -> ArtifactValidationPolicy.OFFICIAL_RESOLVED_JAR
        sha256 != null -> ArtifactValidationPolicy.OFFICIAL_PINNED_JAR
        else -> ArtifactValidationPolicy.UNVERIFIED_MUTABLE_JAR
    }
}

private val TRUSTED_SHA256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")

fun EngineVersion.hasTrustedPublisherChecksum(): Boolean =
    sha256?.trim()?.matches(TRUSTED_SHA256_PATTERN) == true

fun EngineVersion.installability(): EngineInstallability = when {
    !available -> EngineInstallability.UNAVAILABLE
    engineId == "nukkit-mot" || validationPolicy() == ArtifactValidationPolicy.OFFICIAL_RESOLVED_JAR || hasTrustedPublisherChecksum() -> EngineInstallability.AUTOMATIC_DOWNLOAD
    else -> EngineInstallability.MANUAL_VERIFICATION_REQUIRED
}

