package com.example.data

import com.example.server.ServerStatus
import com.example.server.engine.WorldSeedMode

data class ProfileListItem(
    val id: String,
    val name: String,
    val engineId: String,
    val engineVersionId: String,
    val bedrockVersion: String,
    val levelName: String,
    val iconPath: String?,
    val createdAt: Long
)

data class ServerCreationDraft(
    val name: String,
    val engineId: String,
    val engineVersionId: String,
    val bedrockVersion: String,
    val worldSeed: Long,
    val worldSeedMode: WorldSeedMode,
    val worldSeedKnown: Boolean,
    val levelName: String = "world",
    val gameMode: String = "0",
    val difficulty: String = "1",
    val levelType: String = "DEFAULT",
    val iconPath: String? = null,
    val port: Int = 19132,
    val memoryMb: Int = 600,
    val maxPlayers: Int = 10,
    val onlineMode: Boolean = true,
    val autoRestart: Boolean = true,
    val autoBackup: Boolean = false,
    val edition: ServerEdition = ServerEdition.BEDROCK,
    val networkType: ServerNetworkType = ServerNetworkType.BEDROCK_RAKNET_UDP,
    val minecraftVersion: String? = null,
    val minecraftEulaAccepted: Boolean = false,
)

data class ServerRuntimeEvent(
    val serverId: String,
    val sessionId: String,
    val status: ServerStatus,
    val terminationCause: com.example.server.engine.TerminationCause = com.example.server.engine.TerminationCause.NONE,
    val processAlive: Boolean = false
)

data class ActiveServerRuntimeState(
    val serverId: String? = null,
    val sessionId: String? = null,
    val status: ServerStatus = ServerStatus.STOPPED,
    val terminationCause: com.example.server.engine.TerminationCause = com.example.server.engine.TerminationCause.NONE,
    val processAlive: Boolean = false,
    val startedAtMillis: Long? = null,
    val reachedOnlineAtMillis: Long? = null,
    val runtimeFailureClass: String? = null, // e.g. "STARTUP_TIMEOUT", "CRASH", "NORMAL_STOP"
    val port: Int? = null,
    val lanAddress: String? = null,
    val uptimeMillis: Long? = null,
    val tps: Double? = null,
    val advertisedMinecraftVersion: String? = null,
    val advertisedProtocol: Int? = null,
    val worldCompatibility: CompatibilityStatus = CompatibilityStatus.UNKNOWN,
    val networkCompatibility: CompatibilityStatus = CompatibilityStatus.UNKNOWN
)

enum class CompatibilityStatus {
    VERIFIED,
    WARNING,
    FAILED,
    COMPATIBLE,
    INCOMPATIBLE,
    UNKNOWN
}

data class PluginEntry(
    val fileName: String,
    val name: String,
    val sizeBytes: Long,
    val enabled: Boolean,
    val lastModified: Long
)

data class BackupEntry(
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val path: String = ""
)

data class WorldEntry(
    val name: String,
    val folderName: String,
    val sizeBytes: Long = 0L,
    val lastModified: Long = 0L,
    val active: Boolean = false,
    val path: String = "",
    val modifiedAt: Long = 0L,
    val isCurrent: Boolean = false
)

data class ServerSettingsState(
    val serverName: String = "",
    val maxPlayers: Int = 10,
    val viewDistance: Int = 10,
    val port: Int = 19132,
    val memoryMb: Int = 600,
    val onlineMode: Boolean = true,
    val levelName: String = "world",
    val gameMode: String = "survival",
    val difficulty: String = "normal",
    val whitelistEnabled: Boolean = false,
    val autoRestart: Boolean = true,
    val autoBackup: Boolean = false
)

data class PlayerSession(
    val name: String,
    val joinedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    val online: Boolean = true,
    val xuid: String? = null
)

data class RuntimeMetrics(
    val ramBytes: Long? = 0L,
    val cpuPercent: Double? = 0.0,
    val tps: Double = 20.0,
    val processId: Long? = null,
    val uptimeMillis: Long? = null,
    val serverDataBytes: Long = 0L,
    val availableStorageBytes: Long = 0L,
    val playersOnline: Int? = null,
    val isPlayerTrackingAvailable: Boolean = false
)

