package com.example.javaedition

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

object JavaServerPropertiesAdapter {

    fun applyProperties(
        serverDir: File,
        port: Int,
        onlineMode: Boolean,
        levelName: String = "world",
        gameMode: String = "survival",
        difficulty: String = "easy",
        maxPlayers: Int = 20,
        motd: String = "A MineHost Java Server"
    ) {
        val propsFile = File(serverDir, "server.properties")
        val properties = Properties()

        if (propsFile.isFile && propsFile.length() > 0L) {
            runCatching {
                FileInputStream(propsFile).use { properties.load(it) }
            }
        }

        // Standard Java server.properties keys
        properties.setProperty("server-port", port.toString())
        properties.setProperty("server-ip", "0.0.0.0")
        properties.setProperty("online-mode", onlineMode.toString())
        properties.setProperty("level-name", levelName.ifBlank { "world" })

        // Map gamemode string if needed
        val mappedGamemode = when (gameMode.trim().lowercase()) {
            "0", "survival" -> "survival"
            "1", "creative" -> "creative"
            "2", "adventure" -> "adventure"
            "3", "spectator" -> "spectator"
            else -> "survival"
        }
        properties.setProperty("gamemode", mappedGamemode)

        // Map difficulty string if needed
        val mappedDifficulty = when (difficulty.trim().lowercase()) {
            "0", "peaceful" -> "peaceful"
            "1", "easy" -> "easy"
            "2", "normal" -> "normal"
            "3", "hard" -> "hard"
            else -> "easy"
        }
        properties.setProperty("difficulty", mappedDifficulty)

        properties.setProperty("max-players", maxPlayers.coerceIn(1, 100).toString())
        properties.setProperty("motd", motd.ifBlank { "A MineHost Java Server" })
        
        // Defaults for safe operation
        properties.remove("xbox-auth")
        if (!properties.containsKey("enable-status")) properties.setProperty("enable-status", "true")
        if (!properties.containsKey("view-distance")) properties.setProperty("view-distance", "10")
        if (!properties.containsKey("simulation-distance")) properties.setProperty("simulation-distance", "10")
        if (!properties.containsKey("white-list")) properties.setProperty("white-list", "false")
        if (!properties.containsKey("enforce-secure-profile")) properties.setProperty("enforce-secure-profile", "false")

        FileOutputStream(propsFile).use { out ->
            properties.store(out, "Minecraft Java server properties - MineHost Managed")
        }

        // Verification
        require(propsFile.isFile && propsFile.length() > 0L) {
            "Failed to write server.properties for Java server"
        }

        val reloadedProps = Properties().apply {
            FileInputStream(propsFile).use { load(it) }
        }
        val storedPort = reloadedProps.getProperty("server-port")?.trim()?.toIntOrNull()
            ?: throw IOException("server.properties missing valid server-port")

        require(storedPort == port) {
            "Configuration mismatch: expected TCP port $port, got $storedPort in server.properties"
        }
    }
}
