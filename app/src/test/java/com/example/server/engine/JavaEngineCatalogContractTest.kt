package com.example.server.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.ServerFactory
import com.example.server.template.TemplateRegistry
import com.example.server.version.EngineVersion
import com.example.server.version.ReleaseChannel
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JavaEngineCatalogContractTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun vanillaAndFabricSpecsResolveWithCatalogJarNames() {
        val vanilla = EngineCatalog.getSpec("java_vanilla")
        assertNotNull(vanilla)
        assertEquals("server.jar", vanilla?.jarName)
        assertEquals(25565, vanilla?.defaultPort)

        val fabric = EngineCatalog.getSpec("java_fabric")
        assertNotNull(fabric)
        assertEquals("fabric-server-launch.jar", fabric?.jarName)
        assertEquals(25565, fabric?.defaultPort)

        assertTrue(EngineCatalog.ALL_ENGINES.any { it.id == "java_vanilla" })
        assertTrue(EngineCatalog.ALL_ENGINES.any { it.id == "java_fabric" })
    }

    @Test
    fun factoryRoutesVanillaAndFabricAdapters() {
        assertTrue(ConfigAdapterFactory.getAdapter("java_vanilla") is VanillaAdapter)
        assertTrue(ConfigAdapterFactory.getAdapter("java_fabric") is FabricAdapter)
        assertTrue(ConfigAdapterFactory.getAdapter("Vanilla") is VanillaAdapter)
        assertTrue(ConfigAdapterFactory.getAdapter("Fabric") is FabricAdapter)
    }

    @Test
    fun vanillaAndFabricAdaptersWriteStandardServerProperties() {
        listOf("vanilla" to VanillaAdapter(), "fabric" to FabricAdapter()).forEach { (name, adapter) ->
            val serverDir = temporaryFolder.newFolder(name)
            adapter.applyConfig(
                serverDir,
                EngineServerConfig(
                    port = 25565,
                    levelName = "${name}_world",
                    worldSeed = 42L,
                    worldSeedKnown = true,
                    levelType = "DEFAULT",
                    gameMode = "survival",
                    difficulty = "normal",
                    onlineMode = true,
                )
            )

            val propertiesFile = File(serverDir, "server.properties")
            assertTrue(propertiesFile.isFile)
            val properties = Properties().apply {
                propertiesFile.inputStream().use(::load)
            }
            assertEquals("25565", properties.getProperty("server-port"))
            assertEquals("${name}_world", properties.getProperty("level-name"))
            assertEquals("true", properties.getProperty("online-mode"))
        }
    }

    private fun engineVersionFor(template: com.example.server.template.ServerTemplate): EngineVersion =
        EngineVersion(
            id = "${template.id}:test",
            engineId = template.id,
            versionName = "${template.name} test",
            displayName = "${template.name} test",
            channel = ReleaseChannel.STABLE,
            downloadUrl = "https://example.invalid/${template.id}.jar",
            jarFileName = EngineCatalog.getSpec(template.id)!!.jarName,
            requiredJavaVersion = 25,
            compatibilityLabel = "Test",
            recommended = false,
            recommendedBedrockVersion = "26.2"
        )

    @Test
    fun serverFactoryConstructsVanillaEngine() {
        val engine = ServerFactory.createEngine(
            context = context,
            serverDir = temporaryFolder.newFolder("factory-vanilla"),
            template = TemplateRegistry.JAVA_VANILLA,
            engineVersion = engineVersionFor(TemplateRegistry.JAVA_VANILLA),
            bedrockVersion = "26.2",
            port = 25565,
            profileId = "profile-test",
            runtimeSessionId = "session-test",
            serverConfig = EngineServerConfig(port = 25565, worldSeed = 0L, worldSeedKnown = false),
            onLog = {},
            onStatusChange = {}
        )
        assertTrue(engine is com.example.server.engine.VanillaEngine)
        assertEquals("java_vanilla", engine.getEngineId())
    }

    @Test
    fun serverFactoryConstructsFabricEngine() {
        val engine = ServerFactory.createEngine(
            context = context,
            serverDir = temporaryFolder.newFolder("factory-fabric"),
            template = TemplateRegistry.JAVA_FABRIC,
            engineVersion = engineVersionFor(TemplateRegistry.JAVA_FABRIC),
            bedrockVersion = "26.2",
            port = 25565,
            profileId = "profile-test",
            runtimeSessionId = "session-test",
            serverConfig = EngineServerConfig(port = 25565, worldSeed = 0L, worldSeedKnown = false),
            onLog = {},
            onStatusChange = {}
        )
        assertTrue(engine is com.example.server.engine.FabricEngine)
        assertEquals("java_fabric", engine.getEngineId())
    }
}
