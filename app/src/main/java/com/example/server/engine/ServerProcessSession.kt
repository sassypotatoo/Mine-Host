package com.example.server.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import com.example.world.RuntimeMappingDiagnostics

data class ServerProcessSession(
    val sessionId: String,
    @Volatile var process: Process? = null,
    val profileId: String,
    val engineId: String,
    val port: Int,
    val startedAt: Long,
    @Volatile var terminationCause: TerminationCause = TerminationCause.NONE,
    @Volatile var networkReady: Boolean = false,
    @Volatile var engineReady: Boolean = false,
    @Volatile var onlineModeConfirmed: Boolean = false,
    @Volatile var boundPort: Int? = null,
    @Volatile var incompatibleWorldDetected: Boolean = false,
    @Volatile var fatalErrorMessage: String? = null,
    @Volatile var importedWorldVerified: Boolean = false,
    @Volatile var provisionalWorldLoaded: Boolean = false,
    @Volatile var joinedPlayerName: String? = null,
    @Volatile var importedPlayerPositionApplied: Boolean = false,
    val compatibilityErrors: MutableList<String> = mutableListOf(),
    val bootstrapState: MutableMap<String, Int> = mutableMapOf(),
    val bootstrapMutex: Mutex = Mutex(),
    val sessionMutex: Mutex = Mutex(),
    @Volatile var probeJob: Job? = null,
    @Volatile var startupTimeoutJob: Job? = null,
    val cleanupStarted: AtomicBoolean = AtomicBoolean(false),
    @Volatile var pingResult: BedrockServerPingResult? = null,
    val worldIdentityCommitStarted: AtomicBoolean = AtomicBoolean(false),
    val onlineCommitStarted: AtomicBoolean = AtomicBoolean(false),
    @Volatile var worldIdentityVerified: Boolean = false,
    @Volatile var worldIdentityJob: Job? = null,
    @Volatile var reachedOnlineAtMillis: Long? = null,
    @Volatile var runtimeFailureClass: String? = null,
    val pendingImportedWorldName: String? = null,
    val activeWorldName: String? = null,
    val preparedDiagnosticSummary: RuntimeMappingDiagnostics.DiagnosticSummary? = null,
    val protectedCopyResumed: Boolean = false,
    val expectedDimensions: Set<Int> = emptySet(),
    @Volatile var protocolExpectationLogged: Boolean = false,
)
