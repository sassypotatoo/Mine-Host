package com.example.server.version

data class InstalledEngineVersion(
    val engineId: String,
    val versionId: String,
    val versionName: String,
    val jarFileName: String,
    val installedAt: Long,
    val resolvedAt: Long,
    val firstReadyAt: Long = 0,
    val lastReadyAt: Long = 0,
    val bedrockVersion: String,
    val runtimeJavaVersion: Int = 21,
    val sourceArtifactName: String? = null,
    val sourceType: VersionSourceType = VersionSourceType.GITHUB_RELEASE,
    val launchMode: LaunchMode = LaunchMode.JAVA_JAR,
    val mainClass: String? = null,
    val jarSha256: String? = null,
    val sourceJobUrl: String? = null,
    val sourceBuildNumber: String? = null,
    val artifactRelativePath: String? = null,
    val resolvedDownloadUrl: String? = null,
    val manifestMainClass: String? = null,
    val resolvedIdentity: ResolvedEngineVersion? = null
)
