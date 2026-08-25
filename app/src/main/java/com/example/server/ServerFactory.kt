package com.example.server

import android.content.Context
import com.example.server.engine.CloudburstEngine
import com.example.server.engine.NukkitEngine
import com.example.server.engine.PowerNukkitEngine
import com.example.server.engine.PowerNukkitXEngine
import com.example.server.engine.NukkitMOTEngine
import com.example.server.engine.PaperEngine
import com.example.server.engine.ServerEngine
import com.example.server.template.ServerTemplate
import com.example.server.version.EngineVersion
import java.io.File

object ServerFactory {
    fun createEngine(
        context: Context,
        serverDir: File,
        template: ServerTemplate,
        engineVersion: EngineVersion,
        bedrockVersion: String,
        port: Int,
        profileId: String,
        runtimeSessionId: String,
        serverConfig: com.example.server.engine.EngineServerConfig,
        minecraftEulaAccepted: Boolean = false,
        onLog: (String) -> Unit,
        onStatusChange: (ServerStatus) -> Unit
    ): ServerEngine {
        require(engineVersion.engineId == template.id) {
            "Engine mismatch: template=${template.id}, version=${engineVersion.engineId}"
        }

        return when (template.id) {
            "bedrock_power_nukkit", "PowerNukkit" -> PowerNukkitEngine(context, serverDir, engineVersion, bedrockVersion, port, profileId, runtimeSessionId, serverConfig, onLog, onStatusChange)
            "bedrock_power_nukkit_x", "PowerNukkitX" -> PowerNukkitXEngine(context, serverDir, engineVersion, bedrockVersion, port, profileId, runtimeSessionId, serverConfig, onLog, onStatusChange)
            "bedrock_nukkit", "PM1E" -> NukkitEngine(context, serverDir, engineVersion, bedrockVersion, port, profileId, runtimeSessionId, serverConfig, onLog, onStatusChange)
            "bedrock_cloudburst_nukkit", "Cloudburst" -> CloudburstEngine(context, serverDir, engineVersion, bedrockVersion, port, profileId, runtimeSessionId, serverConfig, onLog, onStatusChange)
            "nukkit-mot", "Nukkit-MOT" -> NukkitMOTEngine(context, serverDir, engineVersion, bedrockVersion, port, profileId, runtimeSessionId, serverConfig, onLog, onStatusChange)
            "java_paper", "Paper", "PaperMC" -> PaperEngine(
                context = context,
                serverDir = serverDir,
                engineVersion = engineVersion,
                minecraftVersion = bedrockVersion,
                port = port,
                profileId = profileId,
                runtimeSessionId = runtimeSessionId,
                serverConfig = serverConfig,
                minecraftEulaAccepted = minecraftEulaAccepted,
                onLog = onLog,
                onStatusChange = onStatusChange
            )
            else -> error("Unsupported engine ID: ${template.id}")
        }
    }
}
