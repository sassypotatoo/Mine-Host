package com.example.server.engine

enum class WorldSeedMode {
    RANDOM,
    CUSTOM
}

data class EngineServerConfig(
    val port: Int = 19132,
    val onlineMode: Boolean = true,
    val levelName: String = "world",
    val worldSeed: Long,
    val worldSeedKnown: Boolean,
    val gameMode: String = "0",
    val difficulty: String = "1",
    val levelType: String = "DEFAULT",
    val maxPlayers: Int = 10,
    val motd: String = "A MineHost Java Server"
)
