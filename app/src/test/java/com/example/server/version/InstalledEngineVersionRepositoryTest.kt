package com.example.server.version

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.Random
import java.util.jar.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The repository persists through org.json, which is a throwing stub on the
 * plain-JVM unit-test classpath; Robolectric supplies the real implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InstalledEngineVersionRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun createJar(directory: File): File {
        val jar = File(directory, "nukkit-mot.jar")
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "cn.nukkit.Nukkit"
        }

        JarOutputStream(jar.outputStream(), manifest).use { output ->
            val random = Random(0x4D494E45484F5354L)
            listOf(
                "cn/nukkit/Nukkit.class",
                "cn/nukkit/Server.class",
                "cn/nukkit/level/Level.class",
            ).forEach { entryName ->
                output.putNextEntry(JarEntry(entryName))
                val bytes = ByteArray(2_048)
                random.nextBytes(bytes)
                output.write(bytes)
                output.closeEntry()
            }
            output.putNextEntry(JarEntry("padding.bin"))
            val padding = ByteArray(8_192)
            random.nextBytes(padding)
            output.write(padding)
            output.closeEntry()
        }
        check(jar.length() > 1024L)
        return jar
    }

    private fun baseVersion(sha256: String?): EngineVersion = EngineVersion(
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
        launchMode = LaunchMode.JAVA_JAR,
        buildNumber = "1361",
        sha256 = sha256,
    )

    private fun resolvedIdentity(
        version: EngineVersion,
        jar: File,
        url: String =
            "https://motci.cn/job/Nukkit-MOT/job/master/1376/" +
                "artifact/target/Nukkit-MOT-SNAPSHOT.jar",
    ): ResolvedEngineVersion = ResolvedEngineVersion(
        catalogBaseId = version.id,
        effectiveVersionId = "nukkit-mot:1376",
        engineId = version.engineId,
        resolvedBuildNumber = 1376,
        resolvedArtifactUrl = url,
        artifactSize = jar.length(),
        sourceRepository =
            "https://github.com/MemoriesOfTime/Nukkit-MOT.git",
        sourceRevision = "abc123",
        resolvedAt = 1_717_000_000_000L,
        generatorId = "nukkit-mot/normal",
        generatorRevision = "jenkins-build:1376",
    )

    @Test
    fun pinnedArtifactExactShaPasses() {
        val serverDir = temporaryFolder.newFolder("pinned-pass")
        val jar = createJar(serverDir)
        val sha = EngineInstallationMetadataFactory.sha256OrThrow(jar)
        val version = baseVersion(sha)
        val validation = InstalledEngineVersionRepository.validateJar(
            jar,
            version.launchMode,
            version.mainClass,
        )
        val metadata = EngineInstallationMetadataFactory.create(
            version,
            "AUTO",
            jar,
            validation,
            null,
        )

        assertTrue(InstalledEngineVersionRepository.write(serverDir, metadata))
        assertTrue(
            InstalledEngineVersionRepository.matches(
                serverDir,
                version,
                "AUTO",
            )
        )
    }

    @Test
    fun pinnedArtifactCatalogShaMismatchFails() {
        val serverDir = temporaryFolder.newFolder("pinned-fail")
        val jar = createJar(serverDir)
        val version = baseVersion("0".repeat(64))
        val validation = InstalledEngineVersionRepository.validateJar(jar)
        val metadata = EngineInstallationMetadataFactory.create(
            version,
            "AUTO",
            jar,
            validation,
            null,
        )

        assertTrue(InstalledEngineVersionRepository.write(serverDir, metadata))
        assertFalse(
            InstalledEngineVersionRepository.matches(
                serverDir,
                version,
                "AUTO",
            )
        )
    }

    @Test
    fun officialResolvedMasterBuildPassesWithActualStoredSha() {
        val serverDir = temporaryFolder.newFolder("resolved-pass")
        val jar = createJar(serverDir)
        val version = baseVersion("0".repeat(64))
        val identity = resolvedIdentity(version, jar)
        val validation = InstalledEngineVersionRepository.validateJar(jar)
        val metadata = EngineInstallationMetadataFactory.create(
            version,
            "AUTO",
            jar,
            validation,
            identity,
        )

        assertTrue(InstalledEngineVersionRepository.write(serverDir, metadata))
        assertTrue(
            InstalledEngineVersionRepository.matches(
                serverDir,
                version,
                "AUTO",
            )
        )
        assertNotNull(
            InstalledEngineVersionRepository.verifiedResolvedIdentity(
                serverDir,
                version,
                "AUTO",
            )
        )
    }

    @Test
    fun resolvedBuildFromAnotherHostFails() {
        val serverDir = temporaryFolder.newFolder("resolved-host-fail")
        val jar = createJar(serverDir)
        val version = baseVersion("0".repeat(64))
        val identity = resolvedIdentity(
            version,
            jar,
            "https://example.com/job/Nukkit-MOT/job/master/1376/" +
                "artifact/target/Nukkit-MOT-SNAPSHOT.jar",
        )
        val metadata = EngineInstallationMetadataFactory.create(
            version,
            "AUTO",
            jar,
            InstalledEngineVersionRepository.validateJar(jar),
            identity,
        )

        assertTrue(InstalledEngineVersionRepository.write(serverDir, metadata))
        assertFalse(
            InstalledEngineVersionRepository.matches(
                serverDir,
                version,
                "AUTO",
            )
        )
    }

    @Test
    fun tamperedInstalledJarFails() {
        val serverDir = temporaryFolder.newFolder("tampered")
        val jar = createJar(serverDir)
        val sha = EngineInstallationMetadataFactory.sha256OrThrow(jar)
        val version = baseVersion(sha)
        val metadata = EngineInstallationMetadataFactory.create(
            version,
            "AUTO",
            jar,
            InstalledEngineVersionRepository.validateJar(jar),
            null,
        )
        assertTrue(InstalledEngineVersionRepository.write(serverDir, metadata))

        jar.appendBytes(byteArrayOf(1, 2, 3, 4))

        assertFalse(
            InstalledEngineVersionRepository.matches(
                serverDir,
                version,
                "AUTO",
            )
        )
    }

    @Test
    fun metadataWriteRejectsResolvedArtifactSizeMismatch() {
        val serverDir = temporaryFolder.newFolder("size-mismatch")
        val jar = createJar(serverDir)
        val version = baseVersion("0".repeat(64))
        val identity = resolvedIdentity(version, jar).copy(
            artifactSize = jar.length() + 1L,
        )

        val result = runCatching {
            EngineInstallationMetadataFactory.create(
                version,
                "AUTO",
                jar,
                InstalledEngineVersionRepository.validateJar(jar),
                identity,
            )
        }

        assertTrue(result.isFailure)
    }
    @Test
    fun metadataWriteRejectsMissingStoredSha() {
        val serverDir = temporaryFolder.newFolder("missing-sha")
        val jar = createJar(serverDir)
        val actualSha = EngineInstallationMetadataFactory.sha256OrThrow(jar)
        val version = baseVersion(actualSha)
        val metadata = EngineInstallationMetadataFactory.create(
            version,
            "AUTO",
            jar,
            InstalledEngineVersionRepository.validateJar(jar),
            null,
        ).copy(jarSha256 = null)

        assertFalse(
            InstalledEngineVersionRepository.write(
                serverDir,
                metadata,
            )
        )
    }

}
