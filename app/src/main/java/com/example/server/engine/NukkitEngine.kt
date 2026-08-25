package com.example.server.engine

import android.content.Context
import com.example.server.ServerStatus

import com.example.server.version.EngineVersion

class NukkitEngine(
    context: Context,
    serverDir: java.io.File,
    engineVersion: EngineVersion,
    selectedBedrockVersion: String,
    port: Int,
    profileId: String,
    runtimeSessionId: String,
    serverConfig: EngineServerConfig,
    onLog: (String) -> Unit,
    onStatusChange: (ServerStatus) -> Unit
) : BedrockJavaEngineBase(
    context,
    serverDir,
    engineVersion,
    selectedBedrockVersion,
    port,
    profileId,
    runtimeSessionId,
    serverConfig,
    onLog,
    onStatusChange
) {
    override fun getEngineId(): String = "bedrock_nukkit"
}
