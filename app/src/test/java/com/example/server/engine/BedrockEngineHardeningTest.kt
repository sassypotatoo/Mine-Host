package com.example.server.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.ServerStatus
import com.example.server.health.HealthEvent
import com.example.server.version.EngineVersion
import com.example.server.version.LaunchMode
import com.example.server.version.ReleaseChannel
import com.example.server.version.VersionSourceType
import com.example.world.SeedParseResult
import com.example.world.WorldLaunchOwnership
import com.example.world.WorldLaunchOwnershipPolicy
import com.example.world.WorldSeedFactory
import com.example.world.WorldSeedParser
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class StubProcess(
    initialAlive: Boolean = true,
) : Process() {
    @Volatile
    private var alive = initialAlive

    override fun getOutputStream() =
        java.io.OutputStream.nullOutputStream()

    override fun getInputStream() =
        java.io.InputStream.nullInputStream()

    override fun getErrorStream() =
        java.io.InputStream.nullInputStream()

    override fun waitFor(): Int {
        alive = false
        return 0
    }

    override fun waitFor(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        alive = false
        return true
    }

    override fun exitValue(): Int {
        if (alive) {
            throw IllegalThreadStateException(
                "Process is still alive"
            )
        }
        return 0
    }

    override fun destroy() {
        alive = false
    }

    override fun destroyForcibly(): Process {
        alive = false
        return this
    }

    override fun isAlive(): Boolean = alive
}

