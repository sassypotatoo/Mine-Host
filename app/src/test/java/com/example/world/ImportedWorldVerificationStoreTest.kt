package com.example.world

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedWorldVerificationStoreTest {
    @Test fun failedWorldCannotBePromotedByLateReadiness() {
        val root = Files.createTempDirectory("minehost-world-marker").toFile()
        try {
            assertTrue(ImportedWorldVerificationStore.markPending(root, "world", null))
            assertTrue(ImportedWorldVerificationStore.markFailed(root, "world", "No runtime2legacy mapping for 11893"))
            assertFalse(ImportedWorldVerificationStore.markProvisionallyLoaded(root, "world", "nukkit-mot:1361"))
            assertFalse(ImportedWorldVerificationStore.markVerified(root, "world", "nukkit-mot:1361"))
            assertEquals(ImportedWorldVerificationState.FAILED, ImportedWorldVerificationStore.state(root, "world"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun verificationRequiresProvisionalState() {
        val root = Files.createTempDirectory("minehost-world-marker").toFile()
        try {
            assertTrue(ImportedWorldVerificationStore.markPending(root, "world", null))
            assertFalse(ImportedWorldVerificationStore.markVerified(root, "world", "nukkit-mot:1361"))
            assertTrue(ImportedWorldVerificationStore.markProvisionallyLoaded(root, "world", "nukkit-mot:1361"))
            assertTrue(ImportedWorldVerificationStore.markVerified(root, "world", "nukkit-mot:1361"))
            assertEquals(ImportedWorldVerificationState.VERIFIED, ImportedWorldVerificationStore.state(root, "world"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun renameAndDeleteFollowTheWorldLifecycle() {
        val root = Files.createTempDirectory("minehost-world-marker-lifecycle").toFile()
        try {
            assertTrue(ImportedWorldVerificationStore.markPending(root, "old", null))
            assertTrue(ImportedWorldVerificationStore.rename(root, "old", "new"))
            assertEquals(null, ImportedWorldVerificationStore.state(root, "old"))
            assertEquals(ImportedWorldVerificationState.PENDING, ImportedWorldVerificationStore.state(root, "new"))
            assertTrue(ImportedWorldVerificationStore.delete(root, "new"))
            assertEquals(null, ImportedWorldVerificationStore.state(root, "new"))
        } finally {
            root.deleteRecursively()
        }
    }
}