data class DashboardUiState(
    val activeProfile: ServerProfile? = null,
    val status: ServerStatus = ServerStatus.STOPPED,
    val serverStatus: ServerStatus = ServerStatus.STOPPED,
    val activeOperationServerId: String? = null,
    val isServerReady: Boolean = false,
    val isProcessAlive: Boolean = false,
    val memoryMb: Int = 600,
    val uptimeMillis: Long? = null,
    val lanAddress: String? = null,
    val totalServerCount: Int = 0,
    val onlineServerCount: Int = 0,
    val activePlayers: Int? = 0,
    val onlinePlayers: Int? = 0,
    val maxPlayers: Int = 10,
    val ramBytes: Long? = 0L,
    val ramUsedBytes: Long? = 0L,
    val cpuPercent: Double? = 0.0,
    val cpuUsagePercent: Double? = 0.0,
    val tps: Double? = 20.0,
    val pingMs: Long? = null,
    val healthSummary: HealthSnapshot? = null,
    val latestMetricTimestamp: Long? = 0L,
    val isTunnelActive: Boolean = false,
    val tunnelAddress: String? = null,
    val recentLogs: List<String> = emptyList(),
    val hasServerProfile: Boolean = false
)

data class ActivityEvent(
    val id: String = "",
    val type: ActivityType = ActivityType.INFO,
    val timestamp: Long = 0L,
    val title: String = "",
    val description: String = "",
    val details: String = "",
    val serverId: String? = null
)

enum class ActivityType {
    SUCCESS,
    WARNING,
    ERROR,
    INFO,
    SERVER_START,
    SERVER_STOP,
    SERVER_RESTART,
    PLUGIN_INSTALL,
    PLUGIN_REMOVE,
    BACKUP_CREATE,
    BACKUP_RESTORE,
    WORLD_IMPORT,
    WORLD_EXPORT,
    CRASH,
    SETTINGS_CHANGE,
    GENERAL
}

data class CrashEntry(
    val id: String = "",
    val timestamp: Long = 0L,
    val title: String = "",
    val details: String = "",
    val severity: CrashSeverity = CrashSeverity.WARNING,
    val serverId: String? = null
)

enum class CrashSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    WARNING,
    ERROR
}

data class HealthSnapshot(
    val status: ServerStatus = ServerStatus.STOPPED,
    val score: Int = 100,
    val warnings: List<String> = emptyList()
)

data class AssistantMessage(
    val id: String = "",
    val fromUser: Boolean = false,
    val content: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class DynamicCatalog(
    val versions: List<com.example.server.version.EngineVersion> = emptyList()
)

data class AppSettingsState(
    val darkMode: Boolean = false,
    val language: String = "en",
    val startupScreen: String = "home",
    val dataSaver: Boolean = false,
    val hapticFeedback: Boolean = true,
    val confirmDestructiveActions: Boolean = true,
    val automaticEngineUpdateChecks: Boolean = true
)

data class NotificationSettingsState(
    val serverOfflineAlerts: Boolean = true,
    val crashAlerts: Boolean = true,
    val backupReminders: Boolean = true,
    val pluginUpdateAlerts: Boolean = true,
    val marketplaceAnnouncements: Boolean = false,
    val sound: Boolean = true,
    val vibration: Boolean = true,
    val quietHoursEnabled: Boolean = false
)

sealed class StartServerResult {
    object Started : StartServerResult()
    data class ValidationFailed(val reason: String) : StartServerResult()
    data class OperationBlocked(val reason: String) : StartServerResult()
    data class Failed(val reason: String) : StartServerResult()
}

data class OperationResult(
    val success: Boolean,
    val message: String
)

data class LocalServerProfile(
    val created: Boolean = false,
    val serverName: String = "",
    val templateId: String = "",
    val engineVersionId: String = "",
    val bedrockVersion: String = "",
    val levelName: String = "world",
    val iconPath: String? = null,
    val createdAt: Long = 0L
)

data class DashboardServerCardUiModel(
    val serverId: String = "",
    val serverName: String = "",
    val engineName: String = "",
    val status: ServerStatus = ServerStatus.STOPPED,
    val currentPlayers: Int? = null,
    val maxPlayers: Int = 10,
    val cpuPercent: Double? = null,
    val ramUsedBytes: Long? = null,
    val ramLimitBytes: Long = 600L * 1024 * 1024,
    val tps: Double? = null,
    val uptimeMillis: Long? = null,
    val ipAddress: String? = null,
    val port: Int = 19132,
    val compatibilitySummary: String? = null,
    val customIconPath: String? = null,
    val activeWorldPath: String? = null,
    val isActiveRuntime: Boolean = false,
    val isProcessAlive: Boolean = false,
    val activeOperationServerId: String? = null,
    val isStartEnabled: Boolean = false,
    val isStopEnabled: Boolean = false,
    val networkCompatibility: CompatibilityStatus = CompatibilityStatus.UNKNOWN,
    val worldCompatibility: CompatibilityStatus = CompatibilityStatus.UNKNOWN,
    val blockingReason: String? = null
)
