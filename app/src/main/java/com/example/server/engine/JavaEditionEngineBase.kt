package com.example.server.engine

import android.content.Context
import android.util.Log
import com.example.javaedition.JavaServerPropertiesAdapter
import com.example.javaedition.JavaStatusPingProbe
import com.example.javaedition.JavaStatusResult
import com.example.server.ServerStatus
import com.example.server.health.HealthEvent
import com.example.server.version.EngineVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

abstract class JavaEditionEngineBase(
    context: Context,
    serverDir: File,
    engineVersion: EngineVersion,
    port: Int,
    profileId: String,
    runtimeSessionId: String,
    val initialConfig: EngineServerConfig,
    val initialEulaAccepted: Boolean = false,
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

    @Volatile var minecraftEulaAccepted: Boolean = initialEulaAccepted
    @Volatile var serverConfig: EngineServerConfig = initialConfig

    init {
        this.onlineModeEnabled = initialConfig.onlineMode
    }

    @Volatile protected var lastJavaPingResult: JavaStatusResult? = null

    override fun setOnlineMode(online: Boolean) {
        super.setOnlineMode(online)
        serverConfig = serverConfig.copy(onlineMode = online)
    }

    override suspend fun onPreflightCheck() {
        super.onPreflightCheck()

        if (!minecraftEulaAccepted) {
            onLog("[EULA] FATAL: Minecraft EULA has not been accepted for profile $profileId.")
            throw IllegalStateException("[EULA] Minecraft EULA acceptance required before starting Java Edition server.")
        }

        val eulaFile = File(serverDir, "eula.txt")
        var fileEulaAccepted = false

        if (eulaFile.isFile && eulaFile.length() > 0L) {
            runCatching {
                val props = Properties()
                FileInputStream(eulaFile).use { props.load(it) }
                fileEulaAccepted = props.getProperty("eula", "false").trim().lowercase() == "true"
            }
        }

        // Ensure eula.txt is committed with eula=true
        if (!fileEulaAccepted) {
            onLog("[EULA] Writing eula.txt with eula=true...")
            runCatching {
                FileOutputStream(eulaFile).use { out ->
                    out.write("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n".toByteArray())
                    out.write("eula=true\n".toByteArray())
                }
            }.onFailure { err ->
                throw IOException("Failed to write eula.txt: ${err.message}", err)
            }
        }
        onLog("[EULA] Minecraft EULA accepted and verified.")
    }

    override suspend fun onApplyEngineConfig() {
        onLog("[Config] Writing Java server.properties (TCP port $port)...")
        JavaServerPropertiesAdapter.applyProperties(
            serverDir = serverDir,
            port = port,
            onlineMode = onlineModeEnabled,
            levelName = serverConfig.levelName,
            gameMode = serverConfig.gameMode,
            difficulty = serverConfig.difficulty,
            maxPlayers = serverConfig.maxPlayers,
            motd = serverConfig.motd
        )
        onLog("[Config] Java server.properties committed successfully.")
    }

    override fun onProcessStartedHook(session: ServerProcessSession) {
        super.onProcessStartedHook(session)
        startJavaStatusProbeLoop(session)
    }

    protected open fun startJavaStatusProbeLoop(session: ServerProcessSession) {
        session.probeJob?.cancel()
        session.probeJob = scope.launch(Dispatchers.IO) {
            onLog("[Readiness] Starting TCP & Java status ping probe for port $port...")
            var lastPingSuccessTime = 0L

            while (isActive && isCurrentProcessSession(session) && session.process?.isAlive == true) {
                if (session.onlineModeConfirmed) {
                    // Once online, perform periodic background ping refresh every 5s
                    delay(5000)
                } else {
                    delay(1000)
                }

                if (!isCurrentProcessSession(session) || session.process?.isAlive != true) break

                // Probe 1: Direct TCP Socket connection test
                val tcpSuccess = testTcpConnect("127.0.0.1", port, 1000)
                if (tcpSuccess) {
                    session.boundPort = port
                }

                // Probe 2: Java Server List Ping
                val pingRes = JavaStatusPingProbe.ping("127.0.0.1", port, 1500)
                if (pingRes.isSuccess) {
                    val status = pingRes.getOrNull()
                    if (status != null) {
                        lastJavaPingResult = status
                        session.networkReady = true
                        lastPingSuccessTime = System.currentTimeMillis()

                        if (!session.onlineModeConfirmed) {
                            onLog("[Readiness] Received valid Java Status Ping response from server!")
                            maybeMarkJavaOnline(session)
                        }
                    }
                }
            }
        }
    }

    private fun testTcpConnect(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    override fun onHealthEvent(event: HealthEvent, line: String, session: ServerProcessSession) {
        if (!isCurrentProcessSession(session)) return

        when (event) {
            HealthEvent.ENGINE_READY -> {
                onLog("[Readiness] Engine 'Done' log pattern observed.")
                session.engineReady = true
                maybeMarkJavaOnline(session)
            }
            HealthEvent.SERVER_STOPPING -> {
                session.onlineModeConfirmed = false
                if (getStatus() != ServerStatus.FAILED && getStatus() != ServerStatus.CRASHED) {
                    healthMonitor.setStatus(ServerStatus.STOPPING)
                }
                onLog("[Runtime] Java server process is stopping.")
            }
            else -> super.onHealthEvent(event, line, session)
        }
    }

    protected open fun maybeMarkJavaOnline(session: ServerProcessSession) {
        if (!isCurrentProcessSession(session)) return
        if (session.process?.isAlive != true) return
        if (session.terminationCause != TerminationCause.NONE) return
        if (!minecraftEulaAccepted) return
        if (!session.engineReady) {
            onLog("[Readiness] TCP/Status probe ready; waiting for engine 'Done' log...")
            return
        }
        if (!session.networkReady) {
            onLog("[Readiness] Engine 'Done' log received; waiting for TCP Status Ping probe...")
            return
        }
        if (session.onlineModeConfirmed) return

        session.onlineModeConfirmed = true
        session.reachedOnlineAtMillis = System.currentTimeMillis()
        onLog("[Readiness] Java server profile $profileId is now ONLINE on TCP port $port!")
        healthMonitor.setStatus(ServerStatus.ONLINE)
    }

    override fun getNetworkSnapshot(): ServerNetworkSnapshot? {
        val ping = lastJavaPingResult ?: return null
        return JavaNetworkSnapshot(
            port = port,
            versionName = ping.versionName,
            protocol = ping.protocol,
            description = ping.description,
            onlinePlayers = ping.onlinePlayers,
            maxPlayers = ping.maxPlayers
        )
    }
}
