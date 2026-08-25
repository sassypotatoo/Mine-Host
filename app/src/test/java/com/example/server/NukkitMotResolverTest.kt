package com.example.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.version.EngineReleaseChannel
import com.example.server.version.EngineVersion
import com.example.server.version.ReleaseChannel
import com.example.server.version.VersionSourceType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NukkitMotResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private val client = OkHttpClient()
    private val clock = Clock.fixed(
        Instant.parse("2024-01-01T00:00:00Z"),
        ZoneId.of("UTC"),
    )

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        NukkitMotResolver.getLastKnownGoodFile(context).delete()
    }

    @After
    fun teardown() {
        NukkitMotResolver.getLastKnownGoodFile(context).delete()
        server.shutdown()
    }

    private fun version(): EngineVersion = EngineVersion(
        id = "nukkit-mot:1361",
        engineId = "nukkit-mot",
        versionName = "Master Build 1361",
        displayName = "Nukkit-MOT Master Build 1361",
        channel = ReleaseChannel.SNAPSHOT,
        releaseChannel = EngineReleaseChannel.ROLLING,
        downloadUrl =
            "https://motci.cn/job/Nukkit-MOT/job/master/1361/" +
                "artifact/target/Nukkit-MOT-SNAPSHOT.jar",
        jarFileName = "nukkit-mot.jar",
        requiredJavaVersion = 17,
        runtimeJavaVersion = 17,
        compatibilityLabel = "Bedrock",
        recommended = true,
        sourceType = VersionSourceType.JENKINS_BUILD,
        buildNumber = "1361",
        sha256 = "0".repeat(64),
    )

    private fun resolver(): NukkitMotResolver = NukkitMotResolver(
        client = client,
        jobRoot = server.url("/job/Nukkit-MOT/job/master"),
        clock = clock,
    )

    private fun successfulApiJson(build: Int = 100): String = """
        {
          "builds": [
            {
              "number": $build,
              "url": "${server.url("/job/Nukkit-MOT/job/master/$build/")}",
              "result": "SUCCESS",
              "actions": [
                {
                  "lastBuiltRevision": { "SHA1": "abc123sha" },
                  "remoteUrls": [
                    "https://github.com/MemoriesOfTime/Nukkit-MOT.git"
                  ]
                }
              ],
              "artifacts": [
                { "relativePath": "target/Nukkit-MOT-SNAPSHOT.jar" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun resolveCandidatesParsesOfficialSuccessfulBuild() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(successfulApiJson())
        )

        val candidates = resolver().resolveCandidates(
            context,
            version(),
        ) { }

        assertEquals(1, candidates.size)
        assertEquals(100, candidates.single().buildNumber)
        assertEquals("abc123sha", candidates.single().sourceRevision)
        assertTrue(
            candidates.single().artifactUrl.endsWith(
                "/artifact/target/Nukkit-MOT-SNAPSHOT.jar"
            )
        )
    }

    @Test
    fun failedBuildAndWrongArtifactPathAreRejected() = runBlocking {
        val response = """
            {
              "builds": [
                {
                  "number": 101,
                  "url": "${server.url("/job/Nukkit-MOT/job/master/101/")}",
                  "result": "FAILURE",
                  "artifacts": [
                    { "relativePath": "target/Nukkit-MOT-SNAPSHOT.jar" }
                  ]
                },
                {
                  "number": 102,
                  "url": "${server.url("/job/Nukkit-MOT/job/master/102/")}",
                  "result": "SUCCESS",
                  "artifacts": [
                    { "relativePath": "target/not-the-server.jar" }
                  ]
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))

        val candidates = resolver().resolveCandidates(
            context,
            version(),
        ) { }

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun lastKnownGoodRequiresMatchingCatalogShaAndZipSignature() = runBlocking {
        val currentVersion = version()
        val lkg = NukkitMotResolver.getLastKnownGoodFile(context)
        val artifactUrl = server.url(
            "/job/Nukkit-MOT/job/master/1376/" +
                "artifact/target/Nukkit-MOT-SNAPSHOT.jar"
        ).toString()
        lkg.writeText(
            JSONObject().apply {
                put("schemaVersion", 1)
                put("engineId", "nukkit-mot")
                put("catalogBaseId", currentVersion.id)
                put("resolvedBuildNumber", 1376)
                put("resolvedBuildUrl", server.url(
                    "/job/Nukkit-MOT/job/master/1376/"
                ).toString())
                put("artifactUrl", artifactUrl)
                put(
                    "artifactRelativePath",
                    "target/Nukkit-MOT-SNAPSHOT.jar",
                )
                put("artifactSize", 4096L)
                put("artifactSha256", "a".repeat(64))
                put(
                    "sourceRepository",
                    "https://github.com/MemoriesOfTime/Nukkit-MOT",
                )
                put("resolvedTime", clock.millis())
            }.toString(2)
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "application/java-archive")
                .setBody("PK\u0003\u0004")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"builds\":[]}")
        )

        val candidates = resolver().resolveCandidates(
            context,
            currentVersion,
            failedBuildNumber = "1361",
        ) { }

        assertEquals(1, candidates.size)
        assertEquals(1376, candidates.single().buildNumber)
        assertEquals("a".repeat(64), candidates.single().expectedSha256)
    }

    @Test
    fun htmlLastKnownGoodResponseIsInvalidated() = runBlocking {
        val currentVersion = version()
        val lkg = NukkitMotResolver.getLastKnownGoodFile(context)
        lkg.writeText(
            JSONObject().apply {
                put("schemaVersion", 1)
                put("engineId", "nukkit-mot")
                put("catalogBaseId", currentVersion.id)
                put("resolvedBuildNumber", 1376)
                put("artifactUrl", server.url(
                    "/job/Nukkit-MOT/job/master/1376/" +
                        "artifact/target/Nukkit-MOT-SNAPSHOT.jar"
                ).toString())
                put("artifactSha256", "a".repeat(64))
            }.toString(2)
        )

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>not a jar</html>")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"builds\":[]}")
        )

        val candidates = resolver().resolveCandidates(
            context,
            currentVersion,
        ) { }

        assertTrue(candidates.isEmpty())
        assertFalse(lkg.exists())
    }

    @Test
    fun saveLastKnownGoodPersistsExactIdentityAndSha() {
        val currentVersion = version()
        val resolved = ResolvedNukkitMotCandidate(
            buildNumber = 1376,
            buildUrl = server.url(
                "/job/Nukkit-MOT/job/master/1376/"
            ).toString(),
            artifactUrl = server.url(
                "/job/Nukkit-MOT/job/master/1376/" +
                    "artifact/target/Nukkit-MOT-SNAPSHOT.jar"
            ).toString(),
            artifactRelativePath = "target/Nukkit-MOT-SNAPSHOT.jar",
            artifactSize = 4096L,
            resolvedAt = clock.millis(),
            sourceRepository =
                "https://github.com/MemoriesOfTime/Nukkit-MOT",
            sourceRevision = "abc123",
            generatorRevision = "jenkins-build:1376",
        )

        resolver().saveLastKnownGood(
            context = context,
            version = currentVersion,
            resolved = resolved,
            actualFileSize = 4096L,
            actualSha256 = "b".repeat(64),
            mainClass = "cn.nukkit.Nukkit",
        )

        val json = JSONObject(
            NukkitMotResolver.getLastKnownGoodFile(context).readText()
        )
        assertEquals(currentVersion.id, json.getString("catalogBaseId"))
        assertEquals(1376, json.getInt("resolvedBuildNumber"))
        assertEquals("b".repeat(64), json.getString("artifactSha256"))
        assertEquals(4096L, json.getLong("artifactSize"))
    }

    @Test
    fun fallbackEligibilityDoesNotCrossFromJava25BranchToMaster() {
        val master = version()
        val java25 = master.copy(
            id = "nukkit-mot:59",
            downloadUrl =
                "https://motci.cn/job/Nukkit-MOT/job/java25/59/" +
                    "artifact/target/Nukkit-MOT-SNAPSHOT.jar",
            runtimeJavaVersion = 25,
            requiredJavaVersion = 25,
        )

        assertTrue(Downloader.supportsOfficialNukkitMotFallback(master))
        assertFalse(Downloader.supportsOfficialNukkitMotFallback(java25))
    }

    @Test
    fun buildNumberMustMatchArtifactUrlPath() = runBlocking {
        val response = """
            {
              "builds": [
                {
                  "number": 104,
                  "url": "${server.url("/job/Nukkit-MOT/job/master/105/")}",
                  "result": "SUCCESS",
                  "actions": [
                    {
                      "remoteUrls": [
                        "https://github.com/MemoriesOfTime/Nukkit-MOT.git"
                      ]
                    }
                  ],
                  "artifacts": [
                    { "relativePath": "target/Nukkit-MOT-SNAPSHOT.jar" }
                  ]
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))

        val candidates = resolver().resolveCandidates(
            context,
            version(),
        ) { }

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun lastKnownGoodRejectsUnofficialSourceRepository() {
        val currentVersion = version()
        val resolved = ResolvedNukkitMotCandidate(
            buildNumber = 1376,
            buildUrl = server.url(
                "/job/Nukkit-MOT/job/master/1376/"
            ).toString(),
            artifactUrl = server.url(
                "/job/Nukkit-MOT/job/master/1376/" +
                    "artifact/target/Nukkit-MOT-SNAPSHOT.jar"
            ).toString(),
            artifactRelativePath = "target/Nukkit-MOT-SNAPSHOT.jar",
            artifactSize = 4096L,
            resolvedAt = clock.millis(),
            sourceRepository = "https://github.com/example/not-official",
            sourceRevision = "abc123",
            generatorRevision = "jenkins-build:1376",
        )

        var rejected = false
        try {
            resolver().saveLastKnownGood(
                context = context,
                version = currentVersion,
                resolved = resolved,
                actualFileSize = 4096L,
                actualSha256 = "b".repeat(64),
                mainClass = "cn.nukkit.Nukkit",
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertFalse(
            NukkitMotResolver.getLastKnownGoodFile(context).exists()
        )
    }

    @Test
    fun explicitlyDifferentSourceRepositoryIsRejected() = runBlocking {
        val response = """
            {
              "builds": [
                {
                  "number": 103,
                  "url": "${server.url("/job/Nukkit-MOT/job/master/103/")}",
                  "result": "SUCCESS",
                  "actions": [
                    {
                      "remoteUrls": [
                        "https://github.com/example/not-nukkit-mot.git"
                      ]
                    }
                  ],
                  "artifacts": [
                    { "relativePath": "target/Nukkit-MOT-SNAPSHOT.jar" }
                  ]
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(response))

        val candidates = resolver().resolveCandidates(
            context,
            version(),
        ) { }

        assertTrue(candidates.isEmpty())
    }

}
