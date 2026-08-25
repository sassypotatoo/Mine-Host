package com.example.world

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldWorkingCopyManagerLoopTest {
    @Test fun cleanSameEngineStopResumesButDifferentEngineResetsFromOriginal() {
        val root = Files.createTempDirectory("minehost-world-loop").toFile()
        try {
            val world = File(root, "worlds/world").apply { mkdirs() }
            File(world, "level.dat").writeText("immutable-source")
            File(world, "db").mkdirs()
            File(world, "db/source.ldb").writeText("source")
            val sourceHash = WorldFileIntegrity.fingerprint(world).rootHash
            val manager = WorldWorkingCopyManager(root)
            manager.protectImportedWorld("world", world, inspection(sourceHash), "test-adoption-world")

            val first = manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )
            assertFalse(first.resumedExistingCopy)
            File(world, "db/engine-write.ldb").writeText("provisional change")
            val provisional = manager.markCleanProvisionalStop(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )
            assertNotEquals(sourceHash, provisional.preparedWorldHash)

            val second = manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )
            assertTrue(second.resumedExistingCopy)
            assertTrue(File(world, "db/engine-write.ldb").isFile)

            val differentEngine = manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_nukkit",
                engineVersionId = "pm1e:1",
                adapterVersion = "adapter:2",
            )
            assertFalse(differentEngine.resumedExistingCopy)
            assertFalse(File(world, "db/engine-write.ldb").exists())
            assertEquals(sourceHash, WorldFileIntegrity.fingerprint(world).rootHash)
        } finally {
            root.deleteRecursively()
        }
    }


    @Test fun readinessWithoutCleanStopNeverResumesAfterProcessLoss() {
        val root = Files.createTempDirectory("minehost-world-unclean-loop").toFile()
        try {
            val world = File(root, "worlds/world").apply { mkdirs() }
            File(world, "level.dat").writeText("immutable-source")
            File(world, "db").mkdirs()
            File(world, "db/source.ldb").writeText("source")
            val sourceHash = WorldFileIntegrity.fingerprint(world).rootHash
            val manager = WorldWorkingCopyManager(root)
            manager.protectImportedWorld("world", world, inspection(sourceHash), "test-adoption-world")
            manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )
            File(world, "db/uncommitted.ldb").writeText("engine write before process loss")
            manager.markPartiallyCompatible("world", "RakNet became ready")

            val afterProcessLoss = manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )
            assertFalse(afterProcessLoss.resumedExistingCopy)
            assertFalse(File(world, "db/uncommitted.ldb").exists())
            assertEquals(sourceHash, WorldFileIntegrity.fingerprint(world).rootHash)
        } finally {
            root.deleteRecursively()
        }
    }


    @Test fun missingProvisionalMarkerForcesFreshCopyInsteadOfResume() {
        val root = Files.createTempDirectory("minehost-world-missing-marker").toFile()
        try {
            val world = File(root, "worlds/world").apply { mkdirs() }
            File(world, "level.dat").writeText("immutable-source")
            File(world, "db").mkdirs()
            File(world, "db/source.ldb").writeText("source")
            val sourceHash = WorldFileIntegrity.fingerprint(world).rootHash
            val manager = WorldWorkingCopyManager(root)
            manager.protectImportedWorld("world", world, inspection(sourceHash), "test-adoption-world")
            manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )
            File(world, "db/clean-write.ldb").writeText("clean provisional write")
            manager.markCleanProvisionalStop(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
            )

            val missingMarkerAttempt = manager.prepareEngineWorkingCopy(
                worldName = "world",
                engineId = "bedrock_power_nukkit_x",
                engineVersionId = "pnx:1",
                adapterVersion = "adapter:1",
                allowResume = false,
            )
            assertFalse(missingMarkerAttempt.resumedExistingCopy)
            assertFalse(File(world, "db/clean-write.ldb").exists())
            assertEquals(sourceHash, WorldFileIntegrity.fingerprint(world).rootHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun registrationRenameMovesImmutableOriginalAndDeleteRemovesIt() {
        val root = Files.createTempDirectory("minehost-world-registration").toFile()
        try {
            val world = File(root, "worlds/old").apply { mkdirs() }
            File(world, "level.dat").writeText("source")
            File(world, "db").mkdirs()
            val hash = WorldFileIntegrity.fingerprint(world).rootHash
            val manager = WorldWorkingCopyManager(root)
            manager.protectImportedWorld("old", world, inspection(hash), "test-adoption-old")
            val oldPath = manager.metadata("old")!!.originalWorldPath

            assertTrue(manager.renameRegistration("old", "new"))
            assertNull(manager.metadata("old"))
            val renamed = manager.metadata("new")!!
            assertNotEquals(oldPath, renamed.originalWorldPath)
            assertTrue(File(renamed.originalWorldPath).isDirectory)
            assertTrue(manager.hasProtectedOriginal("new"))

            assertTrue(manager.deleteRegistration("new"))
            assertNull(manager.metadata("new"))
            assertFalse(File(renamed.originalWorldPath).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun inspection(hash: String): BedrockWorldInspectionResult = BedrockWorldInspectionResult(
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
