package com.example.server.template

import com.example.server.ServerType

data class ServerTemplate(
    val id: String,
    val name: String,
    val description: String,
    val serverType: ServerType,
    val defaultMemoryMb: Int,
    val requiredPlugins: List<PluginInfo> = emptyList(),
    val available: Boolean = true,
    val unavailableReason: String? = null
)

data class PluginInfo(
    val name: String,
    val downloadUrl: String
)

object TemplateRegistry {
    val BEDROCK_NUKKIT = ServerTemplate(
        id = "bedrock_nukkit",
        name = "PM1E",
        description = "PetteriM1 Edition Bedrock server engine",
        serverType = ServerType.BEDROCK_NUKKIT,
        defaultMemoryMb = 600
    )

    val BEDROCK_POWER_NUKKIT = ServerTemplate(
        id = "bedrock_power_nukkit",
        name = "PowerNukkit (Legacy)",
        description = "Native Bedrock server software with advanced features (Legacy)",
        serverType = ServerType.BEDROCK_NUKKIT,
        defaultMemoryMb = 600,
        available = false,
        unavailableReason = "Legacy engine no longer supported"
    )

    val BEDROCK_POWER_NUKKIT_X = ServerTemplate(
        id = "bedrock_power_nukkit_x",
        name = "PowerNukkitX",
        description = "Advanced Bedrock server software",
        serverType = ServerType.BEDROCK_NUKKIT,
        defaultMemoryMb = 600
    )

    val BEDROCK_CLOUDBURST_NUKKIT = ServerTemplate(
        id = "bedrock_cloudburst_nukkit",
        name = "Cloudburst Nukkit",
        description = "High-performance Cloudburst Nukkit Bedrock engine",
        serverType = ServerType.BEDROCK_NUKKIT,
        defaultMemoryMb = 600,
        available = true,
        unavailableReason = null
    )

    val BEDROCK_NUKKIT_MOT = ServerTemplate(
        id = "nukkit-mot",
        name = "Nukkit-MOT",
        description = "High-performance Java-based Bedrock server software",
        serverType = ServerType.BEDROCK_NUKKIT,
        defaultMemoryMb = 600
    )

    val JAVA_PAPER = ServerTemplate(
        id = "java_paper",
        name = "PaperMC",
        description = "High-performance Minecraft Java Edition server engine",
        serverType = ServerType.JAVA_PAPER,
        defaultMemoryMb = 1024
    )

    val JAVA_VANILLA = ServerTemplate(
        id = "java_vanilla",
        name = "Vanilla",
        description = "Official Mojang Minecraft Java Edition server",
        serverType = ServerType.JAVA_VANILLA,
        defaultMemoryMb = 1024
    )

    val JAVA_FABRIC = ServerTemplate(
        id = "java_fabric",
        name = "Fabric",
        description = "Lightweight modded Minecraft Java Edition server",
        serverType = ServerType.JAVA_FABRIC,
        defaultMemoryMb = 1024
    )

    /** Bedrock and Java engine templates available in the engine selection UI. */
    val ALL_TEMPLATES = listOf(
        BEDROCK_POWER_NUKKIT_X,
        BEDROCK_CLOUDBURST_NUKKIT,
        BEDROCK_NUKKIT_MOT,
        BEDROCK_NUKKIT,
        JAVA_PAPER,
        JAVA_VANILLA,
        JAVA_FABRIC
    )

    private val REGISTERED_TEMPLATES = listOf(
        BEDROCK_POWER_NUKKIT_X,
        BEDROCK_POWER_NUKKIT,
        BEDROCK_CLOUDBURST_NUKKIT,
        BEDROCK_NUKKIT_MOT,
        BEDROCK_NUKKIT,
        JAVA_PAPER,
        JAVA_VANILLA,
        JAVA_FABRIC
    )

    fun getTemplate(id: String): ServerTemplate? {
        return REGISTERED_TEMPLATES.find { it.id == id }
    }

    /** Engine ids whose profiles are Minecraft Java Edition (TCP, eula.txt, server.properties). */
    val JAVA_EDITION_ENGINE_IDS = setOf("java_paper", "java_vanilla", "java_fabric")

    fun isJavaEditionEngine(engineId: String?): Boolean =
        engineId != null && engineId in JAVA_EDITION_ENGINE_IDS
}
