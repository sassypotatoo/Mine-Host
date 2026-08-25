package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeMappingDiagnosticsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRuntimeMappingDiagnostics_formatsCleanSummary() {
        val artifactFile = tempFolder.newFile("nukkit-mot.jar")

        val inspection = EngineArtifactInspection(
            engineId = "nukkit-mot",
            engineVersionId = "build-1361",
            artifactSha256 = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
            status = CompatibilityStatus.SUPPORTED,
            persistentRuntimeIdsByCanonicalState = emptyMap(),
            protocolStatesByRuntimeId = mapOf(685 to mapOf(0 to EnginePaletteBlockState(685, 0, 1, 0, false, "minecraft:stone", "minecraft:stone", 10))),
            inspectedResources = setOf("leveldb_palette.nbt", "runtime_block_states_685.dat"),
            warnings = emptyList(),
            errors = emptyList(),
        )

        val report = WorldCompatibilityReport(
            worldName = "myworld",
            sourceWorldHash = "hash123",
            totalFiles = 10,
            totalBytes = 1000L,
            lastOpenedMinecraftVersion = "1.20.80",
            storageVersion = 10,
            networkVersion = 685,
            chunkSerializerVersions = mapOf(42 to 10),
            minimumChunkSerializerVersion = 42,
            maximumChunkSerializerVersion = 42,
            chunkCountByDimension = mapOf(0 to 10),
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
                paletteEntryCount = 0L,
                parseErrors = emptyList(),
                canonicalStates = emptyList(),
            ),
            blockEntityIdentifiers = mapOf("Chest" to 5),
            actorEntityIdentifiers = emptyMap(),
            behaviorPacks = emptyList(),
            resourcePacks = emptyList(),
            unknownChunkRecordTypes = emptyMap(),
            levelDbTableErrors = emptyList(),
            activeLevelDbLogs = emptyList(),
            warnings = emptyList(),
            errors = emptyList(),
            sections = emptyMap(),
            blockEntityObservations = emptyList(),
        )

        val summary = RuntimeMappingDiagnostics.analyze(
            engineId = "nukkit-mot",
            engineVersionId = "build-1361",
            launchArtifact = artifactFile,
            inspection = inspection,
            report = report,
            repairs = emptyList(),
        )

        val logs = summary.formatLogs()
        assertTrue(logs.any { it.contains("[RuntimeMappingDiagnostics]") })
        assertEquals(1, summary.totalRuntimeIdsMapped)
    }
}
