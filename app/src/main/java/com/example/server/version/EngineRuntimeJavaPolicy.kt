package com.example.server.version

import com.example.javaedition.PaperResolver

object EngineRuntimeJavaPolicy {

    fun requiredMajor(
        version: EngineVersion,
        selectedMinecraftVersion: String?,
    ): Int {
        return if (
            version.sourceType == VersionSourceType.PAPER_API ||
            version.engineId == "java_paper"
        ) {
            val selected = selectedMinecraftVersion
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
                ?: error("Paper requires an exact supported Minecraft version for Java runtime resolution.")
            PaperResolver.determineRequiredJavaMajor(selected)
        } else {
            version.runtimeJavaVersion
        }
    }
}
