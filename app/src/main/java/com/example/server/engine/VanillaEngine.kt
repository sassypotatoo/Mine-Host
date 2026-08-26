package com.example.server.engine

import android.content.Context
import com.example.server.ServerStatus
import com.example.server.version.EngineVersion
import java.io.File

class VanillaEngine(
    context: Context,
    serverDir: File,
    engineVersion: EngineVersion,
    val minecraftVersion: String,
    port: Int = 25565,
    profileId: String,
    runtimeSessionId: String,
    serverConfig: EngineServerConfig,
    minecraftEulaAccepted: Boolean = false,
    onLog: (String) -> Unit,
    onStatusChange: (ServerStatus) -> Unit
) : JavaEditionEngineBase(
    context = context,
    serverDir = serverDir,
    engineVersion = engineVersion,
    port = port,
    profileId = profileId,
    runtimeSessionId = runtimeSessionId,
    initialConfig = serverConfig,
    initialEulaAccepted = minecraftEulaAccepted,
    onLog = onLog,
    onStatusChange = onStatusChange
) {

    override fun getEngineId(): String = "java_vanilla"

    // Pinned catalog entries carry their own runtimeJavaVersion; the shared
    // EngineRuntimeJavaPolicy resolves non-Paper engines from that field.
    override fun onGetSelectedVersion(): String = minecraftVersion
}
