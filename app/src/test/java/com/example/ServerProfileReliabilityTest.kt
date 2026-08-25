package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ServerCreationDraft
import com.example.data.ServerProfileChanges
import com.example.data.ServerProfileRepository
import com.example.data.StorageResult
import com.example.server.updates.AtomicJsonFileStore
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for server profile persistence and backup recovery reliability.
 */
@RunWith(RobolectricTestRunner::class)
class ServerProfileReliabilityTest {
    private lateinit var context: Context
    private lateinit var serversDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        serversDir = File(context.filesDir, "servers")
        serversDir.deleteRecursively()
        serversDir.mkdirs()
    }

    @Test
    fun concurrentCreatesAreSerializedAndKeepUniquePorts() = runTest {
        val repository = ServerProfileRepository(context)
        repository.loadProfiles()
        val created = (0 until 12).map { index ->
            async {
                repository.createProfile(
                    ServerCreationDraft(
                        name = "Server $index",
                        engineId = "powernukkitx",
                        engineVersionId = "test-version",
                        bedrockVersion = "AUTO",
                        worldSeed = 0L,
                        worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
                        worldSeedKnown = true,
                        port = 30000,
                    ),
                ).getOrThrow()
            }
        }.awaitAll()

        assertEquals(12, created.map { it.id }.toSet().size)
        assertEquals(12, created.map { it.port }.toSet().size)
        assertEquals(12, repository.profiles.value.size)

        val reloaded = ServerProfileRepository(context)
        reloaded.loadProfiles()
        assertEquals(created.map { it.id }.toSet(), reloaded.profiles.value.map { it.id }.toSet())
    }

    @Test
    fun concurrentUpdateAndDeleteCannotResurrectDeletedProfile() = runTest {
        val repository = ServerProfileRepository(context)
        repository.loadProfiles()
        val profile = repository.createProfile(
            ServerCreationDraft(
                name = "Owned",
                engineId = "powernukkitx",
                engineVersionId = "test-version",
                bedrockVersion = "AUTO",
                worldSeed = 0L,
                worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
                worldSeedKnown = true,
                port = 30100,
            ),
        ).getOrThrow()

        val operations = listOf(
            async { repository.updateProfile(profile.id, ServerProfileChanges(name = "Updated")) },
            async { repository.deleteProfile(profile.id, deleteFiles = true) },
        )
        operations.awaitAll()

        val reloaded = ServerProfileRepository(context)
        reloaded.loadProfiles()
        assertFalse(reloaded.profiles.value.any { it.id == profile.id })
        assertFalse(File(serversDir, profile.id).exists())
    }

    @Test
    fun backupRecoveryIsPersistedBackToPrimaryFile() {
        val main = File(serversDir, "profiles.json")
        val backup = File(serversDir, "profiles.json.bak")
        backup.writeText("[]")
        main.writeText("{ invalid json :::")

        val result = AtomicJsonFileStore(main).loadRaw()
        assertTrue("Expected Recovered but got $result", result is StorageResult.Recovered<*>)
        assertEquals("[]", main.readText().trim())
    }

    @Test
    fun profileOptionsRemainPersistedPerUuid() = runTest {
        val repository = ServerProfileRepository(context)
        repository.loadProfiles()
        val profile = repository.createProfile(
            ServerCreationDraft(
                name = "Options",
                engineId = "nukkit-mot",
                engineVersionId = "mot-test",
                bedrockVersion = "AUTO",
                worldSeed = 0L,
                worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
                worldSeedKnown = true,
                port = 30200,
                onlineMode = false,
                autoRestart = false,
                autoBackup = true,
            ),
        ).getOrThrow()

        val reloaded = ServerProfileRepository(context)
        reloaded.loadProfiles()
        val restored = reloaded.profiles.value.single { it.id == profile.id }
        assertFalse(restored.onlineMode)
        assertFalse(restored.autoRestart)
        assertTrue(restored.autoBackup)
    }

    @Test
    fun sessionTrackingFieldsArePersisted() = runTest {
        val repository = ServerProfileRepository(context)
        repository.loadProfiles()
        val profile = repository.createProfile(
            ServerCreationDraft(
                name = "Session Test",
                engineId = "nukkit-mot",
                engineVersionId = "mot-test",
                bedrockVersion = "AUTO",
                worldSeed = 0L,
                worldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM,
                worldSeedKnown = true,
                port = 30300,
            ),
        ).getOrThrow()

        val sessionId = java.util.UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        repository.updateProfile(
            profile.id,
            ServerProfileChanges(
                lastStartedSessionId = sessionId,
                lastStartedAt = startTime
            )
        ).getOrThrow()

        val reloaded = ServerProfileRepository(context)
        reloaded.loadProfiles()
        val restored = reloaded.profiles.value.single { it.id == profile.id }
        assertEquals(sessionId, restored.lastStartedSessionId)
        assertEquals(startTime, restored.lastStartedAt)
    }
}
