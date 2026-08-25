package com.example.server.engine

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineConfigAdapterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun nukkitMotWritesAndVerifiesFreshWorldConfigurationWithoutWorldAdapterFiles() {
        val serverDir = temporaryFolder.newFolder("nukkit-mot")
        val config = EngineServerConfig(
            port = 19132,
            levelName = "generated_world",
            worldSeed = 123456789L,
            worldSeedKnown = true,
            levelType = "DEFAULT",
            gameMode = "survival",
            difficulty = "normal",
            onlineMode = true,
        )

        NukkitMotAdapter().applyConfig(serverDir, config)

        val propertiesFile = File(serverDir, "server.properties")
        assertTrue(propertiesFile.isFile)
        val properties = Properties().apply {
            propertiesFile.inputStream().use(::load)
        }
        assertEquals("19132", properties.getProperty("server-port"))
        assertEquals("19132", properties.getProperty("server-portv6"))
        assertEquals("generated_world", properties.getProperty("level-name"))
        assertEquals("123456789", properties.getProperty("level-seed"))
        assertEquals("DEFAULT", properties.getProperty("level-type"))
        assertFalse(File(serverDir, "nukkit.yml").exists())
    }

    @Test
    fun existingNukkitYamlIsNotMutatedByStableCoreConfiguration() {
        val serverDir = temporaryFolder.newFolder("existing-nukkit-mot")
        val nukkitYaml = File(serverDir, "nukkit.yml")
        val original = "settings:\n  seed: 777\n"
        nukkitYaml.writeText(original)

        NukkitMotAdapter().applyConfig(
            serverDir,
            EngineServerConfig(
                port = 19132,
                levelName = "generated_world",
                worldSeed = 999L,
                worldSeedKnown = true,
                levelType = "DEFAULT",
                gameMode = "survival",
                difficulty = "normal",
                onlineMode = true,
            ),
        )

        assertEquals(original, nukkitYaml.readText())
    }
}
