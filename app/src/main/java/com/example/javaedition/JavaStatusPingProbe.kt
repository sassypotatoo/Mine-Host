package com.example.javaedition

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

data class JavaStatusResult(
    val versionName: String?,
    val protocol: Int?,
    val onlinePlayers: Int?,
    val maxPlayers: Int?,
    val description: String?,
    val rawJson: String
)

object JavaStatusPingProbe {

    const val DEFAULT_TIMEOUT_MS = 1500
    const val MAX_JSON_BYTES = 65536

    @Throws(IOException::class)
    fun writeVarInt(out: OutputStream, value: Int) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    @Throws(IOException::class)
    fun readVarInt(input: InputStream): Int {
        var numRead = 0
        var result = 0
        var read: Int
        do {
            read = input.read()
            if (read == -1) {
                throw IOException("Premature EOF while reading VarInt")
            }
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if (numRead > 5) {
                throw IOException("VarInt is too big (exceeds 5 bytes)")
            }
        } while ((read and 0x80) != 0)
        return result
    }

    private fun writeString(out: OutputStream, str: String) {
        val bytes = str.toByteArray(Charsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    fun ping(host: String = "127.0.0.1", port: Int = 25565, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result<JavaStatusResult> = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs

            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // 1. Send Handshake Packet (0x00)
            val handshakeBytes = ByteArrayOutputStream()
            writeVarInt(handshakeBytes, 0x00) // Packet ID 0x00
            writeVarInt(handshakeBytes, -1)   // Protocol version -1 for status request
            writeString(handshakeBytes, host) // Server host
            handshakeBytes.write((port shr 8) and 0xFF) // Port high byte
            handshakeBytes.write(port and 0xFF)        // Port low byte
            writeVarInt(handshakeBytes, 1)    // Next state: 1 (Status)

            val handshakePacket = handshakeBytes.toByteArray()
            writeVarInt(outputStream, handshakePacket.size)
            outputStream.write(handshakePacket)
            outputStream.flush()

            // 2. Send Status Request Packet (0x00)
            val requestBytes = ByteArrayOutputStream()
            writeVarInt(requestBytes, 0x00) // Packet ID 0x00
            val requestPacket = requestBytes.toByteArray()
            writeVarInt(outputStream, requestPacket.size)
            outputStream.write(requestPacket)
            outputStream.flush()

            // 3. Read Status Response
            val packetLength = readVarInt(inputStream)
            if (packetLength <= 0 || packetLength > MAX_JSON_BYTES + 256) {
                throw IOException("Invalid packet length: $packetLength")
            }

            val packetId = readVarInt(inputStream)
            if (packetId != 0x00) {
                throw IOException("Unexpected packet ID: $packetId (expected 0x00)")
            }

            val jsonStringLength = readVarInt(inputStream)
            if (jsonStringLength <= 0 || jsonStringLength > MAX_JSON_BYTES) {
                throw IOException("Invalid status JSON length: $jsonStringLength")
            }

            val jsonBytes = ByteArray(jsonStringLength)
            val dataInput = DataInputStream(inputStream)
            dataInput.readFully(jsonBytes)

            val rawJson = String(jsonBytes, Charsets.UTF_8)
            parseStatusJson(rawJson)
        }
    }

    fun parseStatusJson(jsonString: String): JavaStatusResult {
        require(jsonString.isNotBlank()) { "Status response JSON is blank" }

        val utf8Size = jsonString.toByteArray(Charsets.UTF_8).size
        require(utf8Size <= MAX_JSON_BYTES) { "Status response JSON exceeds maximum size" }

        val root = JSONObject(jsonString)

        val version = root.optJSONObject("version")
            ?: throw IllegalArgumentException("Status response missing version object")

        val versionName = version.optString("name").trim().takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Status response missing version.name")

        require(version.has("protocol")) { "Status response missing version.protocol" }
        val protocol = version.optInt("protocol", -1)
        require(protocol >= 0) { "Status response has invalid protocol" }

        val players = root.optJSONObject("players")
            ?: throw IllegalArgumentException("Status response missing players object")

        require(players.has("online") && players.has("max")) { "Status response missing player counts" }
        val online = players.optInt("online", -1)
        val max = players.optInt("max", -1)
        require(online >= 0 && max >= 0 && online <= max) { "Status response has invalid player counts" }

        val descriptionStr = when (val desc = root.opt("description")) {
            is String -> desc.trim().takeIf(String::isNotBlank)
            is JSONObject -> desc.optString("text").trim().takeIf(String::isNotBlank)
            else -> null
        }

        return JavaStatusResult(
            versionName = versionName,
            protocol = protocol,
            onlinePlayers = online,
            maxPlayers = max,
            description = descriptionStr,
            rawJson = jsonString
        )
    }
}
