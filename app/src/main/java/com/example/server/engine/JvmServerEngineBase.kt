package com.example.server.engine

import android.content.Context
import android.util.Log
import com.example.server.DownloadServiceLeaseController
import com.example.server.Downloader
import com.example.server.JavaRuntimeManager
import com.example.server.RuntimePreparationResult
import com.example.server.ServerJarDownloadResult
import com.example.server.ServerServiceLeaseController
import com.example.server.ServerStatus
import com.example.server.canStart
import com.example.server.destroyForciblyCompat
import com.example.server.health.HealthEvent
import com.example.server.health.ServerHealthMonitor
import com.example.server.isBlocking
import com.example.server.version.EngineVersion
import com.example.server.version.EngineInstallationMetadataFactory
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.LaunchMode
import com.example.server.version.ResolvedEngineVersion
import com.example.server.waitForCompat
import com.example.world.ServerRunStateStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Generic JVM Server Engine Base class.
 * Owns generic process management, Java runtime selection/installation,
 * process execution via native launcher, stdout/stderr reading, stdin writing,
 * PID tracking, stop command, and termination handling.
 */
abstract class JvmServerEngineBase(
    protected val context: Context,
    override val serverDir: File,
    public val engineVersion: EngineVersion,
    protected val port: Int,
    protected val profileId: String,
    protected val runtimeSessionId: String,
    protected val onLog: (String) -> Unit,
    protected val onStatusChange: (ServerStatus) -> Unit
) : ServerEngine {

    protected var resolvedIdentity: ResolvedEngineVersion? = null

    protected val engineJob = SupervisorJob()
    protected val scope = CoroutineScope(Dispatchers.Main + engineJob)
    @Volatile protected var currentSession: ServerProcessSession? = null
    protected var logJob: Job? = null
    protected var exitWatcherJob: Job? = null
    protected var startupJob: Job? = null
    protected var lastMemoryMb: Int = 1024

    protected val healthMonitor = ServerHealthMonitor(onLog) { status ->
        onStatusChange(status)
    }

    protected val spec: EngineLaunchSpec by lazy {
        EngineCatalog.getSpec(getEngineId())
            ?: throw IllegalStateException("Unknown engine ID: ${getEngineId()}")
    }

    protected val startupMutex = Mutex()
    protected val sessionMutex = Mutex()
    @Volatile protected var onlineModeEnabled: Boolean = true

    abstract fun getEngineId(): String

    open fun getRequiredJavaMajor(): Int = engineVersion.runtimeJavaVersion

    protected fun isCurrentProcessSession(
        session: ServerProcessSession,
    ): Boolean {
        return currentSession?.sessionId == session.sessionId
    }

    protected open fun createProcessSessionId(): String {
        return "$runtimeSessionId:${UUID.randomUUID()}"
    }

    override fun getProcessId(): Long? {
        val current = currentSession?.process ?: return null
        val methodPid = runCatching {
            current.javaClass.methods
                .firstOrNull { it.name == "pid" && it.parameterCount == 0 }
                ?.invoke(current)
                ?.let { it as? Number }
                ?.toLong()
        }.getOrNull()
        if (methodPid != null) return methodPid

        return runCatching {
            val field = current.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            (field.get(current) as? Number)?.toLong()
        }.getOrNull()
    }

    override fun getStartedAtMillis(): Long? = currentSession?.startedAt

    override fun isProcessAlive(): Boolean = currentSession?.process?.isAlive == true

    override fun getStatus(): ServerStatus = healthMonitor.getStatus()
    override fun getTerminationCause(): TerminationCause = currentSession?.terminationCause ?: TerminationCause.NONE
    override fun getReachedOnlineAtMillis(): Long? = currentSession?.reachedOnlineAtMillis
    override fun getRuntimeFailureClass(): String? = currentSession?.runtimeFailureClass

    override fun isStableSession(): Boolean {
        val session = currentSession ?: return false
        if (session.process?.isAlive != true) return false
        if (session.terminationCause != TerminationCause.NONE) return false
        return true
    }

    override fun setOnlineMode(online: Boolean) {
        this.onlineModeEnabled = online
    }

    override fun clearLogs() {}

    override fun installPlugin(url: String, fileName: String) {
        onLog("[Plugin] Direct URL installation rejected. Use verified plugin transactions.")
    }

    override fun backupWorld() {
        onLog("[Backup] Standard automated world backups are configured in settings.")
    }

    override fun sendCommand(command: String) {
        val session = currentSession
        if (session == null || session.process?.isAlive != true) {
            onLog("[Server] Cannot send command: server is not running.")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                sendRawCommand(session, command)
                withContext(Dispatchers.Main) {
                    onLog("> $command")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onLog("[Server] ERROR: Failed to send command: ${e.message}")
                }
            }
        }
    }

    protected fun sendRawCommand(session: ServerProcessSession, command: String) {
        val process = session.process ?: return
        val os = process.outputStream
        os.write((command.trim() + "\n").toByteArray(Charsets.UTF_8))
        os.flush()
    }

    override fun stopServer() {
        val session = currentSession ?: return
        scope.launch(Dispatchers.IO) {
            stopServerInternal(session, TerminationCause.USER_GRACEFUL_STOP)
        }
    }

    override fun restartServer() {
        scope.launch(Dispatchers.IO) {
            val activeStartup = startupJob
            if (
                currentSession == null &&
                activeStartup?.isActive == true
            ) {
                onLog(
                    "[Server] Cancelling the in-progress startup " +
                        "before restart."
                )
                activeStartup.cancelAndJoin()
            }

            val sessionToStop = currentSession
            if (sessionToStop?.process?.isAlive == true) {
                stopServerInternal(
                    sessionToStop,
                    TerminationCause.USER_GRACEFUL_STOP,
                )
            }

            val timeoutAt =
                System.currentTimeMillis() +
                    RESTART_STOP_TIMEOUT_MS

            while (
                (
                    currentSession != null ||
                        startupJob?.isActive == true
                ) &&
                System.currentTimeMillis() < timeoutAt
            ) {
                delay(250)
            }

            if (
                currentSession != null ||
                startupJob?.isActive == true
            ) {
                onLog(
                    "[Server] Restart aborted: previous process " +
                        "cleanup did not finish within the timeout."
                )
                healthMonitor.setStatus(ServerStatus.FAILED)
                return@launch
            }

            startServer(lastMemoryMb)
        }
    }

    override fun startServer(memoryMb: Int) {
        lastMemoryMb = memoryMb

        if (currentSession?.process?.isAlive == true) {
            onLog("[Server] Start ignored: this profile already owns a live process.")
            return
        }

        if (getStatus().isBlocking()) {
            onLog("[Server] Start ignored while status is ${getStatus()}.")
            return
        }

        if (!startupMutex.tryLock()) {
            onLog("[Server] Startup is already in progress for profile $profileId.")
            return
        }

        startupJob = scope.launch(Dispatchers.IO) {
            try {
                DownloadServiceLeaseController.acquire(context, runtimeSessionId, "Preparing ${spec.displayName}")
                    .getOrElse { throw IOException("Unable to start required setup foreground service: ${it.message}", it) }
                healthMonitor.setStatus(ServerStatus.PREPARING)

                if (!serverDir.exists() && !serverDir.mkdirs()) {
                    throw IOException("Failed to create server directory: ${serverDir.absolutePath}")
                }

                onPreflightCheck()

                // Java runtime setup
                val targetJavaMajor = getRequiredJavaMajor()
                onLog("[Runtime] Requesting Java runtime ($targetJavaMajor)...")

                // Engine JAR verification & download
                val selectedVer = onGetSelectedVersion()
                val verifiedInstallation = InstalledEngineVersionRepository.read(serverDir)
                val jarName = engineVersion.jarFileName.ifBlank { spec.jarName }
                val serverJar = File(serverDir, jarName)

                val needsDownload = !InstalledEngineVersionRepository.matches(serverDir, engineVersion, selectedVer) ||
                    !serverJar.isFile || serverJar.length() == 0L

                if (!needsDownload) {
                    if (verifiedInstallation != null) {
                        onLog("[Engine] Verified installation cached: ${verifiedInstallation.versionName} (${verifiedInstallation.versionId}).")
                        resolvedIdentity = verifiedInstallation.resolvedIdentity
                    }
                } else {
                    healthMonitor.setStatus(ServerStatus.DOWNLOADING)
                }

                // Runtime installation and engine provisioning are independent; run concurrently.
                var preparedRuntime: RuntimePreparationResult.Ready? = null
                coroutineScope {
                    val runtimeJob = launch {
                        preparedRuntime = when (
                            val result = JavaRuntimeManager.ensureRuntimeReady(
                                context,
                                targetJavaMajor,
                                onLog
                            )
                        ) {
                            is RuntimePreparationResult.Ready -> result
                            is RuntimePreparationResult.Unsupported -> throw IOException(result.message)
                            is RuntimePreparationResult.Failure -> throw IOException(result.message)
                        }
                    }
                    val engineJob = launch {
                        if (needsDownload) {
                            provisionVerifiedEngineJar(selectedVer, serverJar, targetJavaMajor)
                        }
                    }
                    listOf(runtimeJob, engineJob).joinAll()
                }

                val runtimeReady = requireNotNull(preparedRuntime)
                require(runtimeReady.javaMajor == targetJavaMajor) {
                    "Runtime Java major mismatch: expected $targetJavaMajor, got ${runtimeReady.javaMajor}"
                }
                onLog("[Runtime] Java ${runtimeReady.javaMajor} ready at ${runtimeReady.runtimeHome.absolutePath}, launcher: ${runtimeReady.launcherFile.absolutePath}")

                onApplyEngineConfig()

                val effectiveJar = onPrepareWorldAndLaunchJar(serverJar)
                require(effectiveJar.isFile && effectiveJar.length() > 0L) {
                    "Prepared engine artifact is missing or empty: ${effectiveJar.absolutePath}"
                }

                val safeMemoryMb = memoryMb.coerceIn(256, 4096)
                val tempDir = File(serverDir, "tmp").apply { mkdirs() }

                val javaArguments = mutableListOf<String>()
                javaArguments += "-Xms128M"
                javaArguments += "-Xmx${safeMemoryMb}M"
                if (safeMemoryMb >= 512) {
                    // G1 uses spare cores for collection; Serial only wins on tiny heaps.
                    javaArguments += "-XX:+UseG1GC"
                    javaArguments += "-XX:MaxGCPauseMillis=200"
                    javaArguments += "-XX:G1NewSizePercent=30"
                    javaArguments += "-XX:G1MaxNewSizePercent=40"
                    javaArguments += "-XX:+ParallelRefProcEnabled"
                } else {
                    javaArguments += "-XX:+UseSerialGC"
                }
                javaArguments += "-Djava.awt.headless=true"
                javaArguments += "-Dfile.encoding=UTF-8"
                javaArguments += "-Djava.io.tmpdir=${tempDir.absolutePath}"
                javaArguments += "-Duser.dir=${serverDir.absolutePath}"

                if (spec.terminalMode == TerminalMode.DUMB) {
                    javaArguments += "-Dorg.jline.terminal.provider=dumb"
                    javaArguments += "-Dorg.jline.terminal.providers=dumb"
                    javaArguments += "-Dorg.jline.terminal.dumb=true"
                    javaArguments += "-Dorg.jline.terminal.jni=false"
                    javaArguments += "-Dorg.jline.terminal.exec=false"
                    javaArguments += "-Dorg.jline.terminal.ffm=false"
                    javaArguments += "-Djansi.mode=strip"
                }

                if (targetJavaMajor >= 24) {
                    javaArguments += "--enable-native-access=ALL-UNNAMED"
                }

                javaArguments += spec.jvmArguments
                javaArguments += additionalJvmArguments()

                when (engineVersion.launchMode) {
                    LaunchMode.JAVA_JAR -> {
                        javaArguments += "-jar"
                        javaArguments += effectiveJar.absolutePath
                    }
                    LaunchMode.MAIN_CLASS -> {
                        val mainClass = engineVersion.mainClass
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: throw IOException("Launch mode MAIN_CLASS requires an explicit main class")

                        javaArguments += "-cp"
                        javaArguments += effectiveJar.absolutePath
                        javaArguments += mainClass
                    }
                }

                javaArguments += spec.applicationArguments
                javaArguments += additionalApplicationArguments()

                val processSessionId = createProcessSessionId()

                val session = ServerProcessSession(
                    sessionId = processSessionId,
                    profileId = profileId,
                    engineId = getEngineId(),
                    port = port,
                    startedAt = System.currentTimeMillis()
                )

                val builder = JavaRuntimeManager.createLauncherProcessBuilder(
                    context = context,
                    launcherFile = runtimeReady.launcherFile,
                    runtimeHome = runtimeReady.runtimeHome,
                    javaMajor = runtimeReady.javaMajor,
                    javaArguments = javaArguments,
                    workingDir = serverDir
                )

                onLog("[Server] Launching process (${spec.displayName})...")
                healthMonitor.setStatus(ServerStatus.STARTING)
                val process = builder.start()
                session.process = process
                currentSession = session

                healthMonitor.onProcessStart()
                healthMonitor.setStatus(ServerStatus.PROCESS_STARTED)

                ServerServiceLeaseController.acquire(
                    context,
                    profileId,
                    spec.displayName
                ).getOrElse { error ->
                    session.terminationCause = TerminationCause.STARTUP_FAILURE_STOP
                    process.destroyForciblyCompat()
                    throw IOException(
                        "Unable to start server foreground service: ${error.message}",
                        error
                    )
                }

                ServerRunStateStore.markRunning(
                    serverDir,
                    session.sessionId,
                    getProcessId(),
                )

                startExitWatcher(session)
                startLogCapture(session)
                onProcessStartedHook(session)
                startTimeoutWarningJob(session)

            } catch (cancelled: CancellationException) {
                onLog("[Server] Startup cancelled for profile $profileId.")
                if (currentSession == null) {
                    healthMonitor.setStatus(ServerStatus.STOPPED)
                }
                throw cancelled
            } catch (e: Throwable) {
                onLog("[Server] ERROR: Startup failed: ${e.message}")
                Log.e("JvmServerEngineBase", "Startup failed", e)
                healthMonitor.setStatus(ServerStatus.FAILED)
                performFailedStartupCleanup()
            } finally {
                DownloadServiceLeaseController.release(context, runtimeSessionId)
                startupJob = null
                startupMutex.unlock()
            }
        }
    }

    protected open fun additionalJvmArguments(): List<String> = emptyList()
    protected open fun additionalApplicationArguments(): List<String> = emptyList()

    protected open fun onGetSelectedVersion(): String = "AUTO"
    internal open suspend fun onPreflightCheck() {}
    protected open suspend fun onApplyEngineConfig() {}
    private suspend fun provisionVerifiedEngineJar(
        selectedVer: String,
        serverJar: File,
        targetJavaMajor: Int,
    ) {
        onLog("[Engine] Downloading verified engine build (${engineVersion.id})...")
        val downloadResult = Downloader.downloadServerJar(
            context = context,
            version = engineVersion,
            destination = serverJar,
            minecraftVersion = selectedVer,
        ) { msg ->
            onLog(msg)
        }

        when (downloadResult) {
            is ServerJarDownloadResult.Success -> {
                resolvedIdentity = downloadResult.identity
            }
            is ServerJarDownloadResult.Cancelled -> throw CancellationException("Download cancelled")
            is ServerJarDownloadResult.Failure -> throw IOException(downloadResult.message)
        }

        val effectiveResolvedIdentity = resolvedIdentity
        val jarValidation = InstalledEngineVersionRepository.validateJar(
            serverJar,
            engineVersion.launchMode,
            engineVersion.mainClass,
        )

        require(jarValidation.valid) {
            jarValidation.error
                ?: "Downloaded engine JAR failed final validation"
        }

        val installedInfo =
            EngineInstallationMetadataFactory.create(
                version = engineVersion,
                bedrockVersion = selectedVer,
                jarFile = serverJar,
                validation = jarValidation,
                resolvedIdentity = effectiveResolvedIdentity,
                runtimeJavaVersion = targetJavaMajor,
            )
        val actualJarSha256 =
            installedInfo.jarSha256
                ?: throw IOException(
                    "Installed engine SHA-256 is missing"
                )
        val installationWritten = InstalledEngineVersionRepository.write(serverDir, installedInfo)
        if (!installationWritten) {
            serverJar.delete()
            throw IOException("Engine JAR was downloaded but installation metadata could not be committed")
        }
        require(
            InstalledEngineVersionRepository.matches(
                serverDir,
                engineVersion,
                selectedVer
            )
        ) {
            "Downloaded engine installation failed final integrity verification"
        }
        onLog("[Engine] Engine build verified and saved.")
        effectiveResolvedIdentity?.let {
            onLog(
                "[Engine] Installed official resolved ${engineVersion.displayName} build " +
                    "#${it.resolvedBuildNumber}; SHA-256 $actualJarSha256"
            )
        }
    }

    protected open suspend fun onPrepareWorldAndLaunchJar(serverJar: File): File = serverJar
    protected open fun onLogLineProcessed(line: String, session: ServerProcessSession) {}
    protected open fun onProcessStartedHook(session: ServerProcessSession) {}
    protected open suspend fun onCleanupProcessHook(session: ServerProcessSession, exitCode: Int) {}
    protected open fun onHealthEvent(
        event: HealthEvent,
        line: String,
        session: ServerProcessSession
    ) = Unit

    protected fun startExitWatcher(
        session: ServerProcessSession,
    ) {
        exitWatcherJob = scope.launch(Dispatchers.IO) {
            val process = session.process ?: return@launch
            val exitCode = process.waitFor()
            handleProcessExit(session, exitCode)
        }
    }

    protected open suspend fun handleProcessExit(
        session: ServerProcessSession,
        exitCode: Int,
    ) {
        withContext(Dispatchers.Main) {
            if (isCurrentProcessSession(session)) {
                onLog(
                    "[Server] Process exited with code $exitCode " +
                        "(session ${session.sessionId})"
                )
            } else {
                onLog(
                    "[Runtime] Stale process session " +
                        "${session.sessionId} exited with code $exitCode; " +
                        "current process state will not be changed."
                )
            }

            when (session.terminationCause) {
                TerminationCause.NONE -> {
                    session.terminationCause =
                        TerminationCause.UNEXPECTED_EXIT
                    session.runtimeFailureClass =
                        if (session.onlineModeConfirmed) {
                            "CRASH_AFTER_ONLINE"
                        } else {
                            "CRASH_BEFORE_ONLINE"
                        }
                }

                TerminationCause.NORMAL_STOP -> {
                    session.runtimeFailureClass = "NORMAL_EXIT"
                }

                else -> {
                    session.runtimeFailureClass =
                        session.terminationCause.name
                }
            }
        }

        // Always clean the exited session. cleanupProcess() protects shared
        // current-session state and clears the run-state marker only if this
        // exact process session owns it.
        cleanupProcess(session, exitCode)
    }

    protected fun startLogCapture(
        session: ServerProcessSession,
    ) {
        logJob = scope.launch(Dispatchers.IO) {
            try {
                val process = session.process ?: return@launch

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        if (!isCurrentProcessSession(session)) {
                            return@useLines
                        }

                        val normalized = stripAnsi(rawLine)

                        val accepted = withContext(Dispatchers.Main) {
                            if (!isCurrentProcessSession(session)) {
                                return@withContext false
                            }

                            onLog(normalized)

                            if (
                                normalized.contains("Log4j2") ||
                                normalized.contains("CompatibilityError")
                            ) {
                                synchronized(session.compatibilityErrors) {
                                    val used =
                                        session.compatibilityErrors
                                            .sumOf(String::length)

                                    if (
                                        used <
                                        MAX_COMPATIBILITY_ERROR_LENGTH
                                    ) {
                                        session.compatibilityErrors +=
                                            normalized.take(
                                                MAX_COMPATIBILITY_ERROR_LENGTH -
                                                    used
                                            )
                                    }
                                }
                            }

                            handleBootstrap(session, normalized)

                            if (!isCurrentProcessSession(session)) {
                                return@withContext false
                            }

                            val healthEvent =
                                healthMonitor.analyzeLogLine(
                                    normalized,
                                    spec.successPatterns,
                                    spec.fatalErrorPatterns,
                                )

                            when (healthEvent) {
                                HealthEvent.ENGINE_READY -> {
                                    session.engineReady = true

                                    if (
                                        getStatus() ==
                                            ServerStatus.STARTING ||
                                        getStatus() ==
                                            ServerStatus.PROCESS_STARTED ||
                                        getStatus() ==
                                            ServerStatus.NETWORK_READY
                                    ) {
                                        healthMonitor.setStatus(
                                            ServerStatus.ENGINE_READY
                                        )
                                    }
                                }

                                HealthEvent.STARTUP_FATAL -> {
                                    stopServerInternal(
                                        session,
                                        TerminationCause
                                            .STARTUP_FAILURE_STOP,
                                    )
                                }

                                HealthEvent.RUNTIME_FATAL -> {
                                    stopServerInternal(
                                        session,
                                        TerminationCause
                                            .RUNTIME_FAILURE_STOP,
                                    )
                                }

                                else -> Unit
                            }

                            if (!isCurrentProcessSession(session)) {
                                return@withContext false
                            }

                            onHealthEvent(
                                healthEvent,
                                normalized,
                                session,
                            )

                            if (isCurrentProcessSession(session)) {
                                onLogLineProcessed(
                                    normalized,
                                    session,
                                )
                            }

                            true
                        }

                        if (!accepted) {
                            return@useLines
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isCurrentProcessSession(session)) {
                    withContext(Dispatchers.Main) {
                        if (isCurrentProcessSession(session)) {
                            onLog(
                                "[Runtime] Log capture ended with error: " +
                                    (error.message
                                        ?: error::class.java.simpleName)
                            )
                        }
                    }
                }
            }
        }
    }

    protected suspend fun handleBootstrap(
        session: ServerProcessSession,
        normalizedLine: String,
    ) {
        if (!isCurrentProcessSession(session)) return

        if (
            getStatus() != ServerStatus.STARTING &&
            getStatus() != ServerStatus.PROCESS_STARTED
        ) {
            return
        }

        for (rule in spec.bootstrapRules) {
            if (!isCurrentProcessSession(session)) return

            session.bootstrapMutex.withLock {
                if (!isCurrentProcessSession(session)) {
                    return@withLock
                }

                val count =
                    session.bootstrapState.getOrDefault(
                        rule.id,
                        0,
                    )

                if (count >= rule.maximumResponses) {
                    return@withLock
                }

                val matches = rule.promptPatterns.any {
                    normalizedLine.contains(
                        it,
                        ignoreCase = true,
                    )
                }

                if (!matches) {
                    return@withLock
                }

                session.bootstrapState[rule.id] = count + 1

                onLog(
                    "[Bootstrap] ${spec.family} requested " +
                        "initial interaction (${rule.id})."
                )

                scope.launch(Dispatchers.IO) {
                    try {
                        if (
                            isCurrentProcessSession(session) &&
                            session.process?.isAlive == true
                        ) {
                            sendRawCommand(
                                session,
                                rule.response,
                            )

                            withContext(Dispatchers.Main) {
                                if (isCurrentProcessSession(session)) {
                                    onLog(
                                        "[Bootstrap] Sent response: " +
                                            rule.response
                                    )
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (isCurrentProcessSession(session)) {
                            withContext(Dispatchers.Main) {
                                if (isCurrentProcessSession(session)) {
                                    onLog(
                                        "[Bootstrap] Failed to send " +
                                            "response: ${error.message}"
                                    )
                                }
                            }
                        }

                        session.bootstrapMutex.withLock {
                            val current =
                                session.bootstrapState[rule.id] ?: 1

                            session.bootstrapState[rule.id] =
                                (current - 1).coerceAtLeast(0)
                        }
                    }
                }
            }
        }
    }

    protected open fun shouldStopForStartupTimeout(
        session: ServerProcessSession,
    ): Boolean {
        if (!isCurrentProcessSession(session)) return false
        if (session.process?.isAlive != true) return false
        if (
            session.terminationCause !=
            TerminationCause.NONE
        ) {
            return false
        }
        if (session.onlineModeConfirmed) return false

        return when (getStatus()) {
            ServerStatus.STARTING,
            ServerStatus.PROCESS_STARTED,
            ServerStatus.NETWORK_READY,
            ServerStatus.ENGINE_READY,
            ServerStatus.WORLD_PROVISIONALLY_LOADED,
            ServerStatus.WORLD_VERIFIED -> true

            else -> false
        }
    }

    protected fun startTimeoutWarningJob(
        session: ServerProcessSession,
    ) {
        session.startupTimeoutJob?.cancel()

        session.startupTimeoutJob =
            scope.launch(Dispatchers.IO) {
                delay(STARTUP_TIMEOUT_MS)

                if (!shouldStopForStartupTimeout(session)) {
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (!shouldStopForStartupTimeout(session)) {
                        return@withContext
                    }

                    onLog(
                        "[Server] Startup timed out before " +
                            "complete readiness for session " +
                            session.sessionId
                    )

                    stopServerInternal(
                        session,
                        TerminationCause.STARTUP_TIMEOUT,
                    )
                }
            }
    }

    protected suspend fun stopServerInternal(session: ServerProcessSession, cause: TerminationCause) {
        session.sessionMutex.withLock {
            if (session.terminationCause == TerminationCause.NONE) {
                session.terminationCause = if (cause == TerminationCause.USER_GRACEFUL_STOP) TerminationCause.NORMAL_STOP else cause
            }

            if (cause == TerminationCause.USER_GRACEFUL_STOP ||
                cause == TerminationCause.USER_SIGTERM ||
                cause == TerminationCause.USER_FORCE_KILL
            ) {
                healthMonitor.setStatus(ServerStatus.STOPPING)
            }

            onLog("[Server] Stopping process (cause: $cause)...")
            val p = session.process
            if (p?.isAlive == true) {
                val stopCommand = spec.stopCommand.trim()
                if (stopCommand.isNotEmpty()) {
                    runCatching {
                        sendRawCommand(session, stopCommand)
                    }.onFailure {
                        onLog("[Server] Graceful stop command failed: ${it.message}")
                    }
                }

                val terminatedGracefully = withContext(Dispatchers.IO) {
                    p.waitForCompat(5000, TimeUnit.MILLISECONDS)
                }
                if (!terminatedGracefully && p.isAlive) {
                    onLog("[Server] Process did not exit gracefully; forcing destruction.")
                    p.destroyForciblyCompat()
                }
            }
        }
    }

    protected open suspend fun cleanupProcess(
        session: ServerProcessSession,
        exitCode: Int,
    ) {
        if (
            !session.cleanupStarted.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        session.startupTimeoutJob?.cancel()
        session.startupTimeoutJob = null

        session.probeJob?.cancel()
        session.probeJob = null

        withContext(Dispatchers.IO) {
            ServerRunStateStore.clearIfOwned(
                serverDir,
                session.sessionId,
            )
        }

        onCleanupProcessHook(session, exitCode)

        withContext(Dispatchers.Main) {
            if (!isCurrentProcessSession(session)) {
                onLog(
                    "[Runtime] Cleanup completed for stale session " +
                        session.sessionId +
                        " without changing the current process."
                )
                return@withContext
            }

            exitWatcherJob = null
            logJob?.cancel()
            logJob = null

            when (session.terminationCause) {
                TerminationCause.USER_GRACEFUL_STOP,
                TerminationCause.USER_SIGTERM,
                TerminationCause.USER_FORCE_KILL,
                TerminationCause.NORMAL_STOP -> {
                    healthMonitor.setStatus(ServerStatus.STOPPED)
                }

                TerminationCause.STARTUP_FAILURE_STOP,
                TerminationCause.STARTUP_TIMEOUT -> {
                    healthMonitor.setStatus(ServerStatus.FAILED)
                }

                TerminationCause.RUNTIME_FAILURE_STOP -> {
                    healthMonitor.setStatus(ServerStatus.CRASHED)
                }

                TerminationCause.WORLD_INCOMPATIBLE_STOP -> {
                    healthMonitor.setStatus(
                        ServerStatus.WORLD_LOAD_FAILED
                    )
                }

                TerminationCause.PORT_MISMATCH_STOP -> {
                    healthMonitor.setStatus(
                        ServerStatus.PORT_MISMATCH
                    )
                }

                TerminationCause.PROTOCOL_MISMATCH_STOP -> {
                    healthMonitor.setStatus(
                        ServerStatus.PROTOCOL_MISMATCH
                    )
                }

                TerminationCause.NONE,
                TerminationCause.UNEXPECTED_EXIT -> {
                    healthMonitor.onProcessExit(
                        exitCode,
                        intentional = false,
                    )
                }
            }

            currentSession = null

            ServerServiceLeaseController.release(
                context,
                profileId,
            )

            DownloadServiceLeaseController.release(
                context,
                runtimeSessionId,
            )

            onLog(
                "[Server] Cleanup completed for session " +
                    "${session.sessionId}. Final status: " +
                    getStatus()
            )
        }
    }

    protected fun performFailedStartupCleanup() {
        scope.launch {
            val session = currentSession
            if (session != null) {
                cleanupProcess(session, -1)
            } else {
                healthMonitor.setStatus(ServerStatus.FAILED)
            }
        }
    }

    protected fun stripAnsi(input: String): String {
        return input.replace("\u001B\\[[;\\d]*[A-Za-z]".toRegex(), "")
    }

    protected companion object {
        const val STARTUP_TIMEOUT_MS = 120_000L
        const val RESTART_STOP_TIMEOUT_MS = 30_000L
        const val MAX_COMPATIBILITY_ERROR_LENGTH = 16_000
    }
}
