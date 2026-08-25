package com.example.ui.navigation

sealed class MineHostDestination(val route: String) {
    data object Dashboard : MineHostDestination("dashboard")
    data object Plugins : MineHostDestination("plugins")
    data object ServerPlugins : MineHostDestination("plugins/{serverId}") { fun createRoute(serverId: String) = "plugins/$serverId" }
    data object Files : MineHostDestination("files")
    data object ServerFiles : MineHostDestination("files/{serverId}") { fun createRoute(serverId: String) = "files/$serverId" }
    data object Profile : MineHostDestination("profile")

    data object LegacyServer : MineHostDestination("legacy_server")
    data object ServerOverview : MineHostDestination("server_overview/{serverId}") { fun createRoute(serverId: String) = "server_overview/$serverId" }
    data object ServerCreation : MineHostDestination("server/create")
    data object ServerEdit : MineHostDestination("server/{serverId}/edit") { fun createRoute(serverId: String) = "server/$serverId/edit" }
    data object Marketplace : MineHostDestination("marketplace")
    data object PerformanceMonitor : MineHostDestination("performance_monitor")
    data object ServerPerformanceMonitor : MineHostDestination("performance_monitor/{serverId}") { fun createRoute(serverId: String) = "performance_monitor/$serverId" }
    data object VersionManager : MineHostDestination("version_manager")
    data object ServerVersionManager : MineHostDestination("version_manager/{serverId}") { fun createRoute(serverId: String) = "version_manager/$serverId" }
    data object BackupManager : MineHostDestination("backup_manager")
    data object ServerBackupManager : MineHostDestination("backup_manager/{serverId}") { fun createRoute(serverId: String) = "backup_manager/$serverId" }
    data object WorldManager : MineHostDestination("world_manager")
    data object ServerWorldManager : MineHostDestination("world_manager/{serverId}") { fun createRoute(serverId: String) = "world_manager/$serverId" }
    data object ServerNetworkAccess : MineHostDestination("network_access/{serverId}") { fun createRoute(serverId: String) = "network_access/$serverId" }

    data object AppSettings : MineHostDestination("app_settings")
    data object BatchDownload : MineHostDestination("batch_download")
    data object Account : MineHostDestination("account")
    data object Notifications : MineHostDestination("notifications")

    data object Activity : MineHostDestination("activity/{serverId}") { fun createRoute(serverId: String) = "activity/$serverId" }
    data object AiAssistant : MineHostDestination("ai_assistant/{serverId}") { fun createRoute(serverId: String) = "ai_assistant/$serverId" }
    data object AiAssistantTab : MineHostDestination("ai_assistant_global")
    data object Console : MineHostDestination("console")
    data object PerformanceRecommendations : MineHostDestination("performance/{serverId}") { fun createRoute(serverId: String) = "performance/$serverId" }
    data object CrashAnalysis : MineHostDestination("crash_analysis/{serverId}") { fun createRoute(serverId: String) = "crash_analysis/$serverId" }
    data object ServerHealth : MineHostDestination("server_health/{serverId}") { fun createRoute(serverId: String) = "server_health/$serverId" }
    data object AutoOptimization : MineHostDestination("auto_optimization/{serverId}") { fun createRoute(serverId: String) = "auto_optimization/$serverId" }
}
