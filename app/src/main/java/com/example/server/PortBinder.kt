package com.example.server

internal interface PortBinder {
    fun canBindUdp(port: Int): Boolean
    fun canBindTcp(port: Int): Boolean
}

internal class RealPortBinder : PortBinder {
    override fun canBindUdp(port: Int): Boolean = try {
        java.net.DatagramSocket(null).use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress("0.0.0.0", port))
            true
        }
    } catch (_: Exception) {
        false
    }

    override fun canBindTcp(port: Int): Boolean = try {
        java.net.ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(java.net.InetSocketAddress("0.0.0.0", port))
            true
        }
    } catch (_: Exception) {
        false
    }
}
