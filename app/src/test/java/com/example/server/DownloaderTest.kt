package com.example.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.javaedition.PaperBuildInfo
import com.example.javaedition.PaperUserAgentProvider
import com.example.server.version.EngineVersion
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.ResolvedEngineVersion
import com.example.server.version.VersionSourceType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DownloaderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun testUserAgentFormat() {
        val contact = "https://github.com/test-owner/test-repository"
        val agent = PaperUserAgentProvider.buildUserAgent("1.0", contact)
        assertEquals("MineHost/1.0 (https://github.com/test-owner/test-repository)", agent)
        assertTrue(agent.startsWith("MineHost/"))
        assertTrue(agent.contains(contact))
    }

    @Test
    fun testPaperArtifactRequestUserAgentAndSizeMismatch() = runBlocking {
        val server = MockWebServer()
        val dummyContent = "Hello Paper Artifact"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/java-archive")
                .setBody(dummyContent)
        )
        server.start()

        try {
            val contact = "https://github.com/test-owner/test-repository"
            val userAgent = PaperUserAgentProvider.buildUserAgent("1.0", contact)

            val downloadUrl = server.url("/paper.jar").toString()
            val tempDest = File.createTempFile("minehost-download-test-", ".jar")

            val result = Downloader.downloadFile(
                url = downloadUrl,
                destination = tempDest,
                onProgress = {},
                name = "paper.jar",
                isJar = false,
                requestHeaders = mapOf("User-Agent" to userAgent)
            )

            assertTrue(result is ArtifactDownloadResult.Success || result is ArtifactDownloadResult.ValidationFailure)

            val recordedRequest = server.takeRequest()
            val requestUserAgent = recordedRequest.getHeader("User-Agent")
            assertNotNull(requestUserAgent)
            assertTrue(requestUserAgent!!.startsWith("MineHost/"))
            assertTrue(requestUserAgent.contains(contact))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testCrossVersionCacheValidation() = runBlocking {
        val engine = createPaperEngine("test").copy(mainClass = "Test")
        val cacheDir = Downloader.getCacheDir(context, engine.engineId, engine.id)
        cacheDir.mkdirs()

        val cacheFile = File(cacheDir, engine.jarFileName)
        java.util.zip.ZipOutputStream(cacheFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("Test.class"))
            val randomBytes = ByteArray(10240) // 10KB to be safe
            java.util.Random().nextBytes(randomBytes)
            zos.write(randomBytes)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\nMain-Class: Test\n".toByteArray())
            zos.closeEntry()
        }

        val actualSha256 = Downloader.sha256(cacheFile)
        val actualSize = cacheFile.length()

        val identity21 = ResolvedEngineVersion(
            catalogBaseId = engine.id,
            effectiveVersionId = "java_paper:1.21.11:100",
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
            identity = identity21,
            actualSha256 = actualSha256!!
        )
        assertTrue(committed)

        // CASE A: Requested 1.21.11 -> CACHE HIT
        val verified21 = Downloader.verifyCachedEngineArtifact(
            context = context,
            version = engine,
            invalidateInvalid = false,
            selectedMinecraftVersion = "1.21.11"
        )
        assertNotNull(verified21)
        assertEquals("java_paper:1.21.11:100", verified21?.resolvedIdentity?.effectiveVersionId)

        // CASE B: Requested 26.2 with 1.21.11 cache -> CACHE MISS
        val verified26Miss = Downloader.verifyCachedEngineArtifact(
            context = context,
            version = engine,
            invalidateInvalid = false,
            selectedMinecraftVersion = "26.2"
        )
        assertNull(verified26Miss)

        // CASE C: Commit metadata for 26.2 and requested 26.2 -> CACHE HIT
        val identity26 = identity21.copy(effectiveVersionId = "java_paper:26.2:101")
        Downloader.writeResolvedCacheMetadata(
            context = context,
            version = engine,
            cacheFile = cacheFile,
            identity = identity26,
            actualSha256 = actualSha256!!
        )
        val verified26Hit = Downloader.verifyCachedEngineArtifact(
            context = context,
            version = engine,
            invalidateInvalid = false,
            selectedMinecraftVersion = "26.2"
        )
        assertNotNull(verified26Hit)
        assertEquals("java_paper:26.2:101", verified26Hit?.resolvedIdentity?.effectiveVersionId)

        // CASE D: Same version but file content changed (wrong SHA) -> CACHE MISS
        java.io.FileOutputStream(cacheFile).use { it.write("Changed content to break SHA".toByteArray()) }
        val verifiedWrongSha = Downloader.verifyCachedEngineArtifact(
            context = context,
            version = engine,
            invalidateInvalid = false,
            selectedMinecraftVersion = "26.2"
        )
        assertNull(verifiedWrongSha)

        // Restore file content for next cases
        java.util.zip.ZipOutputStream(cacheFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("Test.class"))
            val randomBytes = ByteArray(10240)
            java.util.Random().nextBytes(randomBytes)
            zos.write(randomBytes)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\nMain-Class: Test\n".toByteArray())
            zos.closeEntry()
        }
        val actualSha256Restored = Downloader.sha256(cacheFile)
        val actualSizeRestored = cacheFile.length()
        Downloader.writeResolvedCacheMetadata(
            context = context,
            version = engine,
            cacheFile = cacheFile,
            identity = identity26.copy(artifactSize = actualSizeRestored),
            actualSha256 = actualSha256Restored!!
        )

        // CASE E: Same version/SHA but wrong size -> CACHE MISS
        val identityWrongSize = identity26.copy(artifactSize = actualSizeRestored + 100L)
        val json = org.json.JSONObject().apply {
            put("schemaVersion", 1)
            put("engineId", engine.engineId)
            put("catalogBaseId", engine.id)
            put("jarFileName", engine.jarFileName)
            put("jarSha256", actualSha256Restored)
            put("effectiveVersionId", identityWrongSize.effectiveVersionId)
            put("resolvedBuildNumber", identityWrongSize.resolvedBuildNumber)
            put("resolvedArtifactUrl", identityWrongSize.resolvedArtifactUrl)
            put("artifactSize", identityWrongSize.artifactSize)
            put("sourceRepository", identityWrongSize.sourceRepository)
            put("sourceRevision", identityWrongSize.sourceRevision)
            put("resolvedAt", identityWrongSize.resolvedAt)
            put("generatorId", identityWrongSize.generatorId)
            put("generatorRevision", identityWrongSize.generatorRevision)
        }
        java.io.File(cacheDir, "${engine.jarFileName}.resolved-identity.json").writeText(json.toString())
        val verifiedWrongSize = Downloader.verifyCachedEngineArtifact(
            context = context,
            version = engine,
            invalidateInvalid = false,
            selectedMinecraftVersion = "26.2"
        )
        assertNull(verifiedWrongSize)
    }

    private fun createPaperEngine(suffix: String = "stable") = EngineVersion(
        id = "java_paper_$suffix",
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
}
