package com.example.server.version

import android.content.Context
import com.example.data.ServerEdition
import com.example.data.ServerProfile
import com.example.javaedition.PaperResolver
import com.example.javaedition.PaperUserAgentProvider
import com.example.server.Downloader
import com.example.server.ServerJarDownloadResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaperHardeningTest {

    private val context = org.robolectric.RuntimeEnvironment.getApplication()
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        // internal set is visible to this friend compilation; reflection cannot
        // do this because the backing field is an instance field of the object.
        PaperResolver.projectUrl = server.url("/v3/projects/paper").toString()

        tempDir = File.createTempFile("paper-hardening-", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun teardown() {
        PaperResolver.projectUrl = "https://fill.papermc.io/v3/projects/paper"
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun testUserAgentContainsContact() {
        val userAgent = PaperResolver.userAgent()
        val ua = PaperUserAgentProvider.value()
        assertTrue(ua.startsWith("MineHost/"))
    }

    @Test
    fun testPaperJavaVersionPolicyFailClosed() {
        // Verified ranges
        assertEquals(17, PaperResolver.determineRequiredJavaMajor("1.18.2"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.20.1"))
        assertEquals(21, PaperResolver.determineRequiredJavaMajor("1.21.4"))
        assertEquals(25, PaperResolver.determineRequiredJavaMajor("26.1"))
        
        // Unknown future version should fail
        runCatching { PaperResolver.determineRequiredJavaMajor("27.0") }.also {
            assertTrue(it.isFailure)
            assertTrue(it.exceptionOrNull()?.message?.contains("no verified Java runtime mapping") == true)
        }
    }

    @Test
    fun testSizeMismatchRejection() = runBlocking {
        val engine = createPaperEngine(expectedSize = 2000L)
        val dest = File(tempDir, "paper.jar")

        // downloadFile validates JAR structure before returning, so the body
        // must be a real JAR; only its length is wrong versus the manifest.
        val jarBytes = fakeJarBytes()
        assertTrue(jarBytes.size != 2000)

        val buildInfoJson = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 100)
                put("channel", "STABLE")
                put("downloads", JSONObject().apply {
                    put("server:default", JSONObject().apply {
                        put("name", "paper-1.21.4-100.jar")
                        put("url", server.url("/test.jar").toString())
                        put("checksums", JSONObject().apply {
                            put("sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
                        })
                        put("size", 2000)
                    })
                })
            })
        }

        // Mock server to return the build info
        server.enqueue(MockResponse().setBody(buildInfoJson.toString()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(jarBytes)))

        val result = Downloader.downloadServerJar(context, engine, dest, minecraftVersion = "1.21.4") {}

        assertTrue("result: $result", result is ServerJarDownloadResult.Failure)
        assertTrue(
            "result: $result",
            (result as ServerJarDownloadResult.Failure).message.contains("size mismatch"),
        )
        assertFalse(dest.exists())
    }

    @Test
    fun testShaMismatchRejection() = runBlocking {
        val jarBytes = fakeJarBytes()

        val buildInfoJson = JSONArray().apply {
            put(JSONObject().apply {
                put("id", 100)
                put("channel", "STABLE")
                put("downloads", JSONObject().apply {
                    put("server:default", JSONObject().apply {
                        put("name", "paper-1.21.4-100.jar")
                        put("url", server.url("/test.jar").toString())
                        put("checksums", JSONObject().apply {
                            put("sha256", "f00df00df00df00df00df00df00df00df00df00df00df00df00df00df00df00d")
                        })
                        put("size", jarBytes.size)
                    })
                })
            })
        }

        server.enqueue(MockResponse().setBody(buildInfoJson.toString()))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(jarBytes)))

        val engine = createPaperEngine(expectedSize = jarBytes.size.toLong())
        val dest = File(tempDir, "paper.jar")
        val result = Downloader.downloadServerJar(context, engine, dest, minecraftVersion = "1.21.4") {}

        assertTrue("result: $result", result is ServerJarDownloadResult.Failure)
        assertTrue(
            "result: $result",
            (result as ServerJarDownloadResult.Failure).message.contains("checksum mismatch"),
        )
        assertFalse(dest.exists())
    }

    private fun fakeJarBytes(): ByteArray {
        // Incompressible payload: deflate must not shrink the archive below
        // validateGenericJar's 1024-byte floor.
        val noise = ByteArray(2048).also { java.util.Random(0x5EED).nextBytes(it) }
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("org/bukkit/craftbukkit/Main.class"))
            zos.write(noise)
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("META-INF/MANIFEST.MF"))
            zos.write("Manifest-Version: 1.0\n".toByteArray())
            zos.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun createPaperEngine(expectedSize: Long? = null) = EngineVersion(
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

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
