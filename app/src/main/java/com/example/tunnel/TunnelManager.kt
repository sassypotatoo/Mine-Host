package com.example.tunnel

import android.content.Context
import android.system.Os
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.server.destroyForciblyCompat
import com.example.server.waitForCompat
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class TunnelStatus { STOPPED, STARTING, RUNNING, RECONNECTING, ERROR }

data class FrpTunnelConfig(
    val serverId: String,
    val frpsHost: String,
    val frpsPort: Int = 7000,
    val remoteUdpPort: Int,
    val localUdpPort: Int,
    val authToken: String,
    val tlsEnabled: Boolean = true
)

data class TunnelHealth(
    val processAlive: Boolean = false,
    val publicAddress: String = "",
    val latencyMs: Long? = null,
    val lastError: String? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

/** Real per-server FRPC process owner. No relay or binary means no fake success. */
class TunnelManager(private val context: Context, private val onLog: (String) -> Unit) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val credentialStore = TunnelCredentialStore(context)
    private val tunnelRoot = File(context.filesDir, "frp").apply { mkdirs() }
    private var process: Process? = null
    private var monitorJob: Job? = null
    private var logJob: Job? = null
    private var activeConfig: FrpTunnelConfig? = null

    private val _status = MutableStateFlow(TunnelStatus.STOPPED)
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()
    private val _publicAddress = MutableStateFlow("")
    val publicAddress: StateFlow<String> = _publicAddress.asStateFlow()
    private val _health = MutableStateFlow(TunnelHealth())
    val health: StateFlow<TunnelHealth> = _health.asStateFlow()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun binaryFile(): File = File(tunnelRoot, "frpc-arm64")

    fun installVerifiedBinary(source: File, expectedSha256: String): Result<Unit> = runCatching {
        require(source.isFile && source.length() > 1024L * 1024L) { "FRPC binary is missing or too small" }
        require(sha256(source).equals(expectedSha256, true)) { "FRPC SHA-256 verification failed" }
        val target = binaryFile()
        val part = File(tunnelRoot, "frpc-arm64.part")
        source.copyTo(part, overwrite = true)
        require(sha256(part).equals(expectedSha256, true)) { "Staged FRPC verification failed" }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        target.setExecutable(true, false)
        runCatching { Os.chmod(target.absolutePath, 0x1ED) }
        require(target.canExecute()) { "FRPC binary is not executable" }
    }

    fun hasToken(serverId: String = activeConfig?.serverId.orEmpty()): Boolean =
        serverId.isNotBlank() && !credentialStore.readToken(serverId).isNullOrBlank()

    fun setToken(serverId: String, token: String) = credentialStore.saveToken(serverId, token)

    suspend fun startTunnel(config: FrpTunnelConfig): Boolean {
        if (process?.isAlive == true) {
            if (activeConfig?.serverId == config.serverId) return true
            onLog("[Tunnel] Another tunnel process is already owned by this manager instance.")
            return false
        }
        val binary = binaryFile()
        if (!binary.isFile || !binary.canExecute()) {
            fail("FRPC ARM64 binary is not installed or executable")
            return false
        }
        if (!config.frpsHost.matches(Regex("[A-Za-z0-9.-]+"))) {
            fail("FRPS host is invalid")
            return false
        }
        if (config.frpsPort !in 1..65535 || config.localUdpPort !in 1..65535 || config.remoteUdpPort !in 1..65535) {
            fail("FRP port is invalid")
            return false
        }
        val token = config.authToken.ifBlank { credentialStore.readToken(config.serverId).orEmpty() }
        if (token.isBlank()) {
            fail("FRPS authentication token is not configured")
            return false
        }
        credentialStore.saveToken(config.serverId, token)
        activeConfig = config.copy(authToken = "")
        _status.value = TunnelStatus.STARTING

        val configFile = File(context.cacheDir, "frpc-${config.serverId}-${UUID.randomUUID()}.toml")
        return try {
            configFile.writeText(buildConfig(config, token))
            runCatching { Os.chmod(configFile.absolutePath, 0x180) }
            val builder = ProcessBuilder(binary.absolutePath, "-c", configFile.absolutePath)
                .directory(tunnelRoot)
                .redirectErrorStream(true)
            process = builder.start()
            startLogCapture(process!!, config, configFile)
            startMonitor(config)
            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && process?.isAlive == true) {
                when (_status.value) {
                    TunnelStatus.RUNNING -> {
                        onLog("[Tunnel] Verified FRPC login/proxy startup for server ${config.serverId}; public UDP address ${_publicAddress.value}")
                        return true
                    }
                    TunnelStatus.ERROR -> return false
                    else -> delay(200)
                }
            }
            fail(if (process?.isAlive == true) "FRPC did not confirm tunnel readiness before timeout" else "FRPC exited before the tunnel became active")
            false
        } catch (error: Throwable) {
            configFile.delete()
            fail(error.message ?: "FRPC start failed")
            false
        }
    }

    @Deprecated("Use startTunnel(FrpTunnelConfig) so ownership and relay settings are explicit")
    suspend fun startTunnel(port: Int, isUdp: Boolean): Boolean {
        fail("Tunnel configuration is required before starting FRPC")
        return false
    }

    fun stopTunnel() {
        monitorJob?.cancel()
        logJob?.cancel()
        val current = process
        if (current?.isAlive == true) {
            current.destroy()
            runCatching {
                if (!current.waitForCompat(5, TimeUnit.SECONDS)) current.destroyForciblyCompat()
            }
        }
        process = null
        activeConfig = null
        _status.value = TunnelStatus.STOPPED
        _publicAddress.value = ""
        _health.value = TunnelHealth()
        onLog("[Tunnel] Tunnel stopped.")
    }

    private fun startLogCapture(started: Process, config: FrpTunnelConfig, configFile: File) {
        logJob?.cancel()
        logJob = scope.launch {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!isActive) return@forEach
                        val safe = line.replace(config.authToken, "[REDACTED]")
                        appendLog(safe)
                        onLog("[FRPC] $safe")
                        when {
                            safe.contains("start proxy success", true) || safe.contains("login to server success", true) -> {
                                _status.value = TunnelStatus.RUNNING
                                _publicAddress.value = "${config.frpsHost}:${config.remoteUdpPort}"
                            }
                            safe.contains("login to server failed", true) || safe.contains("start error", true) -> fail(safe)
                            safe.contains("reconnect", true) -> _status.value = TunnelStatus.RECONNECTING
                        }
                    }
                }
            } finally {
                configFile.delete()
                val exit = runCatching { started.waitFor() }.getOrDefault(-1)
                if (_status.value != TunnelStatus.STOPPED) fail("FRPC exited with code $exit")
            }
        }
    }

    private fun startMonitor(config: FrpTunnelConfig) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && process?.isAlive == true) {
                val latency = measureTcpLatency(config.frpsHost, config.frpsPort)
                _health.value = TunnelHealth(
                    processAlive = true,
                    publicAddress = _publicAddress.value,
                    latencyMs = latency,
                    lastError = if (_status.value == TunnelStatus.ERROR) _health.value.lastError else null
                )
                delay(10_000)
            }
        }
    }

    private fun buildConfig(config: FrpTunnelConfig, token: String): String = """
        serverAddr = "${config.frpsHost}"
        serverPort = ${config.frpsPort}
        auth.method = "token"
        auth.token = "${escapeToml(token)}"
        transport.tls.enable = ${config.tlsEnabled}
        loginFailExit = false

        [[proxies]]
        name = "minehost-${config.serverId.replace(Regex("[^A-Za-z0-9_-]"), "-")}"
        type = "udp"
        localIP = "127.0.0.1"
        localPort = ${config.localUdpPort}
        remotePort = ${config.remoteUdpPort}
    """.trimIndent() + "\n"

    private fun escapeToml(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "")
    private fun appendLog(line: String) { _logs.value = (_logs.value + line).takeLast(500) }
    private fun fail(message: String) {
        _status.value = TunnelStatus.ERROR
        _health.value = _health.value.copy(processAlive = process?.isAlive == true, lastError = message, lastUpdatedAt = System.currentTimeMillis())
        onLog("[Tunnel] ERROR: $message")
    }

    private fun measureTcpLatency(host: String, port: Int): Long? = runCatching {
        val started = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
        (System.nanoTime() - started) / 1_000_000
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun close() {
        stopTunnel()
        scope.cancel()
    }

    companion object {
        private const val STARTUP_TIMEOUT_MS = 20_000L
    }

}
