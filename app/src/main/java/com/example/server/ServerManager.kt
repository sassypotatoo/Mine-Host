package com.example.server

import android.content.Context
import com.example.data.ActiveServerRuntimeState
import com.example.data.PortTransport
import com.example.data.ServerProfile
import com.example.data.ServerRuntimeEvent
import com.example.server.engine.TerminationCause
import com.example.data.StartServerResult
import com.example.data.portTransport
import com.example.server.engine.ServerEngine
import com.example.server.engine.BedrockNetworkSnapshot
import com.example.server.engine.JavaNetworkSnapshot
import com.example.server.players.EnginePlayerListParser
import com.example.server.template.ServerTemplate
import com.example.server.template.TemplateRegistry
import com.example.server.version.EngineCompatibilityValidator
import com.example.server.version.EngineVersion
import com.example.server.version.EngineVersionCatalogRepository
import com.example.tunnel.TunnelManager
import com.example.tunnel.FrpTunnelConfig
import com.example.tunnel.TunnelStatus
import com.example.tunnel.TunnelHealth
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.UUID
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Handler
import android.os.Looper

/**
 * Coordinates independent real server runtimes keyed by immutable profile UUID.
 * UI selection does not own a process. Every profile can keep its own engine,
 * process session, console callback, metrics source and tunnel session.
 */
