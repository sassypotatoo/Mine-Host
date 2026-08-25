package com.example.server.engine

import java.io.File
import java.io.IOException
import java.util.Properties

data class EngineWorldGenerationCapabilities(
    val supportsDefault: Boolean,
    val supportsFlat: Boolean,
    val levelTypeProperty: String?,
    val defaultValue: String?,
    val flatValue: String?,
    val evidenceRepository: String,
    val evidenceRevision: String,
    val evidenceFile: String,
    val evidenceNote: String
)

interface EngineConfigAdapter {
    val capabilities: EngineWorldGenerationCapabilities

    fun applyConfig(serverDir: File, config: EngineServerConfig)
}

/**
 * Every currently supported engine is Nukkit-family and reads
 * server.properties. Keeping first-start configuration here avoids creating
 * guessed YAML files that can prevent an engine from booting.
 */
private fun applyNukkitProperties(
    serverDir: File,
    config: EngineServerConfig,
    capabilities: EngineWorldGenerationCapabilities
) {
    if (config.port !in 1..65535) {
        throw IOException("Invalid server port: ${config.port}")
    }
    if (!serverDir.exists() && !serverDir.mkdirs()) {
        throw IOException("Could not create server directory: ${serverDir.absolutePath}")
    }

    if (config.levelType.equals("FLAT", ignoreCase = true) && !capabilities.supportsFlat) {
        throw IOException("Flat world generation is not supported by this engine")
    }

    val propertiesFile = File(serverDir, "server.properties")
    val properties = Properties()
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use { properties.load(it) }
    }

    properties.setProperty("server-ip", "0.0.0.0")
    properties.setProperty("server-port", config.port.toString())
    properties.setProperty("server-portv6", config.port.toString())
    properties.setProperty("xbox-auth", if (config.onlineMode) "on" else "off")
    properties.setProperty("level-name", config.levelName)
    if (config.worldSeedKnown) {
        properties.setProperty("level-seed", config.worldSeed.toString())
    }
    val expectedLevelType = if (config.levelType.equals("FLAT", ignoreCase = true)) capabilities.flatValue else capabilities.defaultValue
    if (capabilities.levelTypeProperty != null && expectedLevelType != null) {
        properties.setProperty(capabilities.levelTypeProperty, expectedLevelType)
    }
    properties.setProperty("gamemode", config.gameMode)
    properties.setProperty("difficulty", config.difficulty)

    val temporaryFile = File(serverDir, "server.properties.minehost.tmp")
    temporaryFile.outputStream().use { output ->
        properties.store(output, "MineHost server configuration")
        output.flush()
    }

    if (!temporaryFile.renameTo(propertiesFile)) {
        temporaryFile.copyTo(propertiesFile, overwrite = true)
        if (!temporaryFile.delete()) {
            temporaryFile.deleteOnExit()
        }
    }

    // Readback verification: require exact equality
    val readBack = Properties().apply {
        propertiesFile.inputStream().use(::load)
    }

    val readPort = readBack.getProperty("server-port")?.trim()
    require(readPort == config.port.toString()) {
        "Configuration readback mismatch for server-port: expected ${config.port}, got $readPort"
    }

    val readPortV6 = readBack.getProperty("server-portv6")?.trim()
    if (readPortV6 != null) {
        require(readPortV6 == config.port.toString()) {
            "Configuration readback mismatch for server-portv6: expected ${config.port}, got $readPortV6"
        }
    }

    val readLevelName = readBack.getProperty("level-name")?.trim()
    require(readLevelName == config.levelName) {
        "Configuration readback mismatch for level-name: expected ${config.levelName}, got $readLevelName"
    }

    if (config.worldSeedKnown) {
        val readSeed = readBack.getProperty("level-seed")?.trim()
        require(readSeed == config.worldSeed.toString()) {
            "Configuration readback mismatch for level-seed: expected ${config.worldSeed}, got $readSeed"
        }
    }

    if (capabilities.levelTypeProperty != null && expectedLevelType != null) {
        val readLevelType = readBack.getProperty(capabilities.levelTypeProperty)?.trim()
        require(readLevelType != null && readLevelType.equals(expectedLevelType, ignoreCase = true)) {
            "Configuration readback mismatch for ${capabilities.levelTypeProperty}: expected $expectedLevelType, got $readLevelType"
        }
    }

    val readGamemode = readBack.getProperty("gamemode")?.trim()
    require(readGamemode == config.gameMode) {
        "Configuration readback mismatch for gamemode: expected ${config.gameMode}, got $readGamemode"
    }

    val readDifficulty = readBack.getProperty("difficulty")?.trim()
    require(readDifficulty == config.difficulty) {
        "Configuration readback mismatch for difficulty: expected ${config.difficulty}, got $readDifficulty"
    }
}

class PowerNukkitXAdapter : EngineConfigAdapter {
    override val capabilities = EngineWorldGenerationCapabilities(
        supportsDefault = true,
        supportsFlat = true,
        levelTypeProperty = "level-type",
        defaultValue = "DEFAULT",
        flatValue = "FLAT",
        evidenceRepository = "https://github.com/PowerNukkitX/PowerNukkitX",
        evidenceRevision = "2.0.0",
        evidenceFile = "cn/nukkit/level/generator/Generator.java",
        evidenceNote = "PowerNukkitX registers DEFAULT and FLAT level types in Generator.java"
    )

