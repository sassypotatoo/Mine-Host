package com.example.world

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPassThroughWorldAdapterTest {
    @Test fun readableJarAndSerializer42RemainProtectedAndProvisional() {
        val root = Files.createTempDirectory("minehost-pass-through-adapter").toFile()
        try {
            val jar = File(root, "engine.jar")
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("cn/nukkit/Nukkit.class"))
                zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("runtime_block_states_fixture.dat"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            val artifact = PowerNukkitXExperimentalWorldAdapter.inspectEngineArtifact(
                EngineArtifactContext(
                    artifact = jar,
                    engineVersionId = "pnx:test",
                    expectedSha256 = WorldFileIntegrity.sha256(jar),
                    protocolVersions = emptyList(),
                ),
            )
            assertEquals(CompatibilityStatus.PARTIALLY_SUPPORTED, artifact.status)
            assertTrue(artifact.errors.isEmpty())

            val compatibility = PowerNukkitXExperimentalWorldAdapter.checkWorldCompatibility(
                report(serializer = 42),
                engineVersionId = "pnx:test",
                artifactInspection = artifact,
            )
            assertEquals(CompatibilityStatus.PARTIALLY_SUPPORTED, compatibility.overall)
            assertTrue(compatibility.requiresProtectedLaunch)
            assertNotNull(PowerNukkitXExperimentalWorldAdapter.detectRuntimeFailure("Failed to decode chunk 1,2"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun wrongArtifactHashFailsClosed() {
        val root = Files.createTempDirectory("minehost-pass-through-adapter").toFile()
        try {
            val jar = File(root, "engine.jar")
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("cn/nukkit/Nukkit.class"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            val inspection = Pm1eWorldAdapter.inspectEngineArtifact(
                EngineArtifactContext(
                    artifact = jar,
                    engineVersionId = "pm1e:test",
                    expectedSha256 = "0".repeat(64),
                    protocolVersions = emptyList(),
                ),
            )
            assertEquals(CompatibilityStatus.CORRUPT, inspection.status)
            assertTrue(inspection.errors.any { it.contains("SHA-256 mismatch") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun report(serializer: Int): WorldCompatibilityReport = WorldCompatibilityReport(
        worldName = "fixture",
        sourceWorldHash = "a".repeat(64),
        totalFiles = 1,
        totalBytes = 1,
        lastOpenedMinecraftVersion = "1.26.33.1.0",
        storageVersion = 10,
        networkVersion = 975,
        chunkSerializerVersions = mapOf(serializer to 1),
        minimumChunkSerializerVersion = serializer,
        maximumChunkSerializerVersion = serializer,
        chunkCountByDimension = mapOf(0 to 1),
        dimensionsPresent = setOf(0),
        worldSpawn = WorldCoordinates(0.0, 64.0, 0.0),
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
            subchunkFormatVersions = mapOf(9 to 1),
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
    )
}
