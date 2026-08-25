package com.example.server

import com.example.data.PortTransport

/**
 * Session-aware port ownership. A stale callback from an older runtime cannot
 * release a port now owned by a newer session of the same server UUID.
 * Supports both UDP and TCP transport reservations via [PortKey].
 */
internal class RuntimePortReservationRegistry {
    data class PortKey(
        val transport: PortTransport,
        val port: Int,
    )

    data class Reservation(
        val serverId: String,
        val sessionId: String,
        val port: Int,
        val transport: PortTransport,
        val starting: Boolean,
    )

    sealed interface ReserveResult {
        data class Acquired(val reservation: Reservation) : ReserveResult
        data class Rejected(val reason: String, val ownerServerId: String? = null) : ReserveResult
    }

    private val lock = Any()
    private val byServer = linkedMapOf<String, Reservation>()
    private val byPort = linkedMapOf<PortKey, Reservation>()

    fun reserve(
        serverId: String,
        sessionId: String,
        port: Int,
        transport: PortTransport,
    ): ReserveResult = synchronized(lock) {
        val existingServer = byServer[serverId]
        if (existingServer != null) {
            return@synchronized ReserveResult.Rejected(
                reason = "Server $serverId already owns a runtime reservation",
                ownerServerId = serverId,
            )
        }
        val key = PortKey(transport, port)
        val existingPort = byPort[key]
        if (existingPort != null) {
            return@synchronized ReserveResult.Rejected(
                reason = "${transport.name} port $port is reserved by ${existingPort.serverId}",
                ownerServerId = existingPort.serverId,
            )
        }
        val reservation = Reservation(serverId, sessionId, port, transport, starting = true)
        byServer[serverId] = reservation
        byPort[key] = reservation
        ReserveResult.Acquired(reservation)
    }

    fun markStarted(serverId: String, sessionId: String): Boolean = synchronized(lock) {
        val current = byServer[serverId] ?: return@synchronized false
        if (current.sessionId != sessionId) return@synchronized false
        val updated = current.copy(starting = false)
        byServer[serverId] = updated
        byPort[PortKey(current.transport, current.port)] = updated
        true
    }

    fun release(
        serverId: String,
        sessionId: String,
        port: Int,
        transport: PortTransport,
    ): Boolean = synchronized(lock) {
        val current = byServer[serverId] ?: return@synchronized false
        if (current.sessionId != sessionId || current.port != port || current.transport != transport) return@synchronized false
        byServer.remove(serverId)
        byPort.remove(PortKey(transport, port), current)
        true
    }

    fun reservationFor(serverId: String): Reservation? = synchronized(lock) { byServer[serverId] }
    fun startingServerIds(): Set<String> = synchronized(lock) {
        byServer.values.filterTo(linkedSetOf()) { it.starting }.mapTo(linkedSetOf()) { it.serverId }
    }
    fun reservedPorts(): Set<PortKey> = synchronized(lock) { byPort.keys.toSet() }
    fun clear(): List<Reservation> = synchronized(lock) {
        val previous = byServer.values.toList()
        byServer.clear()
        byPort.clear()
        previous
    }
}
