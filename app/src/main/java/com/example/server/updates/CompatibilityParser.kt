package com.example.server.updates

object CompatibilityParser {
    fun parse(engineId: String, tagName: String, body: String): CompatibilityVerification {
        val versions = mutableSetOf<String>()
        val evidenceParts = mutableListOf<String>()
        var verified = false
        
        // 1. Check for official phrases
        val officialMarkers = listOf(
            "Minecraft Bedrock",
            "Minecraft version",
            "Minecraft:",
            "Protocol support for",
            "Supports Minecraft"
        )
        
        val versionRegex = Regex("\\b1\\.\\d{1,2}\\.\\d{1,3}\\b")
        
        for (marker in officialMarkers) {
            val markerRegex = Regex("${Regex.escape(marker)}\\s*([0-9.]+)", RegexOption.IGNORE_CASE)
            markerRegex.findAll(body).forEach { match ->
                val v = match.groupValues[1]
                if (versionRegex.matches(v)) {
                    versions.add(v)
                    evidenceParts.add("Found '$marker $v' in release notes")
                    verified = true
                }
            }
        }
        
        // 2. Engine-specific patterns
        when (engineId) {
            "bedrock_power_nukkit_x" -> {
                // PNX often puts version in tag like 2.0.0-1.20.70 or has it in the title
                if (tagName.contains("-")) {
                    val parts = tagName.split("-")
                    for (part in parts) {
                        if (versionRegex.matches(part)) {
                            versions.add(part)
                            evidenceParts.add("Found Bedrock version '$part' in tag name")
                            verified = true
                        }
                    }
                }
            }
            "bedrock_nukkit" -> {
                // PM1E: Nukkit PM1E 1.21.2.4437 -> 1.21.2 is Bedrock, 4437 is build
                val pm1eRegex = Regex("Nukkit PM1E\\s+([0-9.]+)", RegexOption.IGNORE_CASE)
                pm1eRegex.findAll(tagName + " " + body).forEach { match ->
                    val v = match.groupValues[1]
                    val parts = v.split(".")
                    if (parts.size >= 3) {
                        val bedrockCandidate = parts.take(3).joinToString(".")
                        if (versionRegex.matches(bedrockCandidate)) {
                            versions.add(bedrockCandidate)
                            evidenceParts.add("Detected Bedrock version '$bedrockCandidate' from PM1E release string '$v'")
                            verified = true
                        }
                    }
                }
            }
            "nukkit-mot" -> {
                if (body.contains("Multi-version support", ignoreCase = true) || 
                    body.contains("Supports all versions", ignoreCase = true) ||
                    body.contains("1.1.x-1.2x.x", ignoreCase = true) ||
                    body.contains("1.10.x", ignoreCase = true)) {
                    verified = true
                    evidenceParts.add("Multi-version support explicitly mentioned in notes")
                }
            }
        }

        // If still not verified, do NOT guess from just the tag name if it's regex-shaped (Requirement 3)
        // A valid JAR with unclear compatibility must remain DETECTED.
        
        return CompatibilityVerification(
            versions = versions.toList().sortedByDescending { it },
            recommendedVersion = versions.maxByOrNull { it },
            verified = verified,
            evidence = if (evidenceParts.isNotEmpty()) evidenceParts.joinToString("; ") else null
        )
    }
}
