package com.example.world

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldLaunchOwnershipPolicyTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun engineWillGenerateWhenWorldDoesNotExist() {
        val serverRoot = tempDir.newFolder("server")
        val result = WorldLaunchOwnershipPolicy.classify(serverRoot, "testworld")
        assertEquals(WorldLaunchOwnership.ENGINE_WILL_GENERATE, result.ownership)
    }

    @Test
    fun untrackedRequiresAdoptionWhenBedrockFilesExistWithoutRecords() {
        val serverRoot = tempDir.newFolder("server")
        val worldDir = File(serverRoot, "worlds/testworld").apply { mkdirs() }
        File(worldDir, "level.dat").writeText("data")
        File(worldDir, "db").mkdirs()

        val result = WorldLaunchOwnershipPolicy.classify(serverRoot, "testworld")
        assertEquals(WorldLaunchOwnership.EXTERNAL_ADOPTION_REQUIRED, result.ownership)
    }

    @Test
    fun oneSidedRegistrationIsFlagged() {
        val serverRoot = tempDir.newFolder("server")
        val worldDir = File(serverRoot, "worlds/testworld").apply { mkdirs() }
        File(worldDir, "level.dat").writeText("data")

        ImportedWorldVerificationStore.markPending(
            serverRoot = serverRoot,
            worldName = "testworld",
            metadata = null,
            sourceWorldHash = "a".repeat(64),
            transactionId = "tx-1",
        )

        val result = WorldLaunchOwnershipPolicy.classify(serverRoot, "testworld")
        assertEquals(WorldLaunchOwnership.BROKEN_ONE_SIDED_REGISTRATION, result.ownership)
    }
}
