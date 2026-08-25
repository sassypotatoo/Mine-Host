package com.example.server.updates

import com.example.server.version.VersionSourceType

data class DetectedEngineRelease(
    val sourceId: String,
    val engineId: String,
    val engineName: String,
    val releaseName: String,
    val releaseTag: String?,
    val buildNumber: Int?,
    val artifactName: String?,
    val artifactUrl: String?,
    val bedrockVersions: List<String> = emptyList(),
    val publishedAt: Long?,
    val prerelease: Boolean,
    val firstDetectedAt: Long,
    val lastCheckedAt: Long,
    val verificationStatus: ReleaseVerificationStatus,
    val verificationMessage: String?,
    val notificationSent: Boolean = false,
    
    // PART 6: Extended verified information
    val verifiedBedrockVersions: List<String> = emptyList(),
    val verifiedRecommendedBedrockVersion: String? = null,
    val verifiedJavaVersion: Int? = null,
    val verifiedLaunchMode: String? = null, // Store as String to avoid dependency circularity or use a raw type
    val verifiedMainClass: String? = null,
    val artifactVerified: Boolean = false,
    val compatibilityVerified: Boolean = false,
    val verificationEvidence: String? = null,
    val sourceType: VersionSourceType? = null,
    val sourceKey: String? = null,
    val sourceName: String? = null,
    val sourceProject: String? = null,
    val artifactSize: Long? = null,
    /** Publisher-provided SHA-256 only. Never populate this with a locally calculated hash. */
    val publisherSha256: String? = null
)

data class CompatibilityVerification(
    val versions: List<String>,
    val recommendedVersion: String?,
    val verified: Boolean,
    val evidence: String?
)