class ServerManager(
    private val context: Context,
    private val versionCatalog: EngineVersionCatalogRepository? = null,
) {
    private data class RuntimeHandle(
        val serverId: String,
        val sessionId: String,
        val port: Int,
        val transport: PortTransport,
        val engineId: String,
        val engineVersionId: String,
        val engine: ServerEngine,
        val tunnelManager: TunnelManager
    )

    private data class PlayerTrackerState(
        val namesByKey: LinkedHashMap<String, String> = linkedMapOf(),
        var pendingExpectedCount: Int? = null,
        var pendingSince: Long = 0L,
        var lastEvidenceAt: Long = 0L,
        var lastRefreshRequestedAt: Long = 0L,
    )

    private val _events = MutableSharedFlow<ServerManagerEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val runtimes = ConcurrentHashMap<String, RuntimeHandle>()
    private val runtimeStateCache = ConcurrentHashMap<String, ActiveServerRuntimeState>()
    private val portReservations = RuntimePortReservationRegistry()
    private val logListeners = CopyOnWriteArrayList<(String, String, String) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(ServerRuntimeEvent) -> Unit>()
    private val recentLogs = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val playerTrackers = ConcurrentHashMap<String, PlayerTrackerState>()
    private val saveWaiters = ConcurrentHashMap<String, CopyOnWriteArrayList<CompletableDeferred<Boolean>>>()
    private val commandRouter = ServerCommandRouter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val emptyPublicAddress = MutableStateFlow("")
    private var profileRepositoryProvider: (() -> List<ServerProfile>)? = null
    private var selectedProfileProvider: (() -> ServerProfile?)? = null
    var activeTemplate: ServerTemplate = TemplateRegistry.BEDROCK_POWER_NUKKIT_X

    /** Legacy compatibility for the current UI. Returns the selected runtime first. */
    val activeOperationServerId: String?
        get() {
            val selected = selectedProfile()?.id
            if (selected != null && getRuntimeSnapshot(selected).status.isBlocking()) return selected
            return (runtimes.keys + portReservations.startingServerIds())
                .firstOrNull { getRuntimeSnapshot(it).status.isBlocking() }
        }

    val activeSessionId: String?
        get() = activeOperationServerId?.let { runtimes[it]?.sessionId }

    fun setProfileRepositoryProvider(provider: () -> List<ServerProfile>) {
        profileRepositoryProvider = provider
    }

    fun setSelectedProfileProvider(provider: () -> ServerProfile?) {
        selectedProfileProvider = provider
    }

    fun addLogListener(listener: (String, String, String) -> Unit) { logListeners += listener }
    fun removeLogListener(listener: (String, String, String) -> Unit) { logListeners -= listener }
    fun addStatusListener(listener: (ServerRuntimeEvent) -> Unit) { statusListeners += listener }
    fun removeStatusListener(listener: (ServerRuntimeEvent) -> Unit) { statusListeners -= listener }

    private fun emitLog(serverId: String, sessionId: String, line: String) {
        val queue = recentLogs.getOrPut(serverId) { ArrayDeque() }
        synchronized(queue) {
            queue.addLast(line)
            while (queue.size > 1_000) queue.removeFirst()
        }
        trackPlayers(serverId, line)
        if (SAVE_COMPLETE.containsMatchIn(line)) {
            saveWaiters.remove(serverId)?.forEach { it.complete(true) }
        }
        logListeners.forEach { listener -> runCatching { listener(serverId, sessionId, line) } }
    }

    private fun emitStatus(event: ServerRuntimeEvent) {
        val handle = runtimes[event.serverId]
        val processAlive = handle?.engine?.isProcessAlive() == true
        val terminationCause = event.terminationCause.takeUnless { it == TerminationCause.NONE }
            ?: handle?.engine?.getTerminationCause()
            ?: TerminationCause.NONE
        val enriched = event.copy(
            terminationCause = terminationCause,
            processAlive = processAlive,
        )

        val previous = runtimeStateCache[enriched.serverId]
        val networkInfo = handle?.engine?.advertisedNetworkInfo()

        runtimeStateCache[enriched.serverId] = (previous ?: ActiveServerRuntimeState(serverId = enriched.serverId)).copy(
            serverId = enriched.serverId,
            sessionId = if (enriched.status.canStart()) null else enriched.sessionId,
            status = enriched.status,
            terminationCause = enriched.terminationCause,
            processAlive = enriched.processAlive,
            startedAtMillis = handle?.engine?.getStartedAtMillis(),
            port = handle?.port ?: previous?.port,
            advertisedMinecraftVersion = networkInfo?.minecraftVersion,
            advertisedProtocol = networkInfo?.protocol
        )
        if (enriched.status.canStart()) playerTrackers.remove(enriched.serverId)
        statusListeners.forEach { listener -> runCatching { listener(enriched) } }

        _events.tryEmit(
            ServerManagerEvent.StatusChanged(
                serverId = enriched.serverId,
                sessionId = enriched.sessionId,
                status = enriched.status,
                terminationCause = enriched.terminationCause,
                processAlive = enriched.processAlive
            )
        )
    }

    private fun trackPlayers(serverId: String, line: String) {
        val engineId = runtimes[serverId]?.engineId
            ?: profileRepositoryProvider?.invoke()?.firstOrNull { it.id == serverId }?.engineId
            ?: return
        
        val state = playerTrackers.getOrPut(serverId) { PlayerTrackerState() }
        val now = System.currentTimeMillis()
        
        synchronized(state) {
            if (state.pendingExpectedCount != null && now - state.pendingSince > PLAYER_LIST_FOLLOW_UP_TIMEOUT_MS) {
                state.pendingExpectedCount = null
                state.pendingSince = 0L
            }
            
            val parsed = EnginePlayerListParser.parse(engineId, line, state.pendingExpectedCount)
            when {
                parsed.authoritativeNames != null -> {
                    com.example.server.players.PlayerStateProvider.updateOnlinePlayers(serverId, parsed.authoritativeNames)
                    state.pendingExpectedCount = null
                    state.pendingSince = 0L
                    state.lastEvidenceAt = now
                }
                parsed.pendingExpectedCount != null -> {
                    state.pendingExpectedCount = parsed.pendingExpectedCount
                    state.pendingSince = now
                }
                parsed.joinedName != null -> {
                    com.example.server.players.PlayerStateProvider.playerJoined(serverId, parsed.joinedName)
                    state.lastEvidenceAt = now
                }
                parsed.leftName != null -> {
                    com.example.server.players.PlayerStateProvider.playerLeft(serverId, parsed.leftName)
                    state.lastEvidenceAt = now
                }
            }
        }
    }

    /** Requests an authoritative engine player list; parsed responses reconcile join/leave events. */
    fun requestPlayerRefresh(serverId: String) {
        val handle = runtimes[serverId] ?: return
        if (handle.engine.getStatus() == ServerStatus.ONLINE && handle.engine.isProcessAlive()) {
            val state = playerTrackers.getOrPut(serverId) { PlayerTrackerState() }
            synchronized(state) { state.lastRefreshRequestedAt = System.currentTimeMillis() }
            handle.engine.sendCommand("list")
        }
    }

    fun getRecentLogs(serverId: String, limit: Int = 200): List<String> {
        val queue = recentLogs[serverId] ?: return emptyList()
        return synchronized(queue) { queue.toList().takeLast(limit.coerceIn(1, 1_000)) }
    }

    fun getPlayerNames(serverId: String): List<String> {
        val state = com.example.server.players.PlayerStateProvider.getState(serverId).value
        return state.onlineNames.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun isPlayerTrackingAvailable(serverId: String): Boolean {
        val state = com.example.server.players.PlayerStateProvider.getState(serverId).value
        val now = System.currentTimeMillis()
        return state.isTrackingAvailable && (now - state.lastEvidenceAt <= STALE_PLAYER_TIMEOUT_MS)
    }

    fun clearLogs(serverId: String) {
        recentLogs.remove(serverId)?.let { queue -> synchronized(queue) { queue.clear() } }
    }

    suspend fun requestConsistentSave(serverId: String, timeoutMs: Long = 20_000L): Result<Unit> {
        val handle = runtimes[serverId] ?: return Result.success(Unit)
        if (handle.engine.getStatus() != ServerStatus.ONLINE || !handle.engine.isProcessAlive()) {
            return Result.failure(IllegalStateException("The exact server is not online"))
        }
        val waiter = CompletableDeferred<Boolean>()
        val list = saveWaiters.getOrPut(serverId) { CopyOnWriteArrayList() }
        list.add(waiter)
        if (!commandRouter.send(serverId, "save-all")) {
            list.remove(waiter)
            return Result.failure(IllegalStateException("The exact server command writer is unavailable"))
        }
        val saved = withTimeoutOrNull(timeoutMs) { waiter.await() } == true
        list.remove(waiter)
        if (list.isEmpty()) saveWaiters.remove(serverId, list)
        return if (saved) Result.success(Unit)
        else Result.failure(IllegalStateException("The server did not confirm that world saving completed"))
    }

    private fun selectedProfile(): ServerProfile? = selectedProfileProvider?.invoke()

    fun canStartAnotherServer(): Boolean = true

    fun runningServerIds(): Set<String> = (runtimes.keys + portReservations.startingServerIds())
        .filterTo(linkedSetOf()) { getRuntimeSnapshot(it).status.isBlocking() }

    fun runningServerCount(): Int = runningServerIds().size

    fun getPublicAddress() = selectedRuntime()?.tunnelManager?.publicAddress ?: emptyPublicAddress

    fun checkIntegrity() {
        // Runtime/JAR integrity is authoritatively checked by BaseJavaEngine on start.
    }

    fun setTemplate(template: ServerTemplate, engineVersion: EngineVersion) {
        if (engineVersion.engineId != template.id) {
            throw IllegalArgumentException(
                "Mismatch: engineVersion.engineId (${engineVersion.engineId}) != template.id (${template.id})"
            )
        }
        activeTemplate = template
    }

    @Deprecated("Online mode is persisted per server profile")
    fun setOnlineMode(online: Boolean) {
        selectedRuntime()?.engine?.setOnlineMode(online)
    }

    fun switchProfile() {
        // Selection no longer destroys or replaces another profile's runtime.
    }

    suspend fun startServer(serverId: String, memoryMb: Int = 600): StartServerResult {
        val profiles = profileRepositoryProvider?.invoke().orEmpty()
        val profile = profiles.find { it.id == serverId }
            ?: return StartServerResult.ValidationFailed("Server profile not found.")

        val existing = runtimes[serverId]
        if (existing?.engine?.getStatus()?.isBlocking() == true) {
            return StartServerResult.OperationBlocked("This server is already starting or running.")
        }

        val template = TemplateRegistry.getTemplate(profile.engineId)
            ?: return failValidation(serverId, "Server engine ${profile.engineId} is not registered.")
        if (!template.available) {
            return failValidation(serverId, template.unavailableReason ?: "This server engine is temporarily disabled.")
        }

        val version = versionCatalog?.findVersion(profile.engineVersionId)
            ?: return failValidation(serverId, "Engine build ${profile.engineVersionId} was not found in the catalog.")

        if (version.engineId != profile.engineId) {
            return failValidation(
                serverId,
                "Engine mismatch: profile uses ${profile.engineId}, build belongs to ${version.engineId}."
            )
        }

        val compatibility = versionCatalog?.let {
            EngineCompatibilityValidator.validate(profile, it)
        } ?: EngineCompatibilityValidator.Result(false, "Internal error: version catalog unavailable.")
        if (!compatibility.success) {
            return failValidation(serverId, compatibility.message)
        }

        if (version.downloadUrl.isBlank() || version.downloadUrl == "PLACEHOLDER") {
            return failValidation(serverId, "Invalid download URL for the selected engine build.")
        }

        val transport = profile.networkType.portTransport()
        val sessionId = UUID.randomUUID().toString()
        val runtimeBusy = runtimes[serverId]?.engine?.getStatus()?.isBlocking() == true
        val reservationResult = if (runtimeBusy) {
            RuntimePortReservationRegistry.ReserveResult.Rejected("This server is already starting or running", serverId)
        } else {
            portReservations.reserve(serverId, sessionId, profile.port, transport)
        }
        if (reservationResult !is RuntimePortReservationRegistry.ReserveResult.Acquired) {
            val reservedInts = portReservations.reservedPorts().filter { it.transport == transport }.mapTo(hashSetOf()) { it.port }
            val suggestion = PortAllocator.findAvailable(profile.port + 1, reservedInts, transport)
            val reason = (reservationResult as RuntimePortReservationRegistry.ReserveResult.Rejected).reason
            return StartServerResult.OperationBlocked(
                "$reason." + (suggestion?.let { " Suggested available ${transport.name} port: $it." } ?: ""),
            )
        }
        runtimeStateCache[serverId] = com.example.data.ActiveServerRuntimeState(
            serverId = serverId,
            sessionId = sessionId,
            status = ServerStatus.PREPARING,
            processAlive = false,
            port = profile.port,
        )

        val activePorts = runtimes.values
            .filter { it.serverId != serverId && it.transport == transport && it.engine.getStatus().isBlocking() }
            .mapTo(mutableSetOf()) { it.port }
        val portResult = PortAllocator.validatePort(profile.port, transport, activePorts)
        if (!portResult.available) {
            releaseStartReservation(serverId, sessionId, profile.port, transport)
            val suggestion = portResult.suggestedPort?.let { " Suggested available ${transport.name} port: $it." }.orEmpty()
            return failValidation(serverId, (portResult.reason ?: "${transport.name} port unavailable.") + suggestion)
        }

        existing?.engine?.stopServer()
        existing?.tunnelManager?.stopTunnel()

        val logCallback: (String) -> Unit = { line ->
            if (runtimes[serverId]?.sessionId == sessionId) emitLog(serverId, sessionId, line)
        }
        val statusCallback: (ServerStatus) -> Unit = statusCallback@{ status ->
            val handle = runtimes[serverId]
            if (handle?.sessionId != sessionId) return@statusCallback
            val cause = handle.engine.getTerminationCause()
            emitStatus(ServerRuntimeEvent(serverId, sessionId, status, cause))
            if (status.canStart() && runtimes[serverId]?.sessionId == sessionId) {
                saveWaiters.remove(serverId)?.forEach { it.complete(false) }
                commandRouter.detach(serverId, sessionId)
                releaseStartReservation(serverId, sessionId, profile.port, transport)
                mainHandler.post {
                    val removed = runtimes.remove(serverId)
                    if (removed?.sessionId == sessionId) {
                        removed.tunnelManager.close()
                        removed.engine.close()
                    } else if (removed != null) {
                        runtimes[serverId] = removed
                    }
                }
            }
        }

        return try {
            val serverConfig = com.example.server.engine.EngineServerConfig(
                port = profile.port,
                onlineMode = profile.onlineMode,
                levelName = profile.levelName,
                worldSeed = profile.worldSeed,
                worldSeedKnown = profile.worldSeedKnown,
                gameMode = profile.gameMode,
                difficulty = profile.difficulty,
                levelType = profile.levelType,
                maxPlayers = profile.maxPlayers,
                motd = profile.name
            )

            val engine = ServerFactory.createEngine(
                context = context,
                serverDir = File(profile.serverDirectory),
                template = template,
                engineVersion = version,
                bedrockVersion = profile.bedrockVersion,
                port = profile.port,
                profileId = profile.id,
                runtimeSessionId = sessionId,
                serverConfig = serverConfig,
                minecraftEulaAccepted = profile.minecraftEulaAccepted,
                onLog = logCallback,
                onStatusChange = statusCallback
            )
            engine.setOnlineMode(profile.onlineMode)

            val handle = RuntimeHandle(
                serverId = serverId,
                sessionId = sessionId,
                port = profile.port,
                transport = transport,
                engineId = profile.engineId,
                engineVersionId = version.id,
                engine = engine,
                tunnelManager = TunnelManager(context, logCallback)
            )
            runtimes[serverId] = handle
            commandRouter.attach(serverId, sessionId, engine::sendCommand)
            
            // Part 18: Record session in profile
            profileRepositoryProvider?.invoke()?.find { it.id == serverId }?.let { profile ->
                // This is a bit awkward since ServerManager doesn't have direct access to the repository for writing
                // But it can emit an event or we can handle it in MainViewModel.
                // Actually, ServerManager is designed to be independent.
                // I'll emit a specific event for this.
            }
            
            _events.tryEmit(ServerManagerEvent.SessionStarted(serverId, sessionId))
            
            engine.startServer(memoryMb)
            check(portReservations.markStarted(serverId, sessionId)) {
                "Runtime reservation was lost before startup completed"
            }
            StartServerResult.Started
        } catch (error: Throwable) {
            commandRouter.detach(serverId, sessionId)
            runtimes.remove(serverId)?.let { removed ->
                removed.tunnelManager.close()
                removed.engine.close()
            }
            releaseStartReservation(serverId, sessionId, profile.port, transport)
            failValidation(serverId, error.message ?: "Unable to create the server runtime")
        }
    }

    private fun failValidation(serverId: String, reason: String): StartServerResult.ValidationFailed {
        val sessionId = UUID.randomUUID().toString()
        runtimeStateCache[serverId] = ActiveServerRuntimeState(serverId = serverId, status = ServerStatus.FAILED)
        emitLog(serverId, sessionId, "ERROR: $reason")
        emitStatus(ServerRuntimeEvent(serverId, sessionId, ServerStatus.FAILED))
        return StartServerResult.ValidationFailed(reason)
    }

    fun stopServer(serverId: String) {
        runtimes[serverId]?.engine?.stopServer()
    }

    fun stopServer() {
        selectedProfile()?.id?.let(::stopServer)
    }

    fun stopAllServers() {
        runtimes.values.toList().forEach { it.engine.stopServer() }
    }

    fun restartServer(serverId: String) {
        runtimes[serverId]?.engine?.restartServer()
    }

    fun restartServer() {
        selectedProfile()?.id?.let(::restartServer)
    }

    fun sendCommand(serverId: String, command: String) {
        if (!commandRouter.send(serverId, command)) {
            emitLog(serverId, "none", "[Console] Cannot send command: runtime is not active.")
        }
    }

    fun sendCommand(command: String) {
        selectedProfile()?.id?.let { sendCommand(it, command) }
    }

    suspend fun startTunnel(config: FrpTunnelConfig): Result<String> = runCatching {
        val handle = runtimes[config.serverId] ?: error("The exact server must be running before its tunnel can start")
        require(handle.engine.getStatus() == ServerStatus.ONLINE) { "Server ${config.serverId} is not online" }
        require(config.localUdpPort == handle.port) {
            "Tunnel local UDP port ${config.localUdpPort} does not match server port ${handle.port}"
        }
        check(handle.tunnelManager.startTunnel(config)) {
            handle.tunnelManager.health.value.lastError ?: "FRPC did not become ready"
        }
        handle.tunnelManager.publicAddress.value.ifBlank { error("FRPC did not provide a public address") }
    }

    fun stopTunnel(serverId: String) { runtimes[serverId]?.tunnelManager?.stopTunnel() }

    fun getTunnelStatus(serverId: String): TunnelStatus =
        runtimes[serverId]?.tunnelManager?.status?.value ?: TunnelStatus.STOPPED

    fun getTunnelHealth(serverId: String): TunnelHealth =
        runtimes[serverId]?.tunnelManager?.health?.value ?: TunnelHealth()

    fun getTunnelLogs(serverId: String): List<String> =
        runtimes[serverId]?.tunnelManager?.logs?.value.orEmpty()

    fun installTunnelBinary(serverId: String, source: File, expectedSha256: String): Result<Unit> {
        val existing = runtimes[serverId]?.tunnelManager
        if (existing != null) return existing.installVerifiedBinary(source, expectedSha256)
        val installer = TunnelManager(context) { line -> emitLog(serverId, "tunnel-config", line) }
        return try { installer.installVerifiedBinary(source, expectedSha256) } finally { installer.close() }
    }

    fun getServerDir(serverId: String): File {
        val runtimeDir = runtimes[serverId]?.engine?.serverDir
        if (runtimeDir != null) return runtimeDir
        val profile = profileRepositoryProvider?.invoke()?.find { it.id == serverId }
        return profile?.let { File(it.serverDirectory) }
            ?: File(context.filesDir, "servers/$serverId")
    }

    fun getServerDir(): File {
        val selectedId = selectedProfile()?.id
        return if (selectedId != null) getServerDir(selectedId)
        else File(context.filesDir, "servers")
    }


    fun getRuntimeSnapshot(serverId: String): ActiveServerRuntimeState {
        if (portReservations.reservationFor(serverId)?.starting == true) {
            return runtimeStateCache[serverId]
                ?: ActiveServerRuntimeState(serverId = serverId, status = ServerStatus.PREPARING)
        }
        val handle = runtimes[serverId]
            ?: return runtimeStateCache[serverId]
                ?: ActiveServerRuntimeState(serverId = serverId, status = ServerStatus.STOPPED)
        
        val networkInfo = handle.engine.advertisedNetworkInfo()
        val startedAt = handle.engine.getStartedAtMillis()
        val alive = handle.engine.isProcessAlive()
        val reported = handle.engine.getStatus()
        val cause = handle.engine.getTerminationCause()
        val authoritativeStatus = RuntimeStateTruth.reconcile(reported, alive)
        val reachedOnlineAt = handle.engine.getReachedOnlineAtMillis()
        val failureClass = handle.engine.getRuntimeFailureClass()

        val worldComp = when (authoritativeStatus) {
            ServerStatus.WORLD_VERIFIED,
            ServerStatus.ONLINE -> com.example.data.CompatibilityStatus.VERIFIED
            ServerStatus.WORLD_PROVISIONALLY_LOADED -> com.example.data.CompatibilityStatus.WARNING
            ServerStatus.WORLD_LOAD_FAILED -> com.example.data.CompatibilityStatus.FAILED
            else -> com.example.data.CompatibilityStatus.UNKNOWN
        }

        val networkComp = when {
            authoritativeStatus == ServerStatus.PORT_MISMATCH -> com.example.data.CompatibilityStatus.FAILED
            networkInfo.minecraftVersion != null || authoritativeStatus in setOf(
                ServerStatus.NETWORK_READY,
                ServerStatus.ENGINE_READY,
                ServerStatus.WORLD_PROVISIONALLY_LOADED,
                ServerStatus.WORLD_VERIFIED,
                ServerStatus.ONLINE,
            ) ->
                com.example.data.CompatibilityStatus.VERIFIED
            else -> com.example.data.CompatibilityStatus.UNKNOWN
        }

        val snapshot = ActiveServerRuntimeState(
            serverId = serverId,
            sessionId = if (authoritativeStatus.canStart()) null else handle.sessionId,
            status = authoritativeStatus,
            terminationCause = cause,
            processAlive = alive,
            startedAtMillis = startedAt,
            reachedOnlineAtMillis = reachedOnlineAt,
            runtimeFailureClass = failureClass,
            port = handle.port,
            uptimeMillis = startedAt?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) },
            advertisedMinecraftVersion = networkInfo.minecraftVersion,
            advertisedProtocol = networkInfo.protocol,
            worldCompatibility = worldComp,
            networkCompatibility = networkComp
        )
        runtimeStateCache[serverId] = snapshot
        return snapshot
    }

    fun getAllRuntimeSnapshots(serverIds: Collection<String>): Map<String, ActiveServerRuntimeState> =
        serverIds.associateWith(::getRuntimeSnapshot)

    fun getProcessId(serverId: String): Long? = runtimes[serverId]?.engine?.getProcessId()
    fun getProcessId(): Long? = selectedProfile()?.id?.let(::getProcessId)

    fun getStartedAtMillis(serverId: String): Long? = runtimes[serverId]?.engine?.getStartedAtMillis()
    fun getStartedAtMillis(): Long? = selectedProfile()?.id?.let(::getStartedAtMillis)

    fun getStatus(serverId: String): ServerStatus = getRuntimeSnapshot(serverId).status

    fun getCurrentStatus(): ServerStatus = selectedProfile()?.id?.let(::getStatus) ?: ServerStatus.STOPPED

    fun getRuntimeSessionId(serverId: String): String? = runtimes[serverId]?.sessionId

    fun getRuntimePort(serverId: String): Int? = runtimes[serverId]?.port

    private fun selectedRuntime(): RuntimeHandle? = selectedProfile()?.id?.let(runtimes::get)

    private fun releaseStartReservation(serverId: String, sessionId: String, port: Int, transport: PortTransport) {
        portReservations.release(serverId, sessionId, port, transport)
    }

    fun close() {
        saveWaiters.values.flatten().forEach { it.complete(false) }
        saveWaiters.clear()
        commandRouter.clear()
        runtimes.values.toList().forEach { handle ->
            handle.tunnelManager.close()
            handle.engine.close()
            releaseStartReservation(handle.serverId, handle.sessionId, handle.port, handle.transport)
        }
        runtimes.clear()
        playerTrackers.clear()
        portReservations.clear()
    }

    private data class AdvertisedNetworkInfo(
        val minecraftVersion: String?,
        val protocol: Int?,
    )

    private fun ServerEngine.advertisedNetworkInfo(): AdvertisedNetworkInfo {
        return when (val snapshot = getNetworkSnapshot()) {
            is BedrockNetworkSnapshot ->
                AdvertisedNetworkInfo(
                    snapshot.ping.minecraftVersion,
                    snapshot.ping.protocol,
                )

            is JavaNetworkSnapshot ->
                AdvertisedNetworkInfo(
                    snapshot.versionName,
                    snapshot.protocol,
                )

            null -> AdvertisedNetworkInfo(null, null)
        }
    }

    companion object {
        private const val PLAYER_LIST_FOLLOW_UP_TIMEOUT_MS = 5_000L
        private const val STALE_PLAYER_TIMEOUT_MS = 120_000L
        private val SAVE_COMPLETE = Regex(
            "(?:save(?:d| complete)|worlds? saved|saving.*done|all worlds.*saved)",
            RegexOption.IGNORE_CASE,
        )
    }

}
