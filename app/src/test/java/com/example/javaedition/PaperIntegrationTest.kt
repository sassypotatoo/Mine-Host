package com.example.javaedition

import com.example.server.engine.PaperEngine
import com.example.server.version.EngineRuntimeJavaPolicy
import com.example.server.version.EngineVersion
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.VersionSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaperIntegrationTest {

    @Test
    fun testPaperUserAgentFormatAndValidation() {
        val agent = PaperUserAgentProvider.buildUserAgent("1.0", "https://github.com/test-owner/test-repository")
        assertEquals("MineHost/1.0 (https://github.com/test-owner/test-repository)", agent)
        assertTrue(agent.startsWith("MineHost/"))
        assertFalse(agent.contains("example"))
        assertFalse(agent.contains("ai.studio"))
    }

    @Test
    fun testPaperHostTrustBoundary() {
        assertTrue(PaperUserAgentProvider.isTrustedPaperHost("papermc.io"))
        assertTrue(PaperUserAgentProvider.isTrustedPaperHost("fill.papermc.io"))
        assertTrue(PaperUserAgentProvider.isTrustedPaperHost("fill-data.papermc.io"))

        assertFalse(PaperUserAgentProvider.isTrustedPaperHost("evilpapermc.io"))
        assertFalse(PaperUserAgentProvider.isTrustedPaperHost("papermc.io.evil.com"))
        assertFalse(PaperUserAgentProvider.isTrustedPaperHost("example.com"))
    }

    @Test
    fun testPaperJavaRuntimePolicy() {
        assertEquals(17, PaperResolver.determineRequiredJavaMajor("1.19.4"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.20.4"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.21.4"))
        assertEquals(25, PaperResolver.determineRequiredJavaMajor("26.1"))
    }

    @Test
    fun testEngineRuntimeJavaPolicyForPaper() {
        val paperEngineVersion = EngineVersion(
            id = "java_paper_stable",
            engineId = "java_paper",
            versionName = "PaperMC",
            displayName = "PaperMC",
            channel = ReleaseChannel.STABLE,
            downloadUrl = "https://fill.papermc.io/v3/projects/paper",
            jarFileName = "paper.jar",
            requiredJavaVersion = 17,
            compatibilityLabel = "Java Edition",
            recommended = true,
            sourceType = VersionSourceType.PAPER_API,
            runtimeJavaVersion = 17,
            launchMode = LaunchMode.JAVA_JAR,
            mainClass = "org.bukkit.craftbukkit.Main",
            sha256 = null,
            artifactName = null,
            buildNumber = null,
            manifestMainClass = null,
            recommendedBedrockVersion = null,
            supportedBedrockVersions = emptyList()
        )

        assertEquals(17, EngineRuntimeJavaPolicy.requiredMajor(paperEngineVersion, "1.19.4"))
        assertEquals(21, EngineRuntimeJavaPolicy.requiredMajor(paperEngineVersion, "1.20.4"))
        assertEquals(25, EngineRuntimeJavaPolicy.requiredMajor(paperEngineVersion, "26.2"))
    }

    @Test
    fun testPaperJavaRuntimePolicyFailClosed() {
        assertEquals(17, PaperResolver.determineRequiredJavaMajor("1.19.4"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.20.4"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.21.11"))
        assertEquals(25, PaperResolver.determineRequiredJavaMajor("26.1"))

        val unmapped = runCatching { PaperResolver.determineRequiredJavaMajor("1.21.12") }
        assertTrue(unmapped.isFailure)
    }

    @Test
    fun testJavaVsBedrockServerPropertiesReadWrite() {
        val tempDir = java.nio.file.Files.createTempDirectory("minehost_props_test").toFile()
        try {
            val javaProfile = com.example.data.ServerProfile(
                id = "test-java-uuid",
                name = "Test Java Server",
                edition = com.example.data.ServerEdition.JAVA,
                engineId = "java_paper",
                engineVersionId = "java_paper_stable",
                bedrockVersion = "1.21.4",
                port = 25565,
                maxPlayers = 20,
                memoryMb = 2048,
                serverDirectory = tempDir.absolutePath,
                levelName = "world",
                worldSeed = 0L,
                worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
                worldSeedKnown = false,
                onlineMode = true
            )

            val service = com.example.data.LocalServerDataService(
                rootProvider = { tempDir },
                profileProvider = { javaProfile }
            )

            val writeRes = service.writeProperties(
                com.example.data.ServerSettingsState(
                    serverName = "Test Java Server",
                    gameMode = "survival",
                    difficulty = "normal",
                    maxPlayers = 20,
                    whitelistEnabled = false,
                    onlineMode = true,
                    viewDistance = 10,
                    port = 25565,
                    levelName = "world"
                )
            )
            assertTrue(writeRes.success)

            val propsFile = java.io.File(tempDir, "server.properties")
            assertTrue(propsFile.isFile)
            val text = propsFile.readText()
            assertTrue(text.contains("online-mode=true"))
            assertFalse(text.contains("xbox-auth"))

            val readSettings = service.readProperties()
            assertTrue(readSettings.onlineMode)
            assertEquals(25565, readSettings.port)

            // Override with online-mode=false and xbox-auth=on to verify Java online-mode priority
            propsFile.writeText(
                """
                server-port=25565
                max-players=20
                online-mode=false
                xbox-auth=on
                """.trimIndent()
            )

            val readSettingsOffline = service.readProperties()
            assertFalse(readSettingsOffline.onlineMode)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testPaperVersionDiscoveryFiltersUnsupportedVersions() {
        val mockJson = """
            {
                "versions": {
                    "26": ["26.2", "26.1"],
                    "1.21": ["1.21.11"],
                    "1.16": ["1.16.5"]
                }
            }
        """.trimIndent()

        val parsed = PaperResolver.parseAvailableVersions(mockJson)
        assertTrue(parsed.contains("26.2"))
        assertTrue(parsed.contains("26.1"))
        assertTrue(parsed.contains("1.21.11"))
        assertFalse(parsed.contains("1.16.5")) // 1.16.5 filtered out by runtime policy
    }

    @Test
    fun testPaperArtifactUrlValidation() {
        PaperResolver.validatePaperArtifactUrl("https://fill.papermc.io/v3/projects/paper/versions/1.21.4/builds/1/downloads/paper-1.21.4-1.jar")
        val HTTPUrl = runCatching { PaperResolver.validatePaperArtifactUrl("http://fill.papermc.io/test.jar") }
        assertTrue(HTTPUrl.isFailure)

        val untrustedHost = runCatching { PaperResolver.validatePaperArtifactUrl("https://untrusted.com/test.jar") }
        assertTrue(untrustedHost.isFailure)
    }

    @Test
    fun testJavaStatusJsonValidationStrictness() {
        val validJson = """
            {
                "version": { "name": "1.21.4", "protocol": 769 },
                "players": { "online": 0, "max": 20 },
                "description": "MineHost Paper Server"
            }
        """.trimIndent()

        val parsed = JavaStatusPingProbe.parseStatusJson(validJson)
        assertEquals("1.21.4", parsed.versionName)
        assertEquals(769, parsed.protocol)
        assertEquals(0, parsed.onlinePlayers)
        assertEquals(20, parsed.maxPlayers)

        // Description-only must fail
        val descriptionOnly = """{ "description": "MineHost" }"""
        val result = runCatching { JavaStatusPingProbe.parseStatusJson(descriptionOnly) }
        assertTrue(result.isFailure)
    }

    @Test
    fun testJavaPropertiesPreserveUnknownKeys() {
        val tempDir = java.nio.file.Files.createTempDirectory("minehost_props_preserve").toFile()
        try {
            val propsFile = java.io.File(tempDir, "server.properties")
            propsFile.writeText("custom-key=custom-value\nunknown-property=true\nxbox-auth=on\n")
            
            com.example.javaedition.JavaServerPropertiesAdapter.applyProperties(
                serverDir = tempDir,
                port = 25565,
                onlineMode = true
            )
            
            val text = propsFile.readText()
            assertTrue(text.contains("custom-key=custom-value"))
            assertTrue(text.contains("unknown-property=true"))
            assertFalse(text.contains("xbox-auth"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
