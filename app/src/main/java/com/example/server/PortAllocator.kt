package com.example.server

import com.example.data.PortTransport
import java.net.DatagramSocket
import java.net.ServerSocket

/**
 * Allocates and validates UDP/TCP ports for server profiles.
 * A port is considered usable only when it is not owned by another MineHost
 * runtime and the Android/Linux process can actually bind a socket to it.
 */
object PortAllocator {
    const val DEFAULT_PORT = 19132
    const val DEFAULT_JAVA_PORT = 25565
    private const val MIN_PORT = 1024
    private const val MAX_PORT = 65535

    @Volatile
    internal var binder: PortBinder = RealPortBinder()

    data class Result(
        val available: Boolean,
        val requestedPort: Int,
        val suggestedPort: Int? = null,
        val reason: String? = null
    )

    fun validatePort(
        port: Int,
        transport: PortTransport = PortTransport.UDP,
        reservedPorts: Collection<Int> = emptySet()
    ): Result {
        val activeSet = reservedPorts.toSet()
        if (port !in MIN_PORT..MAX_PORT) {
            val defaultPort = if (transport == PortTransport.TCP) DEFAULT_JAVA_PORT else DEFAULT_PORT
            return Result(
                available = false,
                requestedPort = port,
                suggestedPort = findAvailable(defaultPort, activeSet, transport),
                reason = "${transport.name} port must be between $MIN_PORT and $MAX_PORT."
            )
        }

        if (port in activeSet) {
            return Result(
                available = false,
                requestedPort = port,
                suggestedPort = findAvailable(port + 1, activeSet, transport),
                reason = "${transport.name} port $port is already owned by another running MineHost server."
            )
        }

        val canBind = when (transport) {
            PortTransport.UDP -> binder.canBindUdp(port)
            PortTransport.TCP -> binder.canBindTcp(port)
        }

        if (!canBind) {
            return Result(
                available = false,
                requestedPort = port,
                suggestedPort = findAvailable(port + 1, activeSet, transport),
                reason = "${transport.name} port $port is already in use by another process."
            )
        }

        return Result(available = true, requestedPort = port)
    }

    fun validate(requestedPort: Int, activePorts: Set<Int>): Result {
        return validatePort(requestedPort, PortTransport.UDP, activePorts)
    }

    fun findAvailable(
        preferredPort: Int = DEFAULT_PORT,
        activePorts: Set<Int>,
        transport: PortTransport = PortTransport.UDP
    ): Int? {
        val start = preferredPort.coerceIn(MIN_PORT, MAX_PORT)
        val canBind = { p: Int ->
            when (transport) {
                PortTransport.UDP -> binder.canBindUdp(p)
                PortTransport.TCP -> binder.canBindTcp(p)
            }
        }
        for (port in start..MAX_PORT) {
            if (port !in activePorts && canBind(port)) return port
        }
        for (port in MIN_PORT until start) {
            if (port !in activePorts && canBind(port)) return port
        }
        return null
    }
}