@RunWith(RobolectricTestRunner::class)
class BedrockEngineHardeningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var serverDir: File
    private lateinit var engineVersion: EngineVersion

    private val port = 19132
    private val profileId = "test-profile"
    private val runtimeSessionId = "test-session"

    @Before
    fun setup() {
        context =
            ApplicationProvider.getApplicationContext()
        serverDir = tempFolder.newFolder("server")
        engineVersion = newEngineVersion()
    }

    private fun newEngineVersion(): EngineVersion =
        EngineVersion(
            id = "v1",
            engineId = "bedrock_power_nukkit",
            versionName = "1.0",
            displayName = "PowerNukkit 1.0",
            channel = ReleaseChannel.STABLE,
            downloadUrl =
                "https://example.com/server.jar",
            jarFileName = "server.jar",
            requiredJavaVersion = 21,
            compatibilityLabel = "1.20.0",
            recommended = true,
            sourceType =
                VersionSourceType.JENKINS_BUILD,
            launchMode = LaunchMode.JAVA_JAR,
            sha256 = "a".repeat(64),
        )

    private fun newSession(
        id: String = runtimeSessionId,
        alive: Boolean = true,
    ): ServerProcessSession =
        ServerProcessSession(
            sessionId = id,
            process = StubProcess(alive),
            profileId = profileId,
            engineId = engineVersion.engineId,
            port = port,
            startedAt = 0L,
        )

    private open class TestBedrockEngine(
        context: Context,
        serverDir: File,
        engineVersion: EngineVersion,
        port: Int,
        profileId: String,
        runtimeSessionId: String,
        serverConfig: EngineServerConfig =
            EngineServerConfig(
                port = port,
                worldSeed = 0L,
                worldSeedKnown = true,
            ),
        onLog: (String) -> Unit = {},
        onStatusChange: (ServerStatus) -> Unit = {},
    ) : BedrockJavaEngineBase(
        context = context,
        serverDir = serverDir,
        engineVersion = engineVersion,
        bedrockVersion = "1.20.0",
        port = port,
        profileId = profileId,
        runtimeSessionId = runtimeSessionId,
        serverConfig = serverConfig,
        onLog = onLog,
        onStatusChange = onStatusChange,
    ) {
        var ensureIdentityResult = true
        var onlineMetadataCommitResult = true
        var cleanupHookCalls = 0

        override fun getEngineId(): String =
            engineVersion.engineId

        override suspend fun ensurePreparedWorldIdentity():
            Boolean = ensureIdentityResult

        override fun commitOnlineMetadata(
            readyAtMillis: Long,
        ): Boolean = onlineMetadataCommitResult

        override suspend fun onCleanupProcessHook(
            session: ServerProcessSession,
            exitCode: Int,
        ) {
            cleanupHookCalls += 1
            super.onCleanupProcessHook(
                session,
                exitCode,
            )
        }

        fun installCurrentSession(
            session: ServerProcessSession,
        ) {
            currentSession = session
        }

        fun currentSessionIdForTest(): String? =
            currentSession?.sessionId

        fun setStatusForTest(status: ServerStatus) {
            healthMonitor.setStatus(status)
        }

        fun timeoutWouldStop(
            session: ServerProcessSession,
        ): Boolean =
            shouldStopForStartupTimeout(session)

        fun createProcessSessionIdForTest(): String =
            createProcessSessionId()

        fun tryBeginWorldIdentityForTest(
            session: ServerProcessSession,
        ): Boolean =
            tryBeginWorldIdentityVerification(session)

        suspend fun handleProcessExitForTest(
            session: ServerProcessSession,
            exitCode: Int,
        ) {
            handleProcessExit(session, exitCode)
        }

        public override fun maybeMarkBedrockOnline(
            session: ServerProcessSession,
        ) {
            super.maybeMarkBedrockOnline(session)
        }

        public override fun onHealthEvent(
            event: HealthEvent,
            line: String,
            session: ServerProcessSession,
        ) {
            super.onHealthEvent(
                event,
                line,
                session,
            )
        }
    }

    private fun readySession(
        id: String = runtimeSessionId,
    ): ServerProcessSession =
        newSession(id = id, alive = true).apply {
            engineReady = true
            networkReady = true
            boundPort = port
            pingResult = BedrockServerPingResult(
                responded = true,
                portV4 = port,
            )
            worldIdentityVerified = true
        }

    @Test
    fun portParserSupportsIpv4AndIpv6() {
        assertEquals(
            19132,
            BedrockBoundPortParser.parse(
                "Opening server on 0.0.0.0:19132"
            ),
        )
        assertEquals(
            19132,
            BedrockBoundPortParser.parse(
                "Opening server on 127.0.0.1:19132"
            ),
        )
        assertEquals(
            19132,
            BedrockBoundPortParser.parse(
                "Opening server on [::]:19132"
            ),
        )
        assertEquals(
            19132,
            BedrockBoundPortParser.parse(
                "Opening server on " +
                    "[0:0:0:0:0:0:0:0]:19132"
            ),
        )
        assertNull(
            BedrockBoundPortParser.parse(
                "Some other log line"
            )
        )
        assertNull(
            BedrockBoundPortParser.parse(
                "Opening server on " +
                    "0.0.0.0:invalid"
            )
        )
    }

    @Test
    fun unsafeWorldNamesRemainBlocked() {
        val decision1 =
            WorldLaunchOwnershipPolicy.classify(
                serverDir,
                "../outside",
            )
        assertEquals(
            WorldLaunchOwnership.UNKNOWN,
            decision1.ownership,
        )
        assertTrue(
            decision1.reasons.contains(
                "Unsafe world name"
            )
        )

        val decision2 =
            WorldLaunchOwnershipPolicy.classify(
                serverDir,
                "world/nested",
            )
        assertEquals(
            WorldLaunchOwnership.UNKNOWN,
            decision2.ownership,
        )

        val decision3 =
            WorldLaunchOwnershipPolicy.classify(
                serverDir,
                "  ",
            )
        assertEquals(
            WorldLaunchOwnership.UNKNOWN,
            decision3.ownership,
        )
    }

    @Test
    fun fatalStatusIsNotOverwrittenByStopping() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = newSession(alive = false)

        engine.installCurrentSession(session)
        engine.setStatusForTest(
            ServerStatus.PROTOCOL_MISMATCH
        )

        engine.onHealthEvent(
            HealthEvent.SERVER_STOPPING,
            "Stopping server",
            session,
        )

        assertEquals(
            ServerStatus.PROTOCOL_MISMATCH,
            engine.getStatus(),
        )
    }

    @Test
    fun readinessCandidateAloneCannotSetEngineReady() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = newSession()

        engine.installCurrentSession(session)

        engine.onHealthEvent(
            HealthEvent.READINESS_CANDIDATE,
            "Query is running",
            session,
        )

        assertFalse(session.engineReady)
        assertNotEquals(
            ServerStatus.ONLINE,
            engine.getStatus(),
        )
    }

    @Test
    fun missingBoundPortEvidenceCannotReachOnline() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )

        val session =
            readySession().apply {
                boundPort = null
            }

        engine.installCurrentSession(session)
        engine.maybeMarkBedrockOnline(session)

        assertFalse(session.onlineModeConfirmed)
        assertNotEquals(
            ServerStatus.ONLINE,
            engine.getStatus(),
        )
    }

    @Test
    fun everyReadinessGateReachesOnline() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = readySession()

        engine.installCurrentSession(session)
        engine.maybeMarkBedrockOnline(session)

        assertTrue(session.onlineModeConfirmed)
        assertEquals(
            ServerStatus.ONLINE,
            engine.getStatus(),
        )
        assertEquals(
            "NORMAL_RUN",
            session.runtimeFailureClass,
        )
        assertTrue(
            session.reachedOnlineAtMillis != null
        )
    }

    @Test
    fun wrongBoundPortBecomesPortMismatch() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = newSession(alive = false)

        engine.installCurrentSession(session)

        engine.onHealthEvent(
            HealthEvent.BOUND_PORT_DETECTED,
            "Opening server on 0.0.0.0:19133",
            session,
        )

        assertEquals(
            19133,
            session.boundPort,
        )
        assertEquals(
            ServerStatus.PORT_MISMATCH,
            engine.getStatus(),
        )
        assertFalse(session.onlineModeConfirmed)
    }

    @Test
    fun staleSessionCannotMutateNewSession() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val oldSession =
            newSession(id = "old-session")
        val newSession =
            newSession(id = "new-session")

        engine.installCurrentSession(newSession)

        engine.onHealthEvent(
            HealthEvent.BOUND_PORT_DETECTED,
            "Opening server on 0.0.0.0:19133",
            oldSession,
        )
        engine.maybeMarkBedrockOnline(oldSession)

        assertNull(oldSession.boundPort)
        assertFalse(oldSession.onlineModeConfirmed)
        assertEquals(
            "new-session",
            engine.currentSessionIdForTest(),
        )
        assertNotEquals(
            ServerStatus.PORT_MISMATCH,
            engine.getStatus(),
        )
        assertNotEquals(
            ServerStatus.ONLINE,
            engine.getStatus(),
        )
    }

    @Test
    fun engineFirstOrderReachesOnlineOnlyAfterNetwork() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = newSession().apply {
            engineReady = true
            worldIdentityVerified = true
        }

        engine.installCurrentSession(session)
        engine.maybeMarkBedrockOnline(session)

        assertFalse(session.onlineModeConfirmed)

        session.networkReady = true
        session.boundPort = port
        session.pingResult =
            BedrockServerPingResult(
                responded = true,
                portV4 = port,
            )

        engine.maybeMarkBedrockOnline(session)

        assertTrue(session.onlineModeConfirmed)
        assertEquals(
            ServerStatus.ONLINE,
            engine.getStatus(),
        )
    }

    @Test
    fun networkFirstOrderReachesOnlineOnlyAfterEngine() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = newSession().apply {
            networkReady = true
            boundPort = port
            pingResult =
                BedrockServerPingResult(
                    responded = true,
                    portV4 = port,
                )
            worldIdentityVerified = true
        }

        engine.installCurrentSession(session)
        engine.maybeMarkBedrockOnline(session)

        assertFalse(session.onlineModeConfirmed)

        session.engineReady = true
        engine.maybeMarkBedrockOnline(session)

        assertTrue(session.onlineModeConfirmed)
        assertEquals(
            ServerStatus.ONLINE,
            engine.getStatus(),
        )
    }

    @Test
    fun onlyOneWorldIdentityCommitCanBegin() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val session = newSession()

        engine.installCurrentSession(session)

        val workers = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val results =
            Collections.synchronizedList(
                mutableListOf<Boolean>()
            )
        val executor =
            Executors.newFixedThreadPool(4)

        repeat(workers) {
            executor.execute {
                start.await()
                results +=
                    engine.tryBeginWorldIdentityForTest(
                        session
                    )
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(
            done.await(5, TimeUnit.SECONDS)
        )
        executor.shutdownNow()

        assertEquals(
            1,
            results.count { it },
        )
    }

    @Test
    fun oldTimeoutCannotTargetNewSession() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )
        val oldSession =
            newSession(id = "old-session")
        val newSession =
            newSession(id = "new-session")

        engine.installCurrentSession(newSession)
        engine.setStatusForTest(
            ServerStatus.STARTING
        )

        assertFalse(
            engine.timeoutWouldStop(oldSession)
        )
        assertTrue(
            engine.timeoutWouldStop(newSession)
        )
    }

    @Test
    fun twoLaunchesReceiveDifferentProcessSessionIds() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        )

        val first =
            engine.createProcessSessionIdForTest()
        val second =
            engine.createProcessSessionIdForTest()

        assertNotEquals(first, second)
        assertTrue(
            first.startsWith(
                "$runtimeSessionId:"
            )
        )
        assertTrue(
            second.startsWith(
                "$runtimeSessionId:"
            )
        )
    }

    @Test
    fun staleProcessExitStillRunsItsCleanup() =
        runTest {
            val engine = TestBedrockEngine(
                context,
                serverDir,
                engineVersion,
                port,
                profileId,
                runtimeSessionId,
            )

            val oldSession =
                newSession(
                    id = "old-session",
                    alive = false,
                )
            val newSession =
                newSession(
                    id = "new-session",
                    alive = true,
                )

            engine.installCurrentSession(newSession)
            engine.setStatusForTest(
                ServerStatus.ONLINE
            )

            engine.handleProcessExitForTest(
                oldSession,
                0,
            )

            assertEquals(
                1,
                engine.cleanupHookCalls,
            )
            assertEquals(
                "new-session",
                engine.currentSessionIdForTest(),
            )
            assertEquals(
                ServerStatus.ONLINE,
                engine.getStatus(),
            )
            assertEquals(
                TerminationCause.UNEXPECTED_EXIT,
                oldSession.terminationCause,
            )
        }

    @Test
    fun onlineMetadataFailureRemainsFailClosed() {
        val engine = TestBedrockEngine(
            context,
            serverDir,
            engineVersion,
            port,
            profileId,
            runtimeSessionId,
        ).apply {
            onlineMetadataCommitResult = false
        }

        val session = readySession()
        engine.installCurrentSession(session)

        engine.maybeMarkBedrockOnline(session)

        assertFalse(session.onlineModeConfirmed)
        assertEquals(
            ServerStatus.FAILED,
            engine.getStatus(),
        )
        assertEquals(
            "ONLINE_METADATA_COMMIT_FAILED",
            session.runtimeFailureClass,
        )
        assertTrue(
            session.fatalErrorMessage
                ?.contains(
                    "durably committed"
                ) == true
        )
    }

    @Test
    fun worldSeedParserAcceptsOnlyValidLongs() {
        assertTrue(
            WorldSeedParser.parse("12345") is
                SeedParseResult.Valid
        )
        assertEquals(
            12345L,
            (
                WorldSeedParser.parse("12345") as
                    SeedParseResult.Valid
                ).value,
        )
        assertTrue(
            WorldSeedParser.parse("-9876543210") is
                SeedParseResult.Valid
        )
        assertTrue(
            WorldSeedParser.parse("+123") is
                SeedParseResult.Invalid
        )
        assertTrue(
            WorldSeedParser.parse("12 34") is
                SeedParseResult.Invalid
        )
        assertTrue(
            WorldSeedParser.parse("abc") is
                SeedParseResult.Invalid
        )
    }

    @Test
    fun worldSeedFactoryGeneratesDistinctSeeds() {
        val first = WorldSeedFactory.next()
        val second = WorldSeedFactory.next(first)
        assertNotEquals(first, second)
    }
}
