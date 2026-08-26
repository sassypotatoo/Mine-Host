package com.example.server.engine

import android.content.Context
import com.example.server.ServerStatus
import com.example.server.isBlocking
import com.example.server.engine.ConfigAdapterFactory
import com.example.server.health.HealthEvent
import com.example.server.health.ServerHealthMonitor
import com.example.server.version.EngineProtocolCompatibility
import com.example.server.version.EngineProtocolCompatibilityState
import com.example.server.version.EngineVersion
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.RuntimeProtocolExpectation
import com.example.world.WorldImportJournalManager
import com.example.world.ImportedWorldVerificationStore
import com.example.world.WorldLaunchOwnershipPolicy
import com.example.world.WorldLaunchOwnership
import com.example.world.EngineGeneratedWorldStore
import com.example.world.WorldCompatibilityCore
import com.example.world.WorldFileIntegrity
import com.example.world.WorldWorkingCopyManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.*

/**
 * Base class for Bedrock JVM server engines (PowerNukkit, PowerNukkitX, Nukkit, Cloudburst, NukkitMOT).
 * Extends [JvmServerEngineBase] with Bedrock-specific RakNet probes, world protection, and configuration.
 */
abstract class BedrockJavaEngineBase(
    context: Context,
    serverDir: File,
    engineVersion: EngineVersion,
    protected val bedrockVersion: String,
    port: Int,
    profileId: String,
    runtimeSessionId: String,
    val serverConfig: EngineServerConfig,
    onLog: (String) -> Unit,
    onStatusChange: (ServerStatus) -> Unit
) : JvmServerEngineBase(
    context = context,
    serverDir = serverDir,
    engineVersion = engineVersion,
    port = port,
    profileId = profileId,
    runtimeSessionId = runtimeSessionId,
    onLog = onLog,
    onStatusChange = onStatusChange
) {

    private data class PreparedBedrockWorld(
        val worldName: String,
        val ownership: WorldLaunchOwnership,
    )

    @Volatile
    private var preparedWorld: PreparedBedrockWorld? = null

    @Volatile
    private var activeProtectedLaunch: WorldCompatibilityCore.PreparedLaunch? = null

    protected open fun runtimeProtocolExpectation(): RuntimeProtocolExpectation =
        RuntimeProtocolExpectation(
            expectedProtocols = engineVersion.protocolVersions,
            selectedBedrockVersion = bedrockVersion,
            source = "catalog:${engineVersion.id}",
        )

    override fun getLastPingResult(): BedrockServerPingResult? = currentSession?.pingResult

    override fun getNetworkSnapshot(): ServerNetworkSnapshot? {
        val ping = currentSession?.pingResult ?: return null
        return BedrockNetworkSnapshot(
            port = port,
            ping = ping
        )
    }

    override fun onGetSelectedVersion(): String = bedrockVersion

    override suspend fun onPreflightCheck() {
        val recoveryResult = WorldImportJournalManager.recoverIncompleteTransactions(serverDir)
        check(recoveryResult.success) {
            recoveryResult.messages.joinToString("; ").ifBlank { "Failed to recover incomplete world transactions" }
        }

        val installedResolvedIdentity =
            InstalledEngineVersionRepository.verifiedResolvedIdentity(
                serverDir = serverDir,
                selectedVersion = engineVersion,
                selectedBedrockVersion = bedrockVersion,
            )

        val identityForPreflight =
            resolvedIdentity ?: installedResolvedIdentity

        // Check Part 5 World Generation Marker Verification:
        val genIdentity =
            com.example.world.EngineGeneratorIdentityResolver.resolve(
                getEngineId(),
                engineVersion,
                serverConfig.levelType,
                identityForPreflight,
            )
        val genInput = com.example.world.GenerationIdentityInput(
            levelName = serverConfig.levelName,
            worldSeed = serverConfig.worldSeed,
            worldSeedKnown = serverConfig.worldSeedKnown,
            engineId = getEngineId(),
            engineVersionId = engineVersion.id,
            generatorId = genIdentity.id,
            generatorRevision = genIdentity.revision,
        )

        val metadata = InstalledEngineVersionRepository.read(serverDir)
        if (metadata != null) {
            val installedResolved = metadata.resolvedIdentity
            if (identityForPreflight != null && installedResolved != null) {
                if (identityForPreflight.resolvedBuildNumber !=
                    installedResolved.resolvedBuildNumber
                ) {
                    throw IllegalStateException(
                        "Absolute engine version mismatch for '${spec.displayName}'. " +
                            "The world is locked to build #${installedResolved.resolvedBuildNumber}, " +
                            "but build #${identityForPreflight.resolvedBuildNumber} was resolved for launch. " +
                            "This build cannot safely start this world."
                    )
                }
            }
        }

        val verifyResult = com.example.world.WorldGenerationMarkerManager.verifyOrBlock(
            serverDir = serverDir,
            input = genInput,
            engineVersion = engineVersion
        )
        if (verifyResult.isFailure) {
            throw verifyResult.exceptionOrNull() ?: IOException("World generation identity verification failed")
        }
    }

    override suspend fun onApplyEngineConfig() {
        val adapter = ConfigAdapterFactory.getAdapter(spec.family)
        adapter.applyConfig(
            serverDir = serverDir,
            config = serverConfig.copy(port = port, onlineMode = onlineModeEnabled)
        )

        val propertiesFile = File(serverDir, "server.properties")
        require(propertiesFile.isFile && propertiesFile.length() > 0L) {
            "Engine configuration failed: server.properties was not generated"
        }

        // Loop 5: Verify the committed configuration
        val properties = Properties().apply {
            propertiesFile.inputStream().use(::load)
        }

        val storedPort = properties
            .getProperty("server-port")
            ?.trim()
            ?.toIntOrNull()
            ?: throw IOException("server.properties has no valid server-port")

        require(storedPort == port) {
            "Configuration mismatch: expected UDP $port, stored UDP $storedPort"
        }

        val v6Prop = properties.getProperty("server-portv6")
        if (!v6Prop.isNullOrBlank()) {
            val storedV6 = v6Prop.trim().toIntOrNull()
                ?: throw IOException("server.properties has an invalid server-portv6")
            require(storedV6 == port) {
                "IPv6 configuration mismatch: expected UDP $port, stored UDP $storedV6"
            }
        }

        readActiveWorldNameStrict()
    }

    private fun readActiveWorldNameStrict(): String {
        val propertiesFile = File(serverDir, "server.properties")

        require(propertiesFile.isFile) {
            "server.properties is missing"
        }

        val properties = Properties().apply {
            propertiesFile.inputStream().use(::load)
        }

        val worldName = properties
            .getProperty("level-name", "world")
            .trim()
            .ifBlank { "world" }

        require(
            '/' !in worldName &&
                '\\' !in worldName &&
                worldName != "." &&
                worldName != ".." &&
                worldName.length <= 128
        ) {
            "Unsafe level-name in server.properties: $worldName"
        }

        val worldsRoot = File(serverDir, "worlds").canonicalFile
        val resolved = File(worldsRoot, worldName).canonicalFile

        require(resolved.parentFile == worldsRoot) {
            "level-name escaped the server worlds directory"
        }

        return worldName
    }

    protected open fun onValidateBedrockWorld(
        worldName: String,
        ownership: WorldLaunchOwnership
    ): String? = null

    override suspend fun onPrepareWorldAndLaunchJar(serverJar: File): File {
        val activeWorldName = readActiveWorldNameStrict()
        val decision = WorldLaunchOwnershipPolicy.classify(serverDir, activeWorldName)

        onLog("[Ownership] World '$activeWorldName' classification: ${decision.ownership}")

        val validationError = onValidateBedrockWorld(activeWorldName, decision.ownership)
        if (validationError != null) {
            throw IllegalStateException(validationError)
        }

        when (decision.ownership) {
            WorldLaunchOwnership.IMPORTED_PROTECTED_VALID -> {
                val launch = prepareImportedProtectedWorld(activeWorldName, serverJar)
                preparedWorld = PreparedBedrockWorld(
                    worldName = activeWorldName,
                    ownership = decision.ownership,
                )
                return launch.launchArtifact
            }
            WorldLaunchOwnership.UNTRACKED_REQUIRES_ADOPTION,
            WorldLaunchOwnership.EXTERNAL_ADOPTION_REQUIRED -> {
                throw IllegalStateException(
                    "World '$activeWorldName' (${decision.ownership}) must be adopted through MineHost's protected import flow before it can be launched."
                )
            }
            WorldLaunchOwnership.INCOMPLETE_IMPORT_TRANSACTION,
            WorldLaunchOwnership.CORRUPT_PROTECTED_ORIGINAL,
            WorldLaunchOwnership.BROKEN_ONE_SIDED_REGISTRATION -> {
                throw IllegalStateException(
                    "World '$activeWorldName' is in a broken state: ${decision.reasons.joinToString("; ")}"
                )
            }
            WorldLaunchOwnership.ENGINE_WILL_GENERATE -> {
                if (!serverConfig.worldSeedKnown) {
                    throw IllegalStateException("A missing world with an unknown seed cannot be safely generated. Please use Regenerate World to assign a known seed.")
                }
                preparedWorld = PreparedBedrockWorld(
                    worldName = activeWorldName,
                    ownership = decision.ownership,
                )
                return serverJar
            }
            WorldLaunchOwnership.ENGINE_GENERATED_VALID -> {
                preparedWorld = PreparedBedrockWorld(
                    worldName = activeWorldName,
                    ownership = decision.ownership,
                )
                return serverJar
            }
            WorldLaunchOwnership.UNKNOWN -> {
                throw IllegalStateException("Cannot prove ownership of world '$activeWorldName'. Launch blocked to prevent data loss.")
            }
        }
    }

    override fun onProcessStartedHook(session: ServerProcessSession) {
        startRakNetProbeLoop(session)
    }

    private fun prepareImportedProtectedWorld(
        worldName: String,
        serverJar: File,
    ): WorldCompatibilityCore.PreparedLaunch {
        onLog("[WorldCompat] Preparing protected imported world '$worldName' against ${engineVersion.id}")
        val core = WorldCompatibilityCore(serverDir)
        val prepared = core.prepareProtectedLaunch(
            worldName = worldName,
            engineId = getEngineId(),
            engineVersionId = engineVersion.id,
            engineArtifact = serverJar,
            expectedArtifactSha256 = engineVersion.sha256,
            protocolVersions = runtimeProtocolExpectation().expectedProtocols,
        ).getOrElse { error ->
            throw IllegalStateException(
                "Imported world '$worldName' cannot be safely launched: ${error.message}",
                error,
            )
        }
        prepared.compatibility.reasons.take(6).forEach { onLog("[WorldCompat] $it") }
        if (prepared.compatibility.requiresProtectedLaunch) {
            onLog("[WorldCompat] Protected mode stays active; a runtime failure resets this world from its immutable original.")
        }
        activeProtectedLaunch = prepared
        return prepared
    }

    private fun restoreProtectedWorldAfterRuntimeFailure(session: ServerProcessSession) {
        val launch = activeProtectedLaunch ?: return
        activeProtectedLaunch = null
        if (!isCurrentProcessSession(session)) return

        val errors = buildList {
            session.fatalErrorMessage?.let(::add)
            synchronized(session.compatibilityErrors) { addAll(session.compatibilityErrors) }
        }
        WorldCompatibilityCore(serverDir)
            .restoreAfterRuntimeFailure(launch.protection.worldName, getEngineId(), errors)
            .fold(
                onSuccess = {
                    onLog(
                        "[WorldCompat] Working copy of '${launch.protection.worldName}' was reset " +
                            "from its immutable original after the runtime failure."
                    )
                },
                onFailure = {
                    onLog(
                        "[WorldCompat] Could not reset '${launch.protection.worldName}' after the " +
                            "runtime failure: ${it.message}"
                    )
                },
            )
    }

    override fun onHealthEvent(event: HealthEvent, line: String, session: ServerProcessSession) {
        if (currentSession?.sessionId != session.sessionId) {
            onLog(
                "[Runtime] Ignoring stale health event for session " +
                    session.sessionId
            )
            return
        }
        when (event) {
            HealthEvent.ENGINE_READY -> {
                onLog("[Readiness] Engine-ready evidence confirmed.")
                maybeMarkBedrockOnline(session)
            }
            HealthEvent.READINESS_CANDIDATE -> {
                onLog("[Readiness] Network/query readiness candidate observed; waiting for the real engine-ready line.")
            }
            HealthEvent.BOUND_PORT_DETECTED -> {
                val detected =
                    BedrockBoundPortParser.parse(line) ?: return

                session.boundPort = detected

                if (detected != port) {
                    failPortMismatch(
                        session,
                        "Engine bound UDP $detected, " +
                            "expected UDP $port",
                    )
                    return
                }

                maybeMarkBedrockOnline(session)
            }
            HealthEvent.WORLD_INCOMPATIBLE -> {
                session.incompatibleWorldDetected = true
                session.fatalErrorMessage = line
                session.runtimeFailureClass = "WORLD_INCOMPATIBLE"

                synchronized(session.compatibilityErrors) {
                    val used = session.compatibilityErrors.sumOf(String::length)
                    if (used < 16000) { // Using constant from JvmServerEngineBase companion
                        session.compatibilityErrors += line.take(16000 - used)
                    }
                }

                healthMonitor.setStatus(ServerStatus.WORLD_LOAD_FAILED)

                scope.launch {
                    stopServerInternal(session, TerminationCause.WORLD_INCOMPATIBLE_STOP)
                    restoreProtectedWorldAfterRuntimeFailure(session)
                }
            }
            HealthEvent.SERVER_STOPPING -> {
                session.onlineModeConfirmed = false

                val current = getStatus()
                val preserveSpecificFailure =
                    current == ServerStatus.FAILED ||
                            current == ServerStatus.CRASHED ||
                            current == ServerStatus.WORLD_LOAD_FAILED ||
                            current == ServerStatus.PORT_MISMATCH ||
                            current == ServerStatus.PROTOCOL_MISMATCH

                if (!preserveSpecificFailure) {
                    healthMonitor.setStatus(ServerStatus.STOPPING)
                }

                onLog("[Runtime] Server is stopping")
            }
            else -> super.onHealthEvent(event, line, session)
        }
    }

    private fun failPortMismatch(
        session: ServerProcessSession,
        message: String,
    ) {
        if (currentSession?.sessionId != session.sessionId) return
        session.fatalErrorMessage = message
        session.runtimeFailureClass = "PORT_MISMATCH"
        onLog("[Network] FATAL: $message")
        healthMonitor.setStatus(ServerStatus.PORT_MISMATCH)

        scope.launch {
            stopServerInternal(
                session,
                TerminationCause.PORT_MISMATCH_STOP,
            )
        }
    }

    protected open suspend fun ensurePreparedWorldIdentity(): Boolean {
        val prepared = preparedWorld ?: return false

        val worldsRoot = File(serverDir, "worlds").canonicalFile
        val worldDirectory = File(worldsRoot, prepared.worldName).canonicalFile

        if (worldDirectory.parentFile != worldsRoot) return false

        return when (prepared.ownership) {
            WorldLaunchOwnership.ENGINE_GENERATED_VALID -> {
                val genIdentity = com.example.world.EngineGeneratorIdentityResolver.resolve(
                    getEngineId(), engineVersion, serverConfig.levelType, resolvedIdentity
                )
                val verifiedGen = com.example.world.WorldGenerationMarkerManager.verifyExact(
                    serverDir = serverDir,
                    worldName = prepared.worldName,
                    seed = serverConfig.worldSeed,
                    seedKnown = serverConfig.worldSeedKnown,
                    engineId = getEngineId(),
                    engineVersionId = engineVersion.id,
                    generatorId = genIdentity.id,
                    generatorRevision = genIdentity.revision
                )
                val verifiedOwner = EngineGeneratedWorldStore.isRecognized(
                    serverDir,
                    prepared.worldName,
                    worldDirectory,
                )
                verifiedGen && verifiedOwner
            }

            WorldLaunchOwnership.ENGINE_WILL_GENERATE -> {
                if (!File(worldDirectory, "level.dat").isFile || !File(worldDirectory, "db").isDirectory) {
                    onLog("[World] Engine reported readiness before the generated world became verifiable.")
                    false
                } else {
                    val genIdentity = com.example.world.EngineGeneratorIdentityResolver.resolve(
                        getEngineId(), engineVersion, serverConfig.levelType, resolvedIdentity
                    )
                    val markedOwner = EngineGeneratedWorldStore.mark(
                        serverRoot = serverDir,
                        worldName = prepared.worldName,
                        worldDirectory = worldDirectory,
                        engineId = getEngineId(),
                        engineVersionId = engineVersion.id,
                    )
                    if (!markedOwner) return false

                    val markedGen = com.example.world.WorldGenerationMarkerManager.writeMarker(
                        serverDir = serverDir,
                        worldName = prepared.worldName,
                        seed = serverConfig.worldSeed,
                        seedKnown = serverConfig.worldSeedKnown,
                        engineId = getEngineId(),
                        engineVersionId = engineVersion.id,
                        generatorId = genIdentity.id,
                        generatorRevision = genIdentity.revision
                    )
                    if (!markedGen) {
                        EngineGeneratedWorldStore.clear(serverDir, prepared.worldName, worldDirectory)
                        return false
                    }

                    // World generation is definitively complete and owned. Commit any pending regeneration transactions for this world.
                    com.example.world.WorldRegenerationManager.commitRegenerationForWorld(
                        serverDir = serverDir,
                        worldName = prepared.worldName,
                        seed = serverConfig.worldSeed,
                        seedKnown = serverConfig.worldSeedKnown,
                        generatorId = genIdentity.id,
                        generatorRevision = genIdentity.revision
                    )

                    val verifiedGen = com.example.world.WorldGenerationMarkerManager.verifyExact(
                        serverDir = serverDir,
                        worldName = prepared.worldName,
                        seed = serverConfig.worldSeed,
                        seedKnown = serverConfig.worldSeedKnown,
                        engineId = getEngineId(),
                        engineVersionId = engineVersion.id,
                        generatorId = genIdentity.id,
                        generatorRevision = genIdentity.revision
                    )
                    val verifiedOwner = EngineGeneratedWorldStore.isRecognized(serverDir, prepared.worldName, worldDirectory)

                    if (verifiedGen && verifiedOwner) {
                        onLog("[World] Recorded engine-generated ownership and generation identity for '${prepared.worldName}'.")
                        true
                    } else {
                        onLog("[World] Identity verification failed for newly generated world '${prepared.worldName}'.")
                        false
                    }
                }
            }

            WorldLaunchOwnership.IMPORTED_PROTECTED_VALID -> {
                val metadata = com.example.world.WorldWorkingCopyManager(serverDir).metadata(prepared.worldName)
                val expectedHash = metadata?.preparedWorldHash
                if (metadata == null || expectedHash == null) {
                    onLog("[World] Protected-world metadata is missing for '${prepared.worldName}'.")
                    false
                } else {
                    val actualHash = runCatching {
                        com.example.world.WorldFileIntegrity.fingerprint(worldDirectory).rootHash
                    }.getOrNull()
                    if (actualHash == expectedHash) {
                        true
                    } else {
                        onLog("[World] Working copy of '${prepared.worldName}' no longer matches its prepared fingerprint.")
                        false
                    }
                }
            }

            else -> false
        }
    }

    protected open fun commitOnlineMetadata(
        readyAtMillis: Long,
    ): Boolean {
        val metadata =
            InstalledEngineVersionRepository.read(
                serverDir
            ) ?: return false

        val updated = metadata.copy(
            firstReadyAt =
                if (metadata.firstReadyAt == 0L) {
                    readyAtMillis
                } else {
                    metadata.firstReadyAt
                },
            lastReadyAt = readyAtMillis,
        )

        return InstalledEngineVersionRepository.write(
            serverDir,
            updated,
        )
    }

    protected open fun maybeMarkBedrockOnline(session: ServerProcessSession) {
        if (currentSession?.sessionId != session.sessionId) return
        if (session.process?.isAlive != true) return
        if (session.terminationCause != TerminationCause.NONE) return
        if (!session.engineReady || !session.networkReady) return
        if (session.onlineModeConfirmed) return

        val boundPort = session.boundPort ?: run {
            onLog("[Readiness] Engine-ready and RakNet-ready; waiting for bound-port evidence.")
            return
        }

        if (boundPort != port) {
            failPortMismatch(session, "Engine bound UDP $boundPort, expected UDP $port")
            return
        }

        val ping = session.pingResult ?: return
        if (!ping.responded) return

        val advertisedPort = ping.portV4
        if (advertisedPort != null && advertisedPort != port) {
            failPortMismatch(session, "RakNet advertised UDP $advertisedPort, expected UDP $port")
            return
        }

        if (session.worldIdentityVerified) {
            onLog("[Readiness] All gates satisfied (engine, network, world). Marking ONLINE.")
            markOnline(session)
            return
        }

        onLog("[Readiness] Waiting for world identity verification...")
        startWorldIdentityVerification(session)
    }

    protected fun tryBeginWorldIdentityVerification(
        session: ServerProcessSession,
    ): Boolean {
        if (!isCurrentProcessSession(session)) {
            return false
        }

        return session.worldIdentityCommitStarted
            .compareAndSet(false, true)
    }

    private fun startWorldIdentityVerification(
        session: ServerProcessSession,
    ) {
        if (!tryBeginWorldIdentityVerification(session)) {
            return
        }

        session.worldIdentityJob = scope.launch(Dispatchers.IO) {
            try {
                val baseGrace =
                    if (getEngineId() == "nukkit-mot") {
                        60_000L
                    } else {
                        WORLD_IDENTITY_GRACE_MS
                    }
                val deadline = System.currentTimeMillis() + baseGrace

                while (
                    isActive &&
                    isCurrentProcessSession(session) &&
                    session.process?.isAlive == true &&
                    session.terminationCause == TerminationCause.NONE &&
                    System.currentTimeMillis() < deadline
                ) {
                    if (ensurePreparedWorldIdentity()) {
                        if (!isCurrentProcessSession(session)) {
                            return@launch
                        }

                        session.worldIdentityVerified = true

                        withContext(Dispatchers.Main) {
                            if (isCurrentProcessSession(session)) {
                                maybeMarkBedrockOnline(session)
                            }
                        }
                        return@launch
                    }

                    delay(WORLD_IDENTITY_RETRY_MS)
                }

                if (
                    isCurrentProcessSession(session) &&
                    session.terminationCause == TerminationCause.NONE &&
                    !session.worldIdentityVerified
                ) {
                    failWorldIdentityVerification(
                        session,
                        "Engine-generated world ownership could not be " +
                            "verified within the readiness grace period",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (
                    isCurrentProcessSession(session) &&
                    session.terminationCause == TerminationCause.NONE
                ) {
                    failWorldIdentityVerification(
                        session,
                        "Engine-generated world identity verification " +
                            "failed: " +
                            (error.message ?: error::class.java.simpleName),
                    )
                }
            }
        }
    }

    private suspend fun failWorldIdentityVerification(
        session: ServerProcessSession,
        message: String,
    ) {
        if (!isCurrentProcessSession(session)) return
        if (session.terminationCause != TerminationCause.NONE) return

        session.fatalErrorMessage = message
        session.runtimeFailureClass = "WORLD_IDENTITY_VERIFICATION_FAILED"

        withContext(Dispatchers.Main) {
            if (isCurrentProcessSession(session)) {
                onLog("[World] FATAL: $message")
                healthMonitor.setStatus(ServerStatus.FAILED)
            }
        }

        if (
            isCurrentProcessSession(session) &&
            session.process?.isAlive == true &&
            session.terminationCause == TerminationCause.NONE
        ) {
            stopServerInternal(
                session,
                TerminationCause.STARTUP_FAILURE_STOP,
            )
        }
    }

    private fun markOnline(
        session: ServerProcessSession,
    ) {
        if (!isCurrentProcessSession(session)) return
        if (session.process?.isAlive != true) return
        if (
            session.terminationCause !=
            TerminationCause.NONE
        ) {
            return
        }
        if (!session.engineReady) return
        if (!session.networkReady) return
        if (!session.worldIdentityVerified) return
        if (session.onlineModeConfirmed) return

        if (
            !session.onlineCommitStarted.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        if (
            !isCurrentProcessSession(session) ||
            session.process?.isAlive != true ||
            session.terminationCause !=
                TerminationCause.NONE ||
            !session.engineReady ||
            !session.networkReady ||
            !session.worldIdentityVerified ||
            session.onlineModeConfirmed
        ) {
            session.onlineCommitStarted.set(false)
            return
        }

        val readyAt = System.currentTimeMillis()

        if (!commitOnlineMetadata(readyAt)) {
            session.fatalErrorMessage =
                "ONLINE readiness metadata could not be " +
                    "durably committed"
            session.runtimeFailureClass =
                "ONLINE_METADATA_COMMIT_FAILED"

            onLog(
                "[Readiness] FATAL: " +
                    session.fatalErrorMessage
            )

            healthMonitor.setStatus(ServerStatus.FAILED)

            scope.launch {
                if (
                    isCurrentProcessSession(session) &&
                    session.process?.isAlive == true &&
                    session.terminationCause ==
                        TerminationCause.NONE
                ) {
                    stopServerInternal(
                        session,
                        TerminationCause
                            .STARTUP_FAILURE_STOP,
                    )
                }
            }

            return
        }

        if (
            !isCurrentProcessSession(session) ||
            session.process?.isAlive != true ||
            session.terminationCause !=
                TerminationCause.NONE
        ) {
            session.onlineCommitStarted.set(false)
            return
        }

        session.onlineModeConfirmed = true
        session.reachedOnlineAtMillis = readyAt
        session.runtimeFailureClass = "NORMAL_RUN"

        session.startupTimeoutJob?.cancel()
        session.startupTimeoutJob = null

        healthMonitor.setStatus(ServerStatus.ONLINE)

        onLog(
            "[Server] ONLINE: engine-ready, " +
                "bound-port-ready, verified RakNet-ready " +
                "and world-identity-ready on UDP $port " +
                "(session ${session.sessionId})"
        )
    }

    private fun startRakNetProbeLoop(session: ServerProcessSession) {
        session.probeJob = scope.launch(Dispatchers.IO) {
            delay(1000)
            while (
                isActive &&
                currentSession?.sessionId == session.sessionId &&
                session.process?.isAlive == true &&
                session.terminationCause == TerminationCause.NONE
            ) {
                try {
                    val ping = BedrockRakNetProbe.probe(port)
                    if (currentSession?.sessionId != session.sessionId) {
                        return@launch
                    }
                    if (ping.responded) {
                        if (!ping.edition.equals("MCPE", ignoreCase = true)) {
                            session.fatalErrorMessage =
                                "RakNet response did not advertise the MCPE Bedrock edition"
                            onLog(
                                "[Protocol] FATAL: " +
                                    session.fatalErrorMessage
                            )
                            healthMonitor.setStatus(
                                ServerStatus.PROTOCOL_MISMATCH
                            )
                            stopServerInternal(
                                session,
                                TerminationCause.PROTOCOL_MISMATCH_STOP,
                            )
                            return@launch
                        }

                        if (ping.portV4 != null && ping.portV4 != port) {
                            failPortMismatch(session, "RakNet advertised UDP ${ping.portV4}, expected UDP $port")
                            return@launch
                        }

                        session.pingResult = ping
                        if (!session.networkReady) {
                            val expectation = runtimeProtocolExpectation()
                            var expectedProtocols = expectation.expectedProtocols
                            if (
                                expectation.supportsAdvertisedCurrentProtocol &&
                                ping.protocol != null &&
                                ping.protocol !in expectedProtocols
                            ) {
                                // The probed process IS this artifact; its unnumbered
                                // runtime_block_states.dat palette is the current
                                // protocol's own metadata, so the advertisement is covered.
                                expectedProtocols = expectedProtocols + ping.protocol
                                onLog(
                                    "[Protocol] Unnumbered runtime_block_states.dat covers this " +
                                        "build's own advertisement (protocol ${ping.protocol}); " +
                                        "included in verification."
                                )
                            }
                            if (!session.protocolExpectationLogged) {
                                session.protocolExpectationLogged = true
                                onLog("[Protocol] Verification source: ${expectation.source}, protocols: ${expectedProtocols.joinToString()}")
                            }
                            val verification = EngineProtocolCompatibility.evaluate(
                                selectedBedrockVersion = expectation.selectedBedrockVersion,
                                advertisedBedrockVersion = ping.minecraftVersion,
                                advertisedProtocol = ping.protocol,
                                expectedProtocols = expectedProtocols,
                                advertisedEdition = ping.edition,
                            )

                            when (verification.state) {
                                EngineProtocolCompatibilityState.VERIFIED -> {
                                    onLog("[Protocol] ${verification.message}")
                                }
                                EngineProtocolCompatibilityState.PROTOCOL_MISMATCH,
                                EngineProtocolCompatibilityState.VERSION_MISMATCH -> {
                                    session.fatalErrorMessage = verification.message
                                    onLog("[Protocol] FATAL: ${verification.message}")
                                    healthMonitor.setStatus(ServerStatus.PROTOCOL_MISMATCH)
                                    stopServerInternal(session, TerminationCause.PROTOCOL_MISMATCH_STOP)
                                    return@launch
                                }
                                EngineProtocolCompatibilityState.UNKNOWN -> {
                                    val expectedEvidence = expectation.expectedProtocols.isNotEmpty() ||
                                            !expectation.selectedBedrockVersion.equals("AUTO", ignoreCase = true)
                                    if (expectedEvidence) {
                                        session.fatalErrorMessage = verification.message
                                        onLog("[Protocol] FATAL: Expected metadata could not be verified: ${verification.message}")
                                        healthMonitor.setStatus(ServerStatus.PROTOCOL_MISMATCH)
                                        stopServerInternal(session, TerminationCause.PROTOCOL_MISMATCH_STOP)
                                        return@launch
                                    }
                                    onLog("[Protocol] Metadata remains unverified: ${verification.message}")
                                }
                            }

                            session.networkReady = true
                            if (!session.engineReady) {
                                healthMonitor.setStatus(ServerStatus.NETWORK_READY)
                            }
                            onLog("[RakNet] Verified response on UDP $port: ${ping.minecraftVersion.orEmpty()}, protocol ${ping.protocol ?: "unknown"}")
                            maybeMarkBedrockOnline(session)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    // Probe ignored if unreachable
                }
                delay(3000)
            }
        }
    }

    override suspend fun onCleanupProcessHook(
        session: ServerProcessSession,
        exitCode: Int,
    ) {
        val ownsCurrentEngineState =
            isCurrentProcessSession(session)

        session.pingResult = null
        session.boundPort = null
        session.engineReady = false
        session.networkReady = false
        session.onlineModeConfirmed = false

        session.worldIdentityJob?.cancel()
        session.worldIdentityJob = null
        session.worldIdentityVerified = false
        session.worldIdentityCommitStarted.set(false)
        session.onlineCommitStarted.set(false)

        if (ownsCurrentEngineState) {
            preparedWorld = null
        }

        onLog(
            "[Runtime] Session-owned Bedrock data cleared " +
                "after process exit for ${session.sessionId}"
        )
    }

    companion object {
        private const val WORLD_IDENTITY_GRACE_MS = 15_000L
        private const val WORLD_IDENTITY_RETRY_MS = 500L
    }
}
