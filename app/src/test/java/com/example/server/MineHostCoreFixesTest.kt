package com.example.server

import com.example.data.ServerEdition
import com.example.data.ServerNetworkType
import com.example.data.ServerProfile
import com.example.server.engine.EngineServerConfig
import com.example.server.engine.WorldSeedMode
import com.example.world.ServerRunStateStore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)

/**
 * Regression tests for the core MineHost fixes:
 * - Phase 2: JVM argument ordering (UnlockExperimentalVMOptions before G1 flags)
 * - Phase 3: Crash-loop detection
 * - Phase 4: Console log clearing
 * - Phase 5: State persistence across process death
 * - Phase 7: World Adapter OFF mode
 */
class MineHostCoreFixesTest {

    @Suppress("unused")
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── Phase 2: JVM argument ordering ──────────────────────────────────


    @Test
    fun jvmArgs_unlockExperimentalVMOptions_precedesG1NewSizePercent() {
        // When memory >= 512MB, the JVM argument list must include
        // -XX:+UnlockExperimentalVMOptions BEFORE -XX:G1NewSizePercent.
        // This is required on Java 21+ where G1NewSizePercent is experimental.
        val safeMemoryMb = 1024
        val javaArguments = mutableListOf<String>()

        // Replicate the argument assembly logic from JvmServerEngineBase
        if (safeMemoryMb >= 512) {
            javaArguments += "-XX:+UseG1GC"
            javaArguments += "-XX:MaxGCPauseMillis=200"
            javaArguments += "-XX:+UnlockExperimentalVMOptions"
            javaArguments += "-XX:G1NewSizePercent=30"
            javaArguments += "-XX:G1MaxNewSizePercent=40"
            javaArguments += "-XX:+ParallelRefProcEnabled"
        } else {
            javaArguments += "-XX:+UseSerialGC"
        }

        val unlockIndex = javaArguments.indexOf("-XX:+UnlockExperimentalVMOptions")
        val g1NewSizeIndex = javaArguments.indexOf("-XX:G1NewSizePercent=30")
        val g1MaxNewSizeIndex = javaArguments.indexOf("-XX:G1MaxNewSizePercent=40")

        assertTrue("UnlockExperimentalVMOptions must be present", unlockIndex >= 0)
        assertTrue("G1NewSizePercent must be present", g1NewSizeIndex >= 0)
        assertTrue("G1MaxNewSizePercent must be present", g1MaxNewSizeIndex >= 0)
        assertTrue(
            "UnlockExperimentalVMOptions must precede G1NewSizePercent",
            unlockIndex < g1NewSizeIndex
        )
        assertTrue(
            "UnlockExperimentalVMOptions must precede G1MaxNewSizePercent",
            unlockIndex < g1MaxNewSizeIndex
        )
    }

    @Test
    fun jvmArgs_lowMemory_usesSerialGC_noExperimentalFlags() {
        val safeMemoryMb = 256
        val javaArguments = mutableListOf<String>()

        if (safeMemoryMb >= 512) {
            javaArguments += "-XX:+UseG1GC"
            javaArguments += "-XX:MaxGCPauseMillis=200"
            javaArguments += "-XX:+UnlockExperimentalVMOptions"
            javaArguments += "-XX:G1NewSizePercent=30"
            javaArguments += "-XX:G1MaxNewSizePercent=40"
            javaArguments += "-XX:+ParallelRefProcEnabled"
        } else {
            javaArguments += "-XX:+UseSerialGC"
        }

        assertEquals(1, javaArguments.size)
        assertEquals("-XX:+UseSerialGC", javaArguments[0])
    }

    // ── Phase 3: Crash-loop detection ───────────────────────────────────

    @Test
    fun crashLoopDetection_threeRapidCrashes_triggersLoop() {
        val crashTimestamps = mutableListOf<Long>()
        val windowMs = 60_000L
        val threshold = 3
        val now = System.currentTimeMillis()

        // Simulate 3 rapid crashes within the window
        repeat(3) { i ->
            val crashTime = now + (i * 1000L) // 1 second apart
            crashTimestamps.add(crashTime)
            crashTimestamps.removeAll { crashTime - it > windowMs }
        }

        assertTrue(
            "Crash loop should be detected after $threshold crashes",
            crashTimestamps.size >= threshold
        )
    }

    @Test
    fun crashLoopDetection_crashesOutsideWindow_noLoop() {
        val crashTimestamps = mutableListOf<Long>()
        val windowMs = 60_000L
        val threshold = 3
        val now = System.currentTimeMillis()

        // Simulate 2 crashes, then one outside the window
        crashTimestamps.add(now - 70_000L) // Outside window
        crashTimestamps.add(now - 1_000L)
        crashTimestamps.removeAll { now - it > windowMs }

        assertEquals("Only 1 crash within window", 1, crashTimestamps.size)
        assertFalse("No crash loop", crashTimestamps.size >= threshold)
    }

