package com.example.server.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

data class BedrockServerPingResult(
    val responded: Boolean,
    val serverGuid: Long? = null,
    val edition: String? = null,
    val motd: String? = null,
    val protocol: Int? = null,
    val minecraftVersion: String? = null,
    val currentPlayers: Int? = null,
    val maxPlayers: Int? = null,
    val subMotd: String? = null,
    val gameMode: String? = null,
    val portV4: Int? = null,
    val rawAdvertisement: String? = null,
    val error: String? = null
)

object BedrockRakNetProbe {
    private const val TAG = "RakNetProbe"
    
    // RakNet offline-message magic: 00 FF FF 00 FE FE FE FE FD FD FD FD 12 34 56 78
    private val RAKNET_MAGIC = byteArrayOf(
        0x00.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00.toByte(),
        0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(),
        0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
    )

    suspend fun probe(port: Int): BedrockServerPingResult {
        return withContext(Dispatchers.IO) {
            sendPing(port)
        }
    }

    private fun sendPing(port: Int): BedrockServerPingResult {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1500
                val address = InetAddress.getByName("127.0.0.1")
                
                val buffer = ByteBuffer.allocate(33)
                buffer.put(0x01.toByte()) // ID_UNCONNECTED_PING
                buffer.putLong(System.currentTimeMillis()) // Timestamp
                buffer.put(RAKNET_MAGIC)
                buffer.putLong(12345L) // Random Client GUID
                
                val packet = DatagramPacket(buffer.array(), buffer.capacity(), address, port)
                socket.send(packet)
                
                val receiveBuffer = ByteArray(2048)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                socket.receive(receivePacket)
                
                if (receiveBuffer[0] != 0x1C.toByte()) {
                    return BedrockServerPingResult(false, error = "Invalid response packet ID: ${receiveBuffer[0]}")
                }

                val data = ByteBuffer.wrap(receiveBuffer, 0, receivePacket.length)
                data.get() // Skip 0x1C
                data.getLong() // Skip timestamp
                val serverGuid = data.getLong()
                
                // Magic check
                val receivedMagic = ByteArray(16)
                data.get(receivedMagic)
                if (!receivedMagic.contentEquals(RAKNET_MAGIC)) {
                    return BedrockServerPingResult(false, error = "Invalid RakNet magic in pong")
                }

                // Advertisement string
                val length = data.getShort().toInt() and 0xFFFF
                if (length <= 0 || length > 2000) {
                     return BedrockServerPingResult(true, serverGuid = serverGuid)
                }

                val advBytes = ByteArray(length)
                data.get(advBytes)
                val advertisement = String(advBytes, Charsets.UTF_8)
                
                parseAdvertisement(advertisement, serverGuid)
            }
        } catch (e: Exception) {
            BedrockServerPingResult(false, error = e.message)
        }
    }

    private fun parseAdvertisement(adv: String, guid: Long): BedrockServerPingResult {
        val parts = adv.split(";")
        return try {
            BedrockServerPingResult(
                responded = true,
                serverGuid = guid,
                edition = parts.getOrNull(0),
                motd = parts.getOrNull(1),
                protocol = parts.getOrNull(2)?.toIntOrNull(),
                minecraftVersion = parts.getOrNull(3),
                currentPlayers = parts.getOrNull(4)?.toIntOrNull(),
                maxPlayers = parts.getOrNull(5)?.toIntOrNull(),
                subMotd = parts.getOrNull(7),
                gameMode = parts.getOrNull(8),
                portV4 = parts.getOrNull(10)?.toIntOrNull(),
                rawAdvertisement = if (adv.length > 256) adv.take(256) + "..." else adv
            )
        } catch (e: Exception) {
            BedrockServerPingResult(true, serverGuid = guid, rawAdvertisement = adv)
        }
    }
}
