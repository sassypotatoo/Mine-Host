package com.example.server.engine

import com.example.server.ServerStatus
import java.io.File

sealed interface ServerNetworkSnapshot {
    val port: Int
}

data class BedrockNetworkSnapshot(
    override val port: Int,
    val ping: BedrockServerPingResult,
) : ServerNetworkSnapshot

data class JavaNetworkSnapshot(
    override val port: Int,
    val versionName: String?,
    val protocol: Int?,
    val description: String?,
    val onlinePlayers: Int?,
    val maxPlayers: Int?,
) : ServerNetworkSnapshot

interface ServerEngine {
    fun startServer(memoryMb: Int)
    fun stopServer()
    fun restartServer()
    fun sendCommand(command: String)
    fun getStatus(): ServerStatus
    fun setOnlineMode(online: Boolean)
    fun installPlugin(url: String, fileName: String)
    fun backupWorld()
    val serverDir: File
    fun getProcessId(): Long? = null
    fun getStartedAtMillis(): Long? = null
    fun isProcessAlive(): Boolean = false
    @Deprecated("Use getNetworkSnapshot() for edition-neutral status")
    fun getLastPingResult(): BedrockServerPingResult? = null
    fun getNetworkSnapshot(): ServerNetworkSnapshot? = null
    fun getTerminationCause(): TerminationCause = TerminationCause.NONE
    fun getReachedOnlineAtMillis(): Long? = null
    fun getRuntimeFailureClass(): String? = null
    fun isStableSession(): Boolean = false
    fun clearLogs() = Unit
    fun close() = Unit
}
