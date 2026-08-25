package com.example.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ServerEdition
import com.example.data.ServerProfile
import com.example.server.engine.PaperEngine
import com.example.server.version.EngineVersion
import com.example.server.version.EngineVersionCatalogRepository
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.ResolvedEngineVersion
import com.example.server.version.VersionSourceType
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ServerManagerTest {

    @get:Rule
    val mainDispatcherRule = com.example.testutil.MainDispatcherRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testPaperStartsWithoutNetworkIfValid() = runBlocking {
        val engine = createPaperEngine()
        val cacheDir = Downloader.getCacheDir(context, engine.engineId, engine.id)
        cacheDir.mkdirs()

        val cacheFile = File(cacheDir, engine.jarFileName)
        java.util.zip.ZipOutputStream(cacheFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("org/bukkit/craftbukkit/Main.class"))
            val randomBytes = ByteArray(10240)
            java.util.Random().nextBytes(randomBytes)
            zos.write(randomBytes)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\nMain-Class: org.bukkit.craftbukkit.Main\n".toByteArray())
            zos.closeEntry()
        }

        val actualSha = Downloader.sha256(cacheFile)
        val actualSize = cacheFile.length()

        val identity = ResolvedEngineVersion(
            catalogBaseId = engine.id,
            effectiveVersionId = "java_paper:1.21.4:100",
            engineId = engine.engineId,
            resolvedBuildNumber = 100,
            resolvedArtifactUrl = "https://fill.papermc.io/test.jar",
            artifactSize = actualSize,
            sourceRepository = "https://github.com/PaperMC/Paper",
            sourceRevision = "main",
            resolvedAt = System.currentTimeMillis(),
            generatorId = "paper/v3",
            generatorRevision = "v1"
        )

        val committed = Downloader.writeResolvedCacheMetadata(
            context = context,
            version = engine,
            cacheFile = cacheFile,
            identity = identity,
            actualSha256 = actualSha!!
        )
        assertTrue(committed)

        val destDir = File.createTempFile("minehost-dest-", "").apply {
            delete()
            mkdirs()
        }
        val destinationJar = File(destDir, "paper.jar")

        try {
            val result = Downloader.downloadServerJar(
                context = context,
                version = engine,
                destination = destinationJar,
                minecraftVersion = "1.21.4",
                onProgress = {}
            )

            assertTrue(result is ServerJarDownloadResult.Success)
            assertEquals(100, (result as ServerJarDownloadResult.Success).identity?.resolvedBuildNumber)
            assertTrue(destinationJar.exists())
            assertEquals(actualSize, destinationJar.length())
        } finally {
            destDir.deleteRecursively()
        }
    }

    @Test
    fun testEulaAuthoritativeConsent() = runBlocking {
        val tempDir = File.createTempFile("minehost-eula-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val engineVersion = createPaperEngine()

            // CASE A: profile.minecraftEulaAccepted = false, but eula.txt has eula=true -> Preflight MUST throw
            val eulaFile = File(tempDir, "eula.txt")
            eulaFile.writeText("eula=true\n")

            val paperEngineOffline = PaperEngine(
                context = context,
                serverDir = tempDir,
                engineVersion = engineVersion,
                minecraftVersion = "1.21.4",
                port = 25565,
                profileId = "test-uuid",
                runtimeSessionId = "session-1",
                serverConfig = com.example.server.engine.EngineServerConfig(worldSeed = 0L, worldSeedKnown = false),
                minecraftEulaAccepted = false,
                onLog = {},
                onStatusChange = {}
            )

            val caseAResult = runCatching { paperEngineOffline.onPreflightCheck() }
            assertTrue(caseAResult.isFailure)
            assertTrue(caseAResult.exceptionOrNull() is IllegalStateException)

            // CASE B: profile.minecraftEulaAccepted = true, eula.txt absent -> eula.txt written with eula=true
            eulaFile.delete()
            val paperEngineAccepted = PaperEngine(
                context = context,
                serverDir = tempDir,
                engineVersion = engineVersion,
                minecraftVersion = "1.21.4",
                port = 25565,
                profileId = "test-uuid",
                runtimeSessionId = "session-1",
                serverConfig = com.example.server.engine.EngineServerConfig(worldSeed = 0L, worldSeedKnown = false),
                minecraftEulaAccepted = true,
                onLog = {},
                onStatusChange = {}
            )

            paperEngineAccepted.onPreflightCheck()
            assertTrue(eulaFile.isFile)
            assertTrue(eulaFile.readText().contains("eula=true"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun createPaperEngine() = EngineVersion(
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

    private fun createProfile(version: String, serverDir: File, eulaAccepted: Boolean = false) = ServerProfile(
        id = "test-uuid",
        name = "Test Server",
        edition = ServerEdition.JAVA,
        engineId = "java_paper",
        engineVersionId = "java_paper_stable",
        bedrockVersion = version,
        port = 25565,
        maxPlayers = 20,
        memoryMb = 2048,
        serverDirectory = serverDir.absolutePath,
        levelName = "world",
        worldSeed = 0L,
        worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
        worldSeedKnown = false,
        onlineMode = true,
        minecraftEulaAccepted = eulaAccepted
    )
    
    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