    override fun applyConfig(serverDir: File, config: EngineServerConfig) {
        applyNukkitProperties(serverDir, config, capabilities)
    }
}

class NukkitAdapter : EngineConfigAdapter {
    override val capabilities = EngineWorldGenerationCapabilities(
        supportsDefault = true,
        supportsFlat = true,
        levelTypeProperty = "level-type",
        defaultValue = "DEFAULT",
        flatValue = "FLAT",
        evidenceRepository = "https://github.com/PowerNukkit/PowerNukkit",
        evidenceRevision = "v1.5.2.1-PN",
        evidenceFile = "cn/nukkit/level/generator/Generator.java",
        evidenceNote = "PowerNukkit registers DEFAULT and FLAT level types in Generator.java"
    )

    override fun applyConfig(serverDir: File, config: EngineServerConfig) {
        applyNukkitProperties(serverDir, config, capabilities)
    }
}

class PM1EAdapter : EngineConfigAdapter {
    override val capabilities = EngineWorldGenerationCapabilities(
        supportsDefault = true,
        supportsFlat = true,
        levelTypeProperty = "level-type",
        defaultValue = "DEFAULT",
        flatValue = "FLAT",
        evidenceRepository = "https://github.com/PetteriM1/NukkitPetteriM1Edition",
        evidenceRevision = "4437",
        evidenceFile = "cn/nukkit/level/generator/Generator.java",
        evidenceNote = "PM1E registers DEFAULT and FLAT level types in Generator.java"
    )

    override fun applyConfig(serverDir: File, config: EngineServerConfig) {
        applyNukkitProperties(serverDir, config, capabilities)
    }
}

class CloudburstAdapter : EngineConfigAdapter {
    override val capabilities = EngineWorldGenerationCapabilities(
        supportsDefault = true,
        supportsFlat = true,
        levelTypeProperty = "level-type",
        defaultValue = "DEFAULT",
        flatValue = "FLAT",
        evidenceRepository = "https://github.com/CloudburstMC/Nukkit",
        evidenceRevision = "1106",
        evidenceFile = "cn/nukkit/level/generator/Generator.java",
        evidenceNote = "Cloudburst registers DEFAULT and FLAT level types in Generator.java"
    )

    override fun applyConfig(serverDir: File, config: EngineServerConfig) {
        applyNukkitProperties(serverDir, config, capabilities)
    }
}

class NukkitMotAdapter : EngineConfigAdapter {
    override val capabilities = EngineWorldGenerationCapabilities(
        supportsDefault = true,
        supportsFlat = true,
        levelTypeProperty = "level-type",
        defaultValue = "DEFAULT",
        flatValue = "FLAT",
        evidenceRepository = "https://github.com/Nukkit-MOT/Nukkit-MOT",
        evidenceRevision = "1361",
        evidenceFile = "cn/nukkit/level/generator/Generator.java",
        evidenceNote = "Nukkit-MOT master branch registers DEFAULT and FLAT level types in Generator.java"
    )

    override fun applyConfig(serverDir: File, config: EngineServerConfig) {
        // Fresh Nukkit-MOT worlds use the same verified server.properties
        // contract as the other Nukkit-family engines. Do not call into the
        // postponed imported-world adapter from the stable Bedrock baseline.
        applyNukkitProperties(serverDir, config, capabilities)
    }
}

class PaperAdapter : EngineConfigAdapter {
    override val capabilities = EngineWorldGenerationCapabilities(
        supportsDefault = true,
        supportsFlat = true,
        levelTypeProperty = "level-type",
        defaultValue = "minecraft:normal",
        flatValue = "minecraft:flat",
        evidenceRepository = "https://github.com/PaperMC/Paper",
        evidenceRevision = "master",
        evidenceFile = "server.properties",
        evidenceNote = "Paper uses standard Java Edition level-type format."
    )

    override fun applyConfig(serverDir: File, config: EngineServerConfig) {
        com.example.javaedition.JavaServerPropertiesAdapter.applyProperties(
            serverDir = serverDir,
            port = config.port,
            onlineMode = config.onlineMode,
            levelName = config.levelName,
            gameMode = config.gameMode,
            difficulty = config.difficulty,
            maxPlayers = config.maxPlayers,
            motd = config.motd
        )
    }
}

object ConfigAdapterFactory {
    fun getAdapter(family: String): EngineConfigAdapter {
        return when (family) {
            "PowerNukkitX", "bedrock_power_nukkit_x" -> PowerNukkitXAdapter()
            "PowerNukkit", "bedrock_power_nukkit" -> NukkitAdapter()
            "PM1E", "bedrock_nukkit" -> PM1EAdapter()
            "Cloudburst", "bedrock_cloudburst_nukkit" -> CloudburstAdapter()
            "Nukkit-MOT", "nukkit-mot" -> NukkitMotAdapter()
            "Paper", "java_paper" -> PaperAdapter()
            else -> error("No engine configuration adapter registered for $family")
        }
    }
}
