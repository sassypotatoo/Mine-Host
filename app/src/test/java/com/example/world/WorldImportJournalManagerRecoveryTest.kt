package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class WorldImportJournalManagerRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testRecoverIncompleteTransaction_cleanRollback() {
        val serverRoot = tempFolder.newFolder("serverRoot")
        val txId = UUID.randomUUID().toString()
        val txDir = File(serverRoot, ".minehost/world-import-transactions/$txId").apply { mkdirs() }

        val activeWorld = File(serverRoot, "worlds/myworld").apply { mkdirs() }
        File(activeWorld, "level.dat").writeText("active-level-dat")

        val journalData = WorldImportJournalData(
            transactionId = txId,
            serverId = "test-server-uuid",
            targetWorldName = "myworld",
            stagingPath = "/tmp/staging",
            finalPath = activeWorld.absolutePath,
            rollbackPath = "/tmp/rollback",
            rollbackSidecarPath = "/tmp/rollback-sidecars",
            previousActiveWorld = null,
            phase = WorldImportPhase.COPYING,
        )

        WorldImportJournalManager.updateJournal(txDir, journalData)

        val log = mutableListOf<String>()
        val result = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot) { log.add(it) }

        assertTrue(result.success)
        assertEquals(1, result.recoveredTransactions)
        assertFalse(txDir.exists())
    }

    @Test
    fun testRecoverIncompleteTransaction_oldWorldMoved_restoresOriginal() {
        val serverRoot = tempFolder.newFolder("serverRoot")
        val txId = UUID.randomUUID().toString()
        val txDir = File(serverRoot, ".minehost/world-import-transactions/$txId").apply { mkdirs() }

        val activeWorld = File(serverRoot, "worlds/myworld").apply { mkdirs() }
        File(activeWorld, "level.dat").writeText("corrupted-partially-copied-dat")

        val oldWorldBackup = File(txDir, "old-world")
        oldWorldBackup.mkdirs()
        File(oldWorldBackup, "level.dat").writeText("original-dat")

        val journalData = WorldImportJournalData(
            transactionId = txId,
            serverId = "test-server-uuid",
            targetWorldName = "myworld",
            stagingPath = "/tmp/staging",
            finalPath = activeWorld.absolutePath,
            rollbackPath = oldWorldBackup.absolutePath,
            rollbackSidecarPath = "/tmp/rollback-sidecars",
            previousActiveWorld = null,
            phase = WorldImportPhase.PROTECTING_ORIGINAL,
            oldWorldMoveStarted = true,
            oldWorldMoved = true,
        )

        WorldImportJournalManager.updateJournal(txDir, journalData)

        val log = mutableListOf<String>()
        val result = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot) { log.add(it) }

        assertTrue(result.success)
        assertEquals(1, result.recoveredTransactions)
        assertEquals("original-dat", File(activeWorld, "level.dat").readText())
        assertFalse(txDir.exists())
    }
    @Test
    fun quarantinedTransactionContinuesBlockingLaterRecoveryCalls() {
        val serverRoot = tempFolder.newFolder("quarantine-server")
        val txDir = File(
            serverRoot,
            ".minehost/world-import-transactions/missing-journal",
        ).apply { mkdirs() }
        File(txDir, "rollback-evidence.txt").writeText("keep-me")

        val first = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
        assertFalse(first.success)
        assertEquals(1, first.blockedTransactions.size)
        val quarantined = File(first.blockedTransactions.single())
        assertTrue(quarantined.isDirectory)
        assertEquals("keep-me", File(quarantined, "rollback-evidence.txt").readText())

        val second = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
        assertFalse(second.success)
        assertTrue(second.blockedTransactions.contains(quarantined.absolutePath))
        assertTrue(
            WorldImportJournalManager.hasIncompleteTransactionForWorld(
                serverRoot,
                "any-world",
            )
        )
    }

    @Test
    fun activeWorldRecoveryPreservesOtherServerProperties() {
        val serverRoot = tempFolder.newFolder("properties-server")
        val properties = File(serverRoot, "server.properties")
        properties.writeText(
            "server-port=19132\n" +
                "level-name=new-world\n" +
                "max-players=20\n",
        )
        val txId = UUID.randomUUID().toString()
        val txDir = File(
            serverRoot,
            ".minehost/world-import-transactions/$txId",
        ).apply { mkdirs() }

        WorldImportJournalManager.updateJournal(
            txDir,
            WorldImportJournalData(
                transactionId = txId,
                serverId = "server",
                targetWorldName = "new-world",
                stagingPath = File(txDir, "staging").absolutePath,
                finalPath = File(serverRoot, "worlds/new-world").absolutePath,
                rollbackPath = null,
                rollbackSidecarPath = null,
                previousActiveWorld = "old-world",
                phase = WorldImportPhase.SETTING_ACTIVE_WORLD,
                activeWorldChangeStarted = true,
            ),
        )

        val result = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
        assertTrue(result.success)
        val restored = java.util.Properties().apply {
            properties.inputStream().use(::load)
        }
        assertEquals("old-world", restored.getProperty("level-name"))
        assertEquals("19132", restored.getProperty("server-port"))
        assertEquals("20", restored.getProperty("max-players"))
    }

    @Test
    fun unreadableServerPropertiesAreQuarantinedInsteadOfOverwritten() {
        val serverRoot = tempFolder.newFolder("unreadable-properties-server")
        File(serverRoot, "server.properties").mkdirs()
        val txId = UUID.randomUUID().toString()
        val txDir = File(
            serverRoot,
            ".minehost/world-import-transactions/$txId",
        ).apply { mkdirs() }

        WorldImportJournalManager.updateJournal(
            txDir,
            WorldImportJournalData(
                transactionId = txId,
                serverId = "server",
                targetWorldName = "new-world",
                stagingPath = File(txDir, "staging").absolutePath,
                finalPath = File(serverRoot, "worlds/new-world").absolutePath,
                rollbackPath = null,
                rollbackSidecarPath = null,
                previousActiveWorld = "old-world",
                phase = WorldImportPhase.SETTING_ACTIVE_WORLD,
                activeWorldChangeStarted = true,
            ),
        )

        val result = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
        assertFalse(result.success)
        assertTrue(File(serverRoot, "server.properties").isDirectory)
        assertEquals(1, result.blockedTransactions.size)
    }

}
