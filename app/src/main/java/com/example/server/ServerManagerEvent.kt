package com.example.server

import com.example.server.engine.TerminationCause

sealed class ServerManagerEvent {
    data class StatusChanged(
        val serverId: String,
        val sessionId: String,
        val status: ServerStatus,
        val terminationCause: TerminationCause = TerminationCause.NONE,
        val processAlive: Boolean = false
    ) : ServerManagerEvent()
    data class SessionStarted(val serverId: String, val sessionId: String) : ServerManagerEvent()
}
