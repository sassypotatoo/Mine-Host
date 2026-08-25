package com.example.world

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedWorldVerificationEvidenceTest {
    @Test fun incompleteEvidenceCannotVerify() {
        val evidence = completeEvidence().copy(
            selectedChunksLoaded = 3,
            unsupportedMappings = listOf("runtime 11893"),
            cleanRestartCompleted = false,
        )
        assertFalse(evidence.verified)
        assertTrue(evidence.failures().any { it.contains("runtime 11893") })
    }

    @Test fun completeEvidenceCanVerify() {
        assertTrue(completeEvidence().verified)
    }

    private fun completeEvidence() = ProtectedWorldVerificationEvidence(
        expectedDimensions = setOf(0, 1),
        loadedDimensions = setOf(0, 1),
        selectedChunksRequested = 4,
        selectedChunksLoaded = 4,
        playerJoined = true,
        playerSpawnAreaLoaded = true,
        playerPositionMigrationRequired = true,
        playerPositionMigrationApplied = true,
        playerInventoryMigrationRequired = true,
        playerInventoryMigrationApplied = true,
        fatalWorldErrors = emptyList(),
        unsupportedMappings = emptyList(),
        unsafeWritesDetected = false,
        cleanRestartCompleted = true,
    )
}
