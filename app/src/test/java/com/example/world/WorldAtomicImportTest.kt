package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldAtomicImportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testStartTransactionAndProtectionLifecycle() {
        val serverRoot = tempFolder.newFolder("serverRoot")

        val journal = WorldImportJournalManager.startTransaction(
            serverRoot = serverRoot,
            serverId = "local",
            sourceName = "my_world.mcworld",
            targetWorldName = "my_world",
            previousActiveWorld = "world",
            replaceActive = false,
        )

        assertNotNull(journal.transactionId)
        assertEquals(WorldImportPhase.PREPARING, journal.phase)
        assertEquals("my_world", journal.targetWorldName)

        val txDir = File(WorldImportJournalManager.transactionRoot(serverRoot), journal.transactionId)
        assertTrue(txDir.isDirectory)

        val readJournal = WorldImportJournalManager.readJournalFromTxDir(txDir)
        assertNotNull(readJournal)
        assertEquals(journal.transactionId, readJournal?.transactionId)

        // Verify journal clean recovery
        val recovery = WorldImportJournalManager.recoverIncompleteTransactions(serverRoot)
        assertTrue(recovery.success)
        assertEquals(1, recovery.recoveredTransactions)
    }
}
