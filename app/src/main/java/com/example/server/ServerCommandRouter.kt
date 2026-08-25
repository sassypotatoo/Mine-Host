package com.example.server

import java.util.concurrent.ConcurrentHashMap

/** Exact UUID + session command ownership. Stale sessions cannot detach newer writers. */
internal class ServerCommandRouter {
    private data class Owner(val sessionId: String, val writer: (String) -> Unit)
    private val owners = ConcurrentHashMap<String, Owner>()

    fun attach(serverId: String, sessionId: String, writer: (String) -> Unit) {
        require(serverId.isNotBlank() && sessionId.isNotBlank())
        owners[serverId] = Owner(sessionId, writer)
    }

    fun detach(serverId: String, sessionId: String): Boolean {
        val current = owners[serverId] ?: return false
        return current.sessionId == sessionId && owners.remove(serverId, current)
    }

    fun send(serverId: String, command: String): Boolean {
        val current = owners[serverId] ?: return false
        current.writer(command)
        return true
    }

    fun sessionId(serverId: String): String? = owners[serverId]?.sessionId

    fun clear() = owners.clear()
}