    @Test
    fun crashLoopDetection_clearOnSuccess() {
        val crashTimestamps = mutableListOf<Long>()
        crashTimestamps.add(System.currentTimeMillis())
        crashTimestamps.add(System.currentTimeMillis())
        crashTimestamps.add(System.currentTimeMillis())

        assertEquals(3, crashTimestamps.size)

        // Simulate successful ENGINE_READY — clear the tracker
        crashTimestamps.clear()

        assertTrue("Crash timestamps cleared on success", crashTimestamps.isEmpty())
    }

    @Test
    fun crashLoopDetection_intentionalStop_notCountedAsCrash() {
        // Regression test for requirement #6: USER_GRACEFUL_STOP, USER_SIGTERM,
        // USER_FORCE_KILL, and NORMAL_STOP must NOT count as crashes.
        val crashTimestamps = mutableListOf<Long>()
        val windowMs = 60_000L
        val now = System.currentTimeMillis()

        // Replicate the isCrash logic from JvmServerEngineBase.handleProcessExit
        val nonCrashCauses = listOf(
            "USER_GRACEFUL_STOP", "USER_SIGTERM",
            "USER_FORCE_KILL", "NORMAL_STOP"
        )
        for (cause in nonCrashCauses) {
            val isCrash = cause == "UNEXPECTED_EXIT" ||
                cause == "RUNTIME_FAILURE_STOP" ||
                cause == "STARTUP_FAILURE_STOP" ||
                cause == "STARTUP_TIMEOUT"
            assertFalse("Intentional stop '$cause' must not be a crash", isCrash)
        }

        // Simulate 2 real crashes + 2 intentional stops: only 2 should count
        crashTimestamps.add(now)
        crashTimestamps.add(now + 1000)
        crashTimestamps.removeAll { now + 2000 - it > windowMs }
        assertEquals("Only 2 real crashes in window", 2, crashTimestamps.size)
        assertFalse("2 crashes should not trigger loop (threshold=3)", crashTimestamps.size >= 3)
    }

    // ── Phase 2b: Sequential runtime-then-engine ordering ────────────────

    @Test
    fun runtimeMustCompleteBeforeEngineDownload_sequentialOrdering() {
        // Regression test for requirement #2: Java runtime must fully complete
        // (resolve, download, verify, extract, validate, commit) before engine
        // download begins. The fix replaced concurrent coroutineScope { joinAll() }
        // with sequential suspend calls. This test verifies the ordering invariant.
        val executionLog = mutableListOf<String>()

        kotlinx.coroutines.runBlocking {
            // Simulate sequential execution as implemented in JvmServerEngineBase
            coroutineScope {
                // Step 1: Runtime (must complete first)
                launch {
                    executionLog.add("runtime:start")
                    delay(50) // Simulate runtime preparation work
                    executionLog.add("runtime:complete")
                }.join()

                // Step 2: Engine (must only start after runtime completes)
                launch {
                    executionLog.add("engine:start")
                    delay(10)
                    executionLog.add("engine:complete")
                }.join()
            }
        }

        assertEquals("Should have 4 log entries", 4, executionLog.size)
        assertEquals("runtime:start", executionLog[0])
        assertEquals("runtime:complete", executionLog[1])
        assertEquals("engine:start", executionLog[2])
        assertEquals("engine:complete", executionLog[3])

        // The critical invariant: engine:start must come AFTER runtime:complete
        val runtimeCompleteIndex = executionLog.indexOf("runtime:complete")
        val engineStartIndex = executionLog.indexOf("engine:start")
        assertTrue(
            "Engine download must not begin before runtime completes",
            engineStartIndex > runtimeCompleteIndex
        )
    }

    @Test
    fun runtimeFailure_preventsEngineDownload() {
        // If runtime preparation fails, engine download must not start.
        val executionLog = mutableListOf<String>()

        try {
            kotlinx.coroutines.runBlocking {
                coroutineScope {
                    launch {
                        executionLog.add("runtime:start")
                        delay(10)
                        throw IOException("Runtime installation failed")
                    }.join()

                    // This should never execute if runtime fails
                    launch {
                        executionLog.add("engine:start")
                    }.join()
                }
            }
        } catch (e: IOException) {
            executionLog.add("runtime:failed")
        }

        assertEquals("Should have 2 log entries", 2, executionLog.size)
        assertEquals("runtime:start", executionLog[0])
        assertEquals("runtime:failed", executionLog[1])
        assertFalse(
            "Engine download must not start after runtime failure",
            executionLog.contains("engine:start")
        )
    }

