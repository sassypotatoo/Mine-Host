package com.example.server.health

import com.example.server.ServerStatus

enum class LogSeverity { NORMAL, WARNING, STARTUP_FATAL, RUNTIME_FATAL, READINESS_CANDIDATE }
enum class HealthEvent {
    NONE,
    WARNING,
    STARTUP_FATAL,
    RUNTIME_FATAL,
    READINESS_CANDIDATE,
    ENGINE_READY,
    WORLD_INCOMPATIBLE,
    BOUND_PORT_DETECTED,
    SERVER_STOPPING
}

/** Pure status classifier. Process ownership and final crash truth remain in BaseJavaEngine. */
class ServerHealthMonitor(
    private val onLog: (String) -> Unit,
    private val onStatusChange: (ServerStatus) -> Unit,
) {
    @Volatile private var currentStatus: ServerStatus = ServerStatus.STOPPED

    // Regular expressions for dynamic parsing
    // Deprecated: Use BedrockBoundPortParser
    
    private val WORLD_FATAL_PATTERNS = listOf(
        "Invalid chunk serializer",
        "Unsupported chunk serializer",
        "Failed to read chunk",
        "Unsupported world format",
        "Cannot deserialize chunk",
        "Corrupt LevelDB chunk",
        "Missing required world database",
        "Failed to load the active level",
        "No runtime2legacy mapping",
        "Can not find legacyId",
        "Cannot find legacyId",
        "Missing block runtime mapping",
        "Missing block runtime id mapping",
        "Missing block runtime id mappings",
        "Tried to create block entity that doesn't exists",
        "Tried to create block entity that does not exist",
        "Unknown block entity",
        "Unsupported palette",
        "Failed to decode chunk",
        "Failed to load actor data",
        "Failed to load entity data",
        "Failed to load actor/entity data",
    )

    fun setStatus(status: ServerStatus) {
        if (currentStatus == status) return
        currentStatus = status
        onStatusChange(status)
    }

    fun getStatus(): ServerStatus = currentStatus

    fun analyzeLogLine(
        line: String,
        successPatterns: List<String>,
        fatalPatterns: List<String> = emptyList(),
    ): HealthEvent {
        if (line.isBlank()) return HealthEvent.NONE
        val stripped = stripAnsi(line)
        val event = classifyLine(stripped, successPatterns, fatalPatterns)
        when (event) {
            HealthEvent.STARTUP_FATAL -> onLog("[Server] STARTUP FATAL: $stripped")
            HealthEvent.RUNTIME_FATAL -> onLog("[Server] RUNTIME FATAL: $stripped")
            HealthEvent.WORLD_INCOMPATIBLE -> onLog("[Server] WORLD INCOMPATIBLE: $stripped")
            else -> Unit
        }
        return event
    }

    private fun classifyLine(line: String, successPatterns: List<String>, engineFatalPatterns: List<String>): HealthEvent {
        // Bound port detection
        if (com.example.server.engine.BedrockBoundPortParser.parse(line) != null) return HealthEvent.BOUND_PORT_DETECTED

        // Stopping detection
        if (line.contains("Stopping server", true) ||
            line.contains("Shutting down", true) ||
            line.contains("Server stopping", true) ||
            line.contains("Engine stopping", true) ||
            line.contains("Stopping engine", true)) {
            return HealthEvent.SERVER_STOPPING
        }

        // World compatibility detection
        if (WORLD_FATAL_PATTERNS.any { line.contains(it, true) }) {
            return HealthEvent.WORLD_INCOMPATIBLE
        }

        // Engine readiness patterns
        if (successPatterns.any { line.contains(it, true) }) return HealthEvent.ENGINE_READY

        // Legacy/Generic readiness
        if (line.contains("RakNet listener started", true) || line.contains("Query is running", true)) {
            return HealthEvent.READINESS_CANDIDATE
        }

        if (line.contains("JLineNativeLoader", true) ||
            line.contains("Failed to load native library:jlinenative", true) ||
            line.contains("libutil.so.1", true) ||
            line.contains("unsupported environment", true) ||
            line.contains("Unrecognized format specifier", true) ||
            line.contains("sun.reflect.Reflection.getCallerClass", true) ||
            line.contains("terminally deprecated method", true)) {
            return HealthEvent.WARNING
        }

        val fatal = engineFatalPatterns.any { line.contains(it, true) } ||
            line.contains("Error occurred during initialization of VM", true) ||
            line.contains("Could not find or load main class", true) ||
            line.contains("UnsupportedClassVersionError", true) ||
            line.contains("FAILED to bind to", true) ||
            line.contains("Address already in use", true) ||
            line.contains("OutOfMemoryError", true) ||
            line.contains("NoClassDefFoundError", true) ||
            line.trim().startsWith("Exception in thread \"main\"")

        if (fatal) {
            return if (currentStatus == ServerStatus.ONLINE) HealthEvent.RUNTIME_FATAL else HealthEvent.STARTUP_FATAL
        }
        return HealthEvent.NONE
    }

    fun onProcessExit(exitCode: Int, intentional: Boolean) {
        val previous = currentStatus
        when {
            intentional || exitCode == 0 -> setStatus(ServerStatus.STOPPED)
            previous == ServerStatus.ONLINE || previous == ServerStatus.WORLD_VERIFIED -> setStatus(ServerStatus.CRASHED)
            previous == ServerStatus.WORLD_PROVISIONALLY_LOADED -> setStatus(ServerStatus.WORLD_LOAD_FAILED)
            previous == ServerStatus.STARTING || previous == ServerStatus.PROCESS_STARTED ||
                previous == ServerStatus.NETWORK_READY || previous == ServerStatus.ENGINE_READY ||
                previous == ServerStatus.PREPARING || previous == ServerStatus.DOWNLOADING -> setStatus(ServerStatus.FAILED)
            previous == ServerStatus.WORLD_LOAD_FAILED -> setStatus(ServerStatus.WORLD_LOAD_FAILED)
            previous == ServerStatus.PORT_MISMATCH -> setStatus(ServerStatus.PORT_MISMATCH)
            exitCode != 0 -> setStatus(ServerStatus.CRASHED)
            else -> setStatus(ServerStatus.STOPPED)
        }
    }

    fun onProcessStart() = setStatus(ServerStatus.STARTING)
    fun resetAfterFailure() = setStatus(ServerStatus.FAILED)
    fun resetToStopped() = setStatus(ServerStatus.STOPPED)

    private fun stripAnsi(input: String): String = input
        .replace("\u001B\\[[;\\d]*[A-Za-z]".toRegex(), "")
        .replace("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)".toRegex(), "")
        .replace("[\u0000-\u0008\u000B\u000C\u000E-\u001F]".toRegex(), "")
}
