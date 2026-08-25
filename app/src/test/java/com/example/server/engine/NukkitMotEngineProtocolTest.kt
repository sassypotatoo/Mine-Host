package com.example.server.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.version.EngineProtocolCompatibility
import com.example.server.version.EngineProtocolCompatibilityState
import com.example.server.version.EngineVersion
import com.example.server.version.InstalledEngineVersion
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.ResolvedEngineVersion
import com.example.server.version.VersionSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class NukkitMotEngineProtocolTest {

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

    @Test
    fun exactCatalogBuildUsesCatalogProtocols() {
        val engine = NukkitMOTEngine(
            context = context,
            serverDir = serverDir,
            engineVersion = catalogEngineVersion,
            selectedBedrockVersion = "1.26.30",
            port = 19132,
            profileId = "test-profile",
            runtimeSessionId = "test-session",
        )

        val expectation = engine.runtimeProtocolExpectationForTest()
        assertEquals(listOf(1001), expectation.expectedProtocols)
        assertEquals("1.26.30", expectation.selectedBedrockVersion)
        assertEquals("catalog:nukkit-mot:1361", expectation.source)
    }

    @Test
    fun resolvedFallbackDiscoversProtocolsFromActualJar() {
        val serverJar = File(serverDir, "nukkit-mot.jar")
        ZipOutputStream(serverJar.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("resources/runtime_block_states_2168.dat"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val resolvedIdentity = ResolvedEngineVersion(
            catalogBaseId = "nukkit-mot:1361",
            effectiveVersionId = "nukkit-mot:1388",
            engineId = "nukkit-mot",
            resolvedBuildNumber = 1388,
            resolvedArtifactUrl = "https://motci.cn/job/Nukkit-MOT/job/master/1388/artifact/target/Nukkit-MOT-SNAPSHOT.jar",
            artifactSize = serverJar.length(),
            sourceRepository = "https://github.com/MemoriesOfTime/Nukkit-MOT",
            sourceRevision = "master",
            resolvedAt = System.currentTimeMillis(),
            generatorId = "nukkit-mot/normal",
            generatorRevision = "jenkins-build:1388"
        )

        val engine = NukkitMOTEngine(
            context = context,
            serverDir = serverDir,
            engineVersion = catalogEngineVersion,
            selectedBedrockVersion = "1.26.30",
            port = 19132,
            profileId = "test-profile",
            runtimeSessionId = "test-session",
            explicitResolvedIdentity = resolvedIdentity
        )

        val expectation = engine.runtimeProtocolExpectationForTest()
        assertEquals(listOf(2168), expectation.expectedProtocols)
        assertEquals("AUTO", expectation.selectedBedrockVersion)
        assertEquals("resolved-artifact:nukkit-mot:1388", expectation.source)

        val verification = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = expectation.selectedBedrockVersion,
            advertisedBedrockVersion = "1.21.30",
            advertisedProtocol = 2168,
            expectedProtocols = expectation.expectedProtocols,
            advertisedEdition = "MCPE",
        )

        assertEquals(EngineProtocolCompatibilityState.VERIFIED, verification.state)
    }

    @Test
    fun resolvedFallbackRejectsUnadvertisedProtocol() {
        val serverJar = File(serverDir, "nukkit-mot.jar")
        ZipOutputStream(serverJar.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("runtime_block_states_2168.dat"))
            zos.write(byteArrayOf(1, 2, 3))
            zos.closeEntry()
        }

        val resolvedIdentity = ResolvedEngineVersion(
            catalogBaseId = "nukkit-mot:1361",
            effectiveVersionId = "nukkit-mot:1388",
            engineId = "nukkit-mot",
            resolvedBuildNumber = 1388,
            resolvedArtifactUrl = "https://motci.cn/job/Nukkit-MOT/job/master/1388/artifact/target/Nukkit-MOT-SNAPSHOT.jar",
            artifactSize = serverJar.length(),
            sourceRepository = "https://github.com/MemoriesOfTime/Nukkit-MOT",
            sourceRevision = "master",
            resolvedAt = System.currentTimeMillis(),
            generatorId = "nukkit-mot/normal",
            generatorRevision = "jenkins-build:1388"
        )

        val engine = NukkitMOTEngine(
            context = context,
            serverDir = serverDir,
            engineVersion = catalogEngineVersion,
            selectedBedrockVersion = "1.26.30",
            port = 19132,
            profileId = "test-profile",
            runtimeSessionId = "test-session",
            explicitResolvedIdentity = resolvedIdentity
        )

        val expectation = engine.runtimeProtocolExpectationForTest()

        val verification = EngineProtocolCompatibility.evaluate(
            selectedBedrockVersion = expectation.selectedBedrockVersion,
            advertisedBedrockVersion = "1.21.30",
            advertisedProtocol = 9999,
            expectedProtocols = expectation.expectedProtocols,
            advertisedEdition = "MCPE",
        )

        assertEquals(EngineProtocolCompatibilityState.PROTOCOL_MISMATCH, verification.state)
    }

    @Test
    fun resolvedFallbackThrowsWhenJarContainsNoProtocols() {
        val serverJar = File(serverDir, "nukkit-mot.jar")
        ZipOutputStream(serverJar.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("other.txt"))
            zos.write(byteArrayOf(1))
            zos.closeEntry()
        }

        val resolvedIdentity = ResolvedEngineVersion(
            catalogBaseId = "nukkit-mot:1361",
            effectiveVersionId = "nukkit-mot:1388",
            engineId = "nukkit-mot",
            resolvedBuildNumber = 1388,
            resolvedArtifactUrl = "https://motci.cn/job/Nukkit-MOT/job/master/1388/artifact/target/Nukkit-MOT-SNAPSHOT.jar",
            artifactSize = serverJar.length(),
            sourceRepository = "https://github.com/MemoriesOfTime/Nukkit-MOT",
            sourceRevision = "master",
            resolvedAt = System.currentTimeMillis(),
            generatorId = "nukkit-mot/normal",
            generatorRevision = "jenkins-build:1388"
        )

        val engine = NukkitMOTEngine(
            context = context,
            serverDir = serverDir,
            engineVersion = catalogEngineVersion,
            selectedBedrockVersion = "1.26.30",
            port = 19132,
            profileId = "test-profile",
            runtimeSessionId = "test-session",
            explicitResolvedIdentity = resolvedIdentity
        )

        assertThrows(IllegalStateException::class.java) {
            engine.runtimeProtocolExpectationForTest()
        }
    }
}
