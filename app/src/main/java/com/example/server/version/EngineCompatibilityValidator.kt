package com.example.server.version

import com.example.BuildConfig
import com.example.data.ServerProfile

object EngineCompatibilityValidator {
    fun validate(profile: ServerProfile, versionCatalog: EngineVersionCatalogRepository): Result {
        val issues = mutableListOf<String>()
        val version = versionCatalog.findVersion(profile.engineVersionId)

        if (version == null) {
            return Result(false, "Selected engine build could not be resolved from the version catalogue.", listOf("Build not found"))
        }

        if (!version.available) {
            issues.add(version.unavailableReason ?: "This engine build is temporarily unavailable.")
        }
        if (version.minimumMineHostVersionCode > BuildConfig.VERSION_CODE) {
            issues.add("This engine build requires MineHost version code ${version.minimumMineHostVersionCode} or newer.")
        }
        if (version.engineId != profile.engineId) {
            issues.add("Engine mismatch: profile expects ${profile.engineId} but build belongs to ${version.engineId}.")
        }
        if (profile.bedrockVersion.isBlank()) {
            issues.add("Minecraft version is missing in the server profile.")
        }

        if (version.compatibilityMode == CompatibilityMode.SINGLE_VERSION) {
            if (profile.bedrockVersion == "AUTO") {
                issues.add("Automatic versioning is not allowed for single-version engines.")
            } else if (!version.supportedBedrockVersions.contains(profile.bedrockVersion) && version.recommendedBedrockVersion != profile.bedrockVersion) {
                issues.add("Selected Minecraft version (${profile.bedrockVersion}) is not verified for this single-version engine build.")
            }
        } else if (version.compatibilityMode == CompatibilityMode.MULTI_VERSION) {
            val requested = profile.bedrockVersion
            if (requested == "AUTO") {
                if (version.supportedBedrockVersions.isEmpty() && 
                    version.minimumSupportedBedrockVersion == null && 
                    version.maximumSupportedBedrockVersion == null) {
                    issues.add("This multi-version build lacks verified compatibility information for automatic selection.")
                }
            } else {
                val supported = version.supportedBedrockVersions
                val min = version.minimumSupportedBedrockVersion
                val max = version.maximumSupportedBedrockVersion

                val inList = supported.isNotEmpty() && supported.contains(requested)
                val inRange = (min == null || compareVersions(requested, min) >= 0) &&
                             (max == null || compareVersions(requested, max) <= 0)
                
                if (!inList) {
                    if (min != null && compareVersions(requested, min) < 0) {
                        issues.add("Minecraft version $requested is too old. Minimum: $min")
                    }
                    if (max != null && compareVersions(requested, max) > 0) {
                        issues.add("Minecraft version $requested is too new. Maximum: $max")
                    }
                    if (supported.isNotEmpty() && min == null && max == null) {
                        issues.add("Minecraft version $requested is not in the verified support list for this build.")
                    }
                }
            }
        } else {
            issues.add("Selected engine build has an unsupported or unknown compatibility mode.")
        }

        return if (issues.isEmpty()) Result(true, "OK")
        else Result(false, issues.first(), issues)
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split('.').mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split('.').mapNotNull { it.toIntOrNull() }
        val length = maxOf(parts1.size, parts2.size)
        for (i in 0 until length) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    data class Result(
        val success: Boolean, 
        val message: String, 
        val issues: List<String> = emptyList()
    )
}
