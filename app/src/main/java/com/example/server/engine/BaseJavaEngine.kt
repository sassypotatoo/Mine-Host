package com.example.server.engine

import android.content.Context
import com.example.server.ServerStatus
import com.example.server.version.EngineVersion
import java.io.File

/**
 * Legacy alias for [BedrockJavaEngineBase].
 * All generic JVM lifecycle management is now in [JvmServerEngineBase],
 * and Bedrock specific extensions are in [BedrockJavaEngineBase].
 */
@Deprecated(
    message = "BaseJavaEngine has been refactored into JvmServerEngineBase and BedrockJavaEngineBase",
    replaceWith = ReplaceWith("BedrockJavaEngineBase")
)
abstract class BaseJavaEngine(
    context: Context,
    serverDir: File,
    engineVersion: EngineVersion,
    selectedBedrockVersion: String,
    port: Int,
    profileId: String,
    runtimeSessionId: String,
    serverConfig: EngineServerConfig,
    onLog: (String) -> Unit,
    onStatusChange: (ServerStatus) -> Unit
) : BedrockJavaEngineBase(
    context = context,
    serverDir = serverDir,
    engineVersion = engineVersion,
    bedrockVersion = selectedBedrockVersion,
    port = port,
    profileId = profileId,
    runtimeSessionId = runtimeSessionId,
    serverConfig = serverConfig,
    onLog = onLog,
    onStatusChange = onStatusChange
)
