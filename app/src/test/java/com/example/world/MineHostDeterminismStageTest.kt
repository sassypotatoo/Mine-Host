package com.example.world

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ServerCreationDraft
import com.example.data.ServerProfile
import com.example.data.ServerProfileRepository
import com.example.server.engine.EngineConfigAdapter
import com.example.server.engine.EngineServerConfig
import com.example.server.engine.WorldSeedMode
import com.example.server.version.EngineVersion
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.VersionSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MineHostDeterminismStageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var serverDir: File
    private lateinit var engineVersion: EngineVersion

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        serverDir = tempFolder.newFolder("server")
        engineVersion = EngineVersion(
            id = "v1361",
            engineId = "nukkit-mot",
            versionName = "1.0.0",
            displayName = "Nukkit-MOT 1361",
            channel = ReleaseChannel.STABLE,
            downloadUrl = "https://example.com/engine.jar",
            jarFileName = "nukkit.jar",
            requiredJavaVersion = 17,
            compatibilityLabel = "1.20.0",
            recommended = true,
            sourceType = VersionSourceType.JENKINS_BUILD,
            launchMode = LaunchMode.JAVA_JAR,
            sha256 = "f".repeat(64),
            buildNumber = "1361"
        )
    }

    @Test
    fun testStrictWorldSeedParserInjectionAndControlCharacters() {
        assertTrue(WorldSeedParser.parse("123456789") is SeedParseResult.Valid)
        assertEquals(123456789L, (WorldSeedParser.parse("123456789") as SeedParseResult.Valid).value)

        // Ordinary spaces trimmed
        assertTrue(WorldSeedParser.parse("  987654  ") is SeedParseResult.Valid)
        assertEquals(987654L, (WorldSeedParser.parse("  987654  ") as SeedParseResult.Valid).value)

        // Injection / Control character rejections
        assertTrue(WorldSeedParser.parse("\n12345\n") is SeedParseResult.Invalid)
        assertTrue(WorldSeedParser.parse("12345\r") is SeedParseResult.Invalid)
        assertTrue(WorldSeedParser.parse("\t12345") is SeedParseResult.Invalid)
        assertTrue(WorldSeedParser.parse("12345\u0000") is SeedParseResult.Invalid)
        assertTrue(WorldSeedParser.parse("12345=999") is SeedParseResult.Invalid)
        assertTrue(WorldSeedParser.parse("12345;level-seed=0") is SeedParseResult.Invalid)
    }

    @Test
    fun testWorldNameValidationAndPathSecurity() {
        val valid = WorldGenerationMarkerManager.validateWorldName(serverDir, "  my_world_123  ")
        assertTrue(valid.isSuccess)
        assertEquals("my_world_123", valid.getOrThrow())

        assertTrue(WorldGenerationMarkerManager.validateWorldName(serverDir, "").isFailure)
        assertTrue(WorldGenerationMarkerManager.validateWorldName(serverDir, "world/sub").isFailure)
        assertTrue(WorldGenerationMarkerManager.validateWorldName(serverDir, "world\\sub").isFailure)
        assertTrue(WorldGenerationMarkerManager.validateWorldName(serverDir, "..").isFailure)
        assertTrue(WorldGenerationMarkerManager.validateWorldName(serverDir, ".").isFailure)
    }

    @Test
    fun testPerWorldMarkerWriteReadAndVerification() {
        val worldName = "test_world"
        val seed = 424242L
        val genId = "nukkit-mot/normal"
        val genRev = "1361"

        val written = WorldGenerationMarkerManager.writeMarker(
            serverDir = serverDir,
            worldName = worldName,
            seed = seed,
            seedKnown = true,
            engineId = "nukkit-mot",
            engineVersionId = "v1361",
            generatorId = genId,
            generatorRevision = genRev
        )
        assertTrue(written)

        val readRes = WorldGenerationMarkerManager.readMarkerResult(serverDir, worldName)
        assertTrue(readRes is MarkerReadResult.Valid)
        val marker = (readRes as MarkerReadResult.Valid).marker
        assertEquals(worldName, marker.worldName)
        assertEquals(seed, marker.seed)
        assertEquals(genId, marker.generatorId)
        assertEquals(genRev, marker.generatorRevision)

        // Verify exact match
        assertTrue(
            WorldGenerationMarkerManager.verifyExact(
                serverDir = serverDir,
                worldName = worldName,
                seed = seed,
                seedKnown = true,
                engineId = "nukkit-mot",
                engineVersionId = "v1361",
                generatorId = genId,
                generatorRevision = genRev
            )
        )

        // Verify mismatch detection
        assertFalse(
            WorldGenerationMarkerManager.verifyExact(
                serverDir = serverDir,
                worldName = worldName,
                seed = 999999L,
                seedKnown = true,
                engineId = "nukkit-mot",
                engineVersionId = "v1361",
                generatorId = genId,
                generatorRevision = genRev
            )
        )
    }

    @Test
    fun testMissingMarkerBlocksExistingTerrain() {
        val worldName = "unmarked_world"
        val worldDir = File(serverDir, "worlds/$worldName")
        val dbDir = File(worldDir, "db")
        dbDir.mkdirs()
        File(worldDir, "level.dat").writeText("fake level.dat")

        val genInput = GenerationIdentityInput(
            levelName = worldName,
            worldSeed = 100L,
            worldSeedKnown = true,
            engineId = "nukkit-mot",
            engineVersionId = "v1361",
            generatorId = "nukkit-mot/normal",
            generatorRevision = "1361"
        )

        val result = WorldGenerationMarkerManager.verifyOrBlock(
            serverDir = serverDir,
            input = genInput,
            engineVersion = engineVersion
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("generation identity marker is missing") == true)
    }

    @Test
    fun testDynamicGeneratorIdentityResolver() {
        val defaultGen = EngineGeneratorIdentityResolver.resolve("nukkit-mot", engineVersion, "DEFAULT")
        assertEquals("nukkit-mot/normal", defaultGen.id)
        assertEquals("1361", defaultGen.revision)

        val flatGen = EngineGeneratorIdentityResolver.resolve("bedrock_power_nukkit_x", engineVersion, "FLAT")
        assertEquals("powernukkitx/flat", flatGen.id)
        assertEquals("1361", flatGen.revision)
    }

    @Test
    fun testExplicitSeedInServerCreationDraft() {
        val draft = ServerCreationDraft(
            name = "Test Server",
            engineId = "nukkit-mot",
            engineVersionId = "v1361",
            bedrockVersion = "1.20.0",
            worldSeed = 777L,
            worldSeedMode = WorldSeedMode.CUSTOM,
            worldSeedKnown = true
        )
        assertEquals(777L, draft.worldSeed)
        assertEquals(WorldSeedMode.CUSTOM, draft.worldSeedMode)
        assertTrue(draft.worldSeedKnown)
    }
}
