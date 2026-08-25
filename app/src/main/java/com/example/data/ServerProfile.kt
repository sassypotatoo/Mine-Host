package com.example.data

import java.util.UUID
import org.json.JSONObject
import com.example.server.engine.WorldSeedMode
import com.example.server.version.ResolvedEngineVersion

enum class ServerEdition {
    BEDROCK,
    JAVA
}

enum class ServerNetworkType {
    BEDROCK_RAKNET_UDP,
    JAVA_TCP
}

fun ServerNetworkType.portTransport(): PortTransport = when (this) {
    ServerNetworkType.BEDROCK_RAKNET_UDP -> PortTransport.UDP
    ServerNetworkType.JAVA_TCP -> PortTransport.TCP
}

enum class PortTransport {
    UDP,
    TCP
}

data class ServerProfile(
    val id: String,
    val name: String,
    val engineId: String,
    val engineVersionId: String,
    val bedrockVersion: String,
    val serverDirectory: String,
    val levelName: String,
    val worldSeed: Long,
    val worldSeedMode: WorldSeedMode,
    val worldSeedKnown: Boolean,
    val gameMode: String = "0",
    val difficulty: String = "1",
    val levelType: String = "DEFAULT",
    val iconPath: String? = null,
    val port: Int = 19132,
    val memoryMb: Int = 1024,
    val maxPlayers: Int = 20,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val onlineMode: Boolean = true,
    val autoRestart: Boolean = true,
    val autoBackup: Boolean = false,
    val lastStartedSessionId: String? = null,
    val lastStartedAt: Long? = null,
    val edition: ServerEdition = ServerEdition.BEDROCK,
    val networkType: ServerNetworkType = ServerNetworkType.BEDROCK_RAKNET_UDP,
    val minecraftVersion: String? = null,
    val resolvedIdentity: ResolvedEngineVersion? = null,
    val minecraftEulaAccepted: Boolean = false,
) {
    fun toJson(): String {
        val obj = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("engineId", engineId)
            put("engineVersionId", engineVersionId)
            put("bedrockVersion", bedrockVersion)
            put("serverDirectory", serverDirectory)
            put("levelName", levelName)
            put("worldSeed", worldSeed)
            put("worldSeedMode", worldSeedMode.name)
            put("worldSeedKnown", worldSeedKnown)
            put("gameMode", gameMode)
            put("difficulty", difficulty)
            put("levelType", levelType)
            put("iconPath", iconPath)
            put("port", port)
            put("memoryMb", memoryMb)
            put("maxPlayers", maxPlayers)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("isFavorite", isFavorite)
            put("onlineMode", onlineMode)
            put("autoRestart", autoRestart)
            put("autoBackup", autoBackup)
            put("lastStartedSessionId", lastStartedSessionId)
            put("lastStartedAt", lastStartedAt ?: -1L)
            put("edition", edition.name)
            put("networkType", networkType.name)
            put("minecraftVersion", minecraftVersion)
            put("minecraftEulaAccepted", minecraftEulaAccepted)
            resolvedIdentity?.let { identity ->
                put("resolvedIdentity", JSONObject().apply {
                    put("resolvedBuildNumber", identity.resolvedBuildNumber)
                    put("sourceRepository", identity.sourceRepository)
                    put("generatorId", identity.generatorId)
                    put("generatorRevision", identity.generatorRevision)
                    put("resolvedAt", identity.resolvedAt)
                })
            }
        }
        return obj.toString(2)
    }

    companion object {
        const val MIN_MEMORY_MB = 384
        const val MAX_MEMORY_MB = 4096

        fun createDraft(
            name: String,
            engineId: String,
            engineVersionId: String,
            bedrockVersion: String,
            serverDirectory: String,
            worldSeed: Long,
            worldSeedMode: WorldSeedMode,
            worldSeedKnown: Boolean,
            levelName: String = "world",
            gameMode: String = "0",
            difficulty: String = "1",
            levelType: String = "DEFAULT",
            port: Int = 19132,
            memoryMb: Int = 600,
            maxPlayers: Int = 10,
            iconPath: String? = null,
            onlineMode: Boolean = true,
            autoRestart: Boolean = true,
            autoBackup: Boolean = false,
            edition: ServerEdition = ServerEdition.BEDROCK,
            networkType: ServerNetworkType = ServerNetworkType.BEDROCK_RAKNET_UDP,
            minecraftVersion: String? = null,
            minecraftEulaAccepted: Boolean = false,
        ): ServerProfile {
            val now = System.currentTimeMillis()
            return ServerProfile(
                id = UUID.randomUUID().toString(),
                name = name,
                engineId = engineId,
                engineVersionId = engineVersionId,
                bedrockVersion = bedrockVersion,
                serverDirectory = serverDirectory,
                levelName = levelName,
                worldSeed = worldSeed,
                worldSeedMode = worldSeedMode,
                worldSeedKnown = worldSeedKnown,
                gameMode = gameMode,
                difficulty = difficulty,
                levelType = levelType,
                iconPath = iconPath,
                port = port,
                memoryMb = memoryMb.coerceIn(MIN_MEMORY_MB, MAX_MEMORY_MB),
                maxPlayers = maxPlayers,
                createdAt = now,
                updatedAt = now,
                onlineMode = onlineMode,
                autoRestart = autoRestart,
                autoBackup = autoBackup,
                edition = edition,
                networkType = networkType,
                minecraftVersion = minecraftVersion,
                minecraftEulaAccepted = minecraftEulaAccepted,
            )
        }
    }
}

data class ServerProfileChanges(
    val name: String? = null,
    val engineId: String? = null,
    val engineVersionId: String? = null,
    val bedrockVersion: String? = null,
    val levelName: String? = null,
    val worldSeed: Long? = null,
    val worldSeedMode: WorldSeedMode? = null,
    val worldSeedKnown: Boolean? = null,
    val gameMode: String? = null,
    val difficulty: String? = null,
    val levelType: String? = null,
    val iconPath: String? = null,
    val port: Int? = null,
    val memoryMb: Int? = null,
    val maxPlayers: Int? = null,
    val isFavorite: Boolean? = null,
    val onlineMode: Boolean? = null,
    val autoRestart: Boolean? = null,
    val autoBackup: Boolean? = null,
    val lastStartedSessionId: String? = null,
    val lastStartedAt: Long? = null,
    val edition: ServerEdition? = null,
    val networkType: ServerNetworkType? = null,
    val minecraftVersion: String? = null,
    val resolvedIdentity: ResolvedEngineVersion? = null,
    val minecraftEulaAccepted: Boolean? = null,
)
