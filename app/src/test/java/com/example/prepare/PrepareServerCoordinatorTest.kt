package com.example.prepare

import android.content.Context
import com.example.data.ServerEdition
import com.example.data.ServerProfile
import com.example.plugins.PluginInstaller
import com.example.server.version.EngineVersion
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.VersionSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrepareServerCoordinatorTest {

    @org.junit.Before
    fun setup() {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
    }

    @Test
    fun testActualPreparationMetadataFlow() = runBlocking {
        val tempDir = File.createTempFile("minehost-prep-", "").apply {
            delete()
            mkdirs()
        }
        try {
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val pluginInstaller = com.example.plugins.PluginInstaller(
                createBackup = { com.example.data.OperationResult(true, "Mock backup") }
            )
            val coordinator = PrepareServerCoordinator(context, pluginInstaller)
            val engine = createPaperEngine()
            
            // Paper 1.19.4 -> Java 17
            val profile19 = createProfile("1.19.4", tempDir)
            mockPaperDownload("1.19.4", tempDir)
            val res19 = coordinator.prepare(profile19, engine, emptyList(), false) {}
            if (res19 is com.example.prepare.PrepareServerCoordinator.Result.Failure) {
                println("DEBUG: prepare 19 failed: ${res19.message}")
            }
            
            val metadata19 = com.example.server.version.InstalledEngineVersionRepository.read(tempDir)
            assertEquals(17, metadata19?.runtimeJavaVersion)
            
            // Paper 1.21.11 -> Java 21
            tempDir.listFiles()?.forEach { it.deleteRecursively() }
            val profile21 = createProfile("1.21.11", tempDir)
            mockPaperDownload("1.21.11", tempDir)
            coordinator.prepare(profile21, engine, emptyList(), false) {}
            
            val metadata21 = com.example.server.version.InstalledEngineVersionRepository.read(tempDir)
            assertEquals(21, metadata21?.runtimeJavaVersion)

            // Paper 26.5 -> Java 25
            tempDir.listFiles()?.forEach { it.deleteRecursively() }
            val profile26 = createProfile("26.5", tempDir)
            mockPaperDownload("26.5", tempDir)
            coordinator.prepare(profile26, engine, emptyList(), false) {}
            
            val metadata26 = com.example.server.version.InstalledEngineVersionRepository.read(tempDir)
            assertEquals(25, metadata26?.runtimeJavaVersion)
            
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun mockPaperDownload(version: String, serverDir: File) {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val engine = createPaperEngine()
        val cacheDir = com.example.server.Downloader.getCacheDir(context, engine.engineId, engine.id)
        cacheDir.mkdirs()
        
        val cacheFile = File(cacheDir, engine.jarFileName)
        // Create a valid-ish JAR content (ZIP) with enough size
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
        
        val actualSha256 = com.example.server.Downloader.sha256(cacheFile)
        val actualSize = cacheFile.length()
        
        // Also copy it to the server dir so matches() and write() succeed
        val serverJar = File(serverDir, "paper.jar")
        cacheFile.copyTo(serverJar, overwrite = true)
        
        val identity = com.example.server.version.ResolvedEngineVersion(
            catalogBaseId = engine.id,
            effectiveVersionId = "java_paper:$version:100",
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
        
        com.example.server.Downloader.writeResolvedCacheMetadata(
            context = context,
            version = engine,
            cacheFile = cacheFile,
            identity = identity,
            actualSha256 = actualSha256!!
        )
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

    private fun createProfile(version: String, serverDir: File) = ServerProfile(
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
        onlineMode = true
    )
}