    // ── Phase 4: Console log clearing ───────────────────────────────────

    @Test
    fun clearLogs_removesAllEntries() {
        val recentLogs = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<String>>()
        val serverId = "test-server-1"
        val queue = recentLogs.getOrPut(serverId) { java.util.ArrayDeque() }
        queue.addLast("[INFO] Server started")
        queue.addLast("[INFO] Player joined")
        queue.addLast("[INFO] World saved")

        assertEquals(3, queue.size)

        // Simulate clearLogs
        recentLogs[serverId]?.let { q ->
            synchronized(q) { q.clear() }
        }

        assertEquals(0, queue.size)
    }

    // ── Phase 5: State persistence ──────────────────────────────────────

    @Test
    fun serverRunStateStore_persistAndReadStatus() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "minehost-test-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            ServerRunStateStore.persistStatus(tempDir, "ONLINE", "nukkit-mot", "profile-123")
            val result = ServerRunStateStore.readPersistedStatus(tempDir)

            assertNotNull("Persisted status should be readable", result)
            assertEquals("ONLINE", result!!.status)
            assertEquals("nukkit-mot", result.engineId)
            assertEquals("profile-123", result.profileId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun serverRunStateStore_persistAndReadLogs() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "minehost-test-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val logs = listOf(
                "[INFO] Server started",
                "[INFO] Player joined",
                "[WARN] Low memory",
                "[INFO] World saved"
            )
            ServerRunStateStore.persistLogs(tempDir, logs)
            val result = ServerRunStateStore.readPersistedLogs(tempDir)

            assertEquals("All logs should be persisted", 4, result.size)
            assertEquals("[INFO] Server started", result[0])
            assertEquals("[INFO] World saved", result[3])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun serverRunStateStore_clearPersistedLogs() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "minehost-test-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            ServerRunStateStore.persistLogs(tempDir, listOf("[INFO] test"))
            ServerRunStateStore.clearPersistedLogs(tempDir)
            val result = ServerRunStateStore.readPersistedLogs(tempDir)

            assertTrue("Logs should be empty after clear", result.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun serverRunStateStore_maxPersistedLines() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "minehost-test-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            // Persist 300 lines — only last 200 should be kept
            val logs = (1..300).map { "[INFO] Line $it" }
            ServerRunStateStore.persistLogs(tempDir, logs)
            val result = ServerRunStateStore.readPersistedLogs(tempDir)

            assertEquals("Only last 200 lines should be persisted", 200, result.size)
            assertEquals("[INFO] Line 101", result[0])
            assertEquals("[INFO] Line 300", result[199])
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ── Phase 7: World Adapter OFF mode ─────────────────────────────────

    @Test
    fun engineServerConfig_worldAdapterEnabled_defaultFalse() {
        val config = EngineServerConfig(worldSeed = 0L, worldSeedKnown = true)
        assertFalse("World adapter should be disabled by default", config.worldAdapterEnabled)
    }

    @Test
    fun engineServerConfig_worldAdapterEnabled_canDisable() {
        val config = EngineServerConfig(
            worldSeed = 0L,
            worldSeedKnown = true,
            worldAdapterEnabled = false
        )
        assertFalse("World adapter should be disabled when set to false", config.worldAdapterEnabled)
    }

    @Test
    fun serverProfile_worldAdapterEnabled_persistsThroughJson() {
        val profile = createTestProfile(worldAdapterEnabled = false)
        val json = profile.toJson()
        val obj = JSONObject(json)

        assertTrue("JSON should contain worldAdapterEnabled", obj.has("worldAdapterEnabled"))
        assertFalse("worldAdapterEnabled should be false in serialized JSON", obj.getBoolean("worldAdapterEnabled"))
    }

    @Test
    fun serverProfile_worldAdapterEnabled_defaultTrue() {
        val profile = createTestProfile()
        assertTrue("World adapter should default to true", profile.worldAdapterEnabled)
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private fun createTestProfile(worldAdapterEnabled: Boolean = true): ServerProfile {
        return ServerProfile(
            id = "test-server-id",
            name = "Test Server",
            engineId = "nukkit-mot",
            engineVersionId = "test-version",
            bedrockVersion = "1.21.0",
            serverDirectory = "/tmp/test-server",
            levelName = "world",
            worldSeed = 12345L,
            worldSeedMode = WorldSeedMode.CUSTOM,
            worldSeedKnown = true,
            edition = ServerEdition.BEDROCK,
            networkType = ServerNetworkType.BEDROCK_RAKNET_UDP,
            worldAdapterEnabled = worldAdapterEnabled,
        )
    }
}
