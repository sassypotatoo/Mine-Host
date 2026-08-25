package com.example.world

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedPlayerMigrationStoreTest {
    @Test fun restoringDisposableWorldResetsPositionApplication() {
        val root = Files.createTempDirectory("minehost-player-migration").toFile()
        try {
            val record = byteArrayOf(10, 0, 0, 0)
            val player = ImportedPlayerData(
                available = true,
                recordSha256 = WorldFileIntegrity.sha256(record),
                position = WorldCoordinates(1.0, 2.0, 3.0),
                rotation = WorldRotation(4.0, 5.0),
                dimensionId = 0,
                gameMode = 0,
                selectedHotbarSlot = 0,
                experienceLevel = 0,
                experienceProgress = 0.0,
                inventory = emptyList(), armor = emptyList(), offhand = emptyList(), enderChest = emptyList(),
                rawKeys = emptySet(),
            )
            val store = ImportedPlayerMigrationStore(root)
            store.stage("world", player, record)
            store.approveClaim("world", "Player")
            store.markPositionApplied("world")
            assertEquals(ImportedPlayerMigrationState.CLAIMED, store.resetAfterWorldRestore("world").state)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun renameAndDeleteFollowTheWorldLifecycle() {
        val root = Files.createTempDirectory("minehost-player-migration-lifecycle").toFile()
        try {
            val record = byteArrayOf(10, 0, 0, 0)
            val player = ImportedPlayerData(
                available = true,
                recordSha256 = WorldFileIntegrity.sha256(record),
                position = WorldCoordinates(1.0, 2.0, 3.0),
                rotation = null,
                dimensionId = 0,
                gameMode = 0,
                selectedHotbarSlot = 0,
                experienceLevel = 0,
                experienceProgress = 0.0,
                inventory = emptyList(), armor = emptyList(), offhand = emptyList(), enderChest = emptyList(),
                rawKeys = emptySet(),
            )
            val store = ImportedPlayerMigrationStore(root)
            store.stage("old", player, record)
            assertTrue(store.rename("old", "new"))
            assertEquals(null, store.load("old"))
            assertEquals("new", store.load("new")?.worldName)
            assertTrue(store.delete("new"))
            assertEquals(null, store.load("new"))
        } finally {
            root.deleteRecursively()
        }
    }
}
