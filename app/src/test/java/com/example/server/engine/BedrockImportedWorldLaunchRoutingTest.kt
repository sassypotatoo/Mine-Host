package com.example.server.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.version.EngineVersion
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.VersionSourceType
import com.example.world.BlockPaletteInformation
import com.example.world.BedrockWorldInspectionResult
import com.example.world.ImportedPlayerData
import com.example.world.ImportedWorldVerificationStore
import com.example.world.WorldCompatibilityReport
import com.example.world.WorldFileIntegrity
import com.example.world.WorldLaunchOwnership
import com.example.world.WorldLaunchOwnershipPolicy
import com.example.world.WorldWorkingCopyManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BedrockImportedWorldLaunchRoutingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var serverDir: File
    private lateinit var catalogEngineVersion: EngineVersion

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        serverDir = tempFolder.newFolder("server-dir")

        catalogEngineVersion = EngineVersion(
            id = "nukkit-mot:1361",
            engineId = "nukkit-mot",
            versionName = "Nukkit-MOT #1361",
            displayName = "Nukkit-MOT Build 1361",
            channel = ReleaseChannel.STABLE,
            downloadUrl = "https://motci.cn/job/Nukkit-MOT/job/master/1361/artifact/target/Nukkit-MOT-SNAPSHOT.jar",
            jarFileName = "nukkit-mot.jar",
            requiredJavaVersion = 21,
            compatibilityLabel = "Bedrock 1.26.30",
            recommended = true,
            sourceType = VersionSourceType.JENKINS_BUILD,
            launchMode = LaunchMode.JAVA_JAR,
            protocolVersions = listOf(1001),
        )
    }

    private fun newEngine(): NukkitMOTEngine =
        NukkitMOTEngine(
            context = context,
            serverDir = serverDir,
            engineVersion = catalogEngineVersion,
            selectedBedrockVersion = "1.26.30",
            port = 19132,
            profileId = "test-profile",
            runtimeSessionId = "test-session",
        )

    private fun writeUndecodableActiveWorld(name: String): File {
        val world = File(serverDir, "worlds/$name").apply { mkdirs() }
        File(world, "level.dat").writeText("fixture-level-dat-not-real-nbt")
        File(world, "db").mkdirs()
        File(serverDir, "server.properties").writeText("level-name=$name\n")
        return world
    }

    private val launchHook: java.lang.reflect.Method by lazy {
        BedrockJavaEngineBase::class.java
            .getDeclaredMethod(
                "onPrepareWorldAndLaunchJar",
                File::class.java,
                kotlin.coroutines.Continuation::class.java,
            )
            .apply { isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeProtectedLaunch(engine: NukkitMOTEngine, serverJar: File): File =
        kotlin.coroutines.suspendCoroutine { continuation ->
            try {
                when (val raw = launchHook.invoke(engine, serverJar, continuation)) {
                    kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED -> Unit
                    else -> continuation.resumeWith(Result.success(raw as File))
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause ?: e
                continuation.resumeWith(Result.failure(cause))
            }
        }

    @Test
    fun untrackedWorldIsRefusedUntilItIsAdoptedThroughProtectedImport() {
        writeUndecodableActiveWorld("untracked")
        val engine = newEngine()

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                invokeProtectedLaunch(engine, File(serverDir, "nukkit-mot.jar"))
            }
        }
        assertEquals(
            WorldLaunchOwnership.EXTERNAL_ADOPTION_REQUIRED,
            WorldLaunchOwnershipPolicy.classify(serverDir, "untracked").ownership,
        )
        assertTrue(error.message.orEmpty().contains("must be adopted"))
    }

    @Test
    fun protectedImportedWorldEntersTheCompatibilityPipelineAndFailsClosedOnUndecodableData() {
        val worldDir = writeUndecodableActiveWorld("imported")
        val manager = WorldWorkingCopyManager(serverDir)
        val sourceHash = WorldFileIntegrity.fingerprint(worldDir).rootHash
        manager.protectImportedWorld(
            worldName = "imported",
            installedWorld = worldDir,
            inspection = fakeInspection(sourceHash),
            importTransactionId = "tx-routing-test",
        )
        assertTrue(
            ImportedWorldVerificationStore.markPending(
                serverRoot = serverDir,
                worldName = "imported",
                metadata = null,
                sourceWorldHash = sourceHash,
                transactionId = "tx-routing-test",
            )
        )

        assertEquals(
            WorldLaunchOwnership.IMPORTED_PROTECTED_VALID,
            WorldLaunchOwnershipPolicy.classify(serverDir, "imported").ownership,
        )

        val engine = newEngine()
        val launchJar = File(serverDir, "nukkit-mot.jar").apply { writeText("engine jar placeholder") }

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                invokeProtectedLaunch(engine, launchJar)
            }
        }
        assertTrue(error.message.orEmpty().contains("cannot be safely launched"))
    }

    private fun fakeInspection(hash: String): BedrockWorldInspectionResult = BedrockWorldInspectionResult(
        report = WorldCompatibilityReport(
            worldName = "fixture",
            sourceWorldHash = hash,
            totalFiles = 2,
            totalBytes = 1,
            lastOpenedMinecraftVersion = "1.26.33.1.0",
            storageVersion = 10,
            networkVersion = 975,
            chunkSerializerVersions = mapOf(42 to 1),
            minimumChunkSerializerVersion = 42,
            maximumChunkSerializerVersion = 42,
            chunkCountByDimension = mapOf(0 to 1),
            dimensionsPresent = setOf(0),
            worldSpawn = null,
            localPlayer = ImportedPlayerData(
                available = false,
                recordSha256 = null,
                position = null,
                rotation = null,
                dimensionId = null,
                gameMode = null,
                selectedHotbarSlot = null,
                experienceLevel = null,
                experienceProgress = null,
                inventory = emptyList(),
                armor = emptyList(),
                offhand = emptyList(),
                enderChest = emptyList(),
                rawKeys = emptySet(),
            ),
            blockPalette = BlockPaletteInformation(
                subchunkFormatVersions = emptyMap(),
                paletteVersions = emptyMap(),
                blockNameCounts = emptyMap(),
                uniqueBlockStateCount = 0,
                paletteEntryCount = 0,
                parseErrors = emptyList(),
            ),
            blockEntityIdentifiers = emptyMap(),
            actorEntityIdentifiers = emptyMap(),
            behaviorPacks = emptyList(),
            resourcePacks = emptyList(),
            unknownChunkRecordTypes = emptyMap(),
            levelDbTableErrors = emptyList(),
            activeLevelDbLogs = emptyList(),
            warnings = emptyList(),
            errors = emptyList(),
            sections = emptyMap(),
        ),
        localPlayerRecord = null,
    )
}
