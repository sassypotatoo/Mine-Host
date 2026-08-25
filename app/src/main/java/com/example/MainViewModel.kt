package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.notifications.MineHostNotificationManager
import com.example.server.Downloader
import com.example.server.FileInfo
import com.example.server.NetworkUtils
import com.example.server.ServerManager
import com.example.server.ServerManagerEvent
import com.example.server.ServerStatus
import com.example.server.canStart
import com.example.server.isBlocking
import com.example.server.engine.TerminationCause
import com.example.server.template.ServerTemplate
import com.example.server.template.TemplateRegistry
import com.example.server.players.ServerAccessListRepository
import com.example.server.players.ServerAccessListState
import com.example.server.version.EngineCompatibilityValidator
import com.example.server.version.EngineVersionTransactionManager
import com.example.server.version.InstalledEngineVersionRepository
import com.example.server.version.VersionSourceType
import com.example.server.version.EngineInstallability
import com.example.server.version.installability
import com.example.server.version.ArtifactValidationPolicy
import com.example.server.version.validationPolicy
import com.example.server.updates.*
import com.example.ai.*
import com.example.backup.BackupManagerV2
import com.example.friends.*
import com.example.diagnostics.DiagnosticExportManager
import com.example.plugins.PluginInstallResult
import com.example.plugins.PluginInstaller
import com.example.plugins.MarketplacePlugin
import com.example.plugins.PluginLifecycleManager
import com.example.prepare.PrepareServerCoordinator
import com.example.performance.OptimizationPlan
import com.example.performance.OptimizationPreset
import com.example.performance.ServerOptimizationManager
import com.example.performance.DeviceHealthMonitor
import com.example.performance.DeviceHealthSnapshot
import com.example.marketplace.MarketplaceCatalog
import com.example.marketplace.MarketplaceItem
import com.example.marketplace.MarketplaceInstaller
import com.example.tunnel.FrpTunnelConfig
import com.example.tunnel.TunnelHealth
import com.example.tunnel.TunnelStatus
import com.example.world.WorldManagerV2
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = application.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
    private val uiPrefs = application.getSharedPreferences("minehost_ui_prefs", Context.MODE_PRIVATE)
    private val metricsReaders = ConcurrentHashMap<String, ProcessMetricsReader>()
    private val playerRosterLocks = ConcurrentHashMap<String, Any>()
    private val playerAccessListRefreshGeneration = ConcurrentHashMap<String, AtomicInteger>()
    private val notificationManager = MineHostNotificationManager(appContext)
    private val mineHostApplication = application as MineHostApplication
    private val catalogRepository = mineHostApplication.catalogRepository
    private val profileRepository = mineHostApplication.profileRepository
    private val updateRepository = mineHostApplication.updateRepository
    private val authManager = mineHostApplication.authManager
    private val aiCredentialStore = mineHostApplication.aiCredentialStore
    private val aiAssistantService = mineHostApplication.aiAssistantService
    private val friendAccessRepository = mineHostApplication.friendAccessRepository
    private val marketplaceCatalogRepository = mineHostApplication.marketplaceCatalogRepository
    private val marketplaceInstaller = mineHostApplication.marketplaceInstaller
    private val deviceHealthMonitor = DeviceHealthMonitor(appContext)

    val authState = authManager.state
    val aiProviderMode: AiProviderMode
        get() = aiAssistantService.providerMode()

    val detectedReleases = updateRepository.detectedReleases
    val checkState = updateRepository.checkState
    val isCheckingUpdates = updateRepository.isChecking
    val lastUpdatesCheckedAt = updateRepository.lastCheckedAt
    val updatesCheckError = updateRepository.checkError

    private val _selectedServerId = MutableStateFlow(prefs.getString("selected_server_id", null))
    val selectedServerId: StateFlow<String?> = _selectedServerId.asStateFlow()

    val profiles: StateFlow<List<ServerProfile>> = profileRepository.profiles

    private val autoRestartJobs = ConcurrentHashMap<String, Job>()
    private val autoRestartAttempts = ConcurrentHashMap<String, Int>()
    private val manualStopRequested = ConcurrentHashMap.newKeySet<String>()

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _activeRuntime = MutableStateFlow(ActiveServerRuntimeState())
    val activeRuntime: StateFlow<ActiveServerRuntimeState> = _activeRuntime.asStateFlow()

    private val _runtimeStates = MutableStateFlow<Map<String, ActiveServerRuntimeState>>(emptyMap())
    val runtimeStates: StateFlow<Map<String, ActiveServerRuntimeState>> = _runtimeStates.asStateFlow()

    private val _logsByServer = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val logsByServer: StateFlow<Map<String, List<String>>> = _logsByServer.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _ipAddress = MutableStateFlow("Unknown")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _memoryMb = MutableStateFlow(prefs.getInt("memory_mb", 600))
    val memoryMb: StateFlow<Int> = _memoryMb.asStateFlow()

    private val _onlineMode = MutableStateFlow(prefs.getBoolean("online_mode", true))
    val onlineMode: StateFlow<Boolean> = _onlineMode.asStateFlow()

    private val initialTemplate = TemplateRegistry.ALL_TEMPLATES.firstOrNull {
        it.id == prefs.getString("template_id", TemplateRegistry.BEDROCK_POWER_NUKKIT_X.id)
    } ?: TemplateRegistry.BEDROCK_POWER_NUKKIT_X

    private val _activeTemplate = MutableStateFlow(initialTemplate)
    val activeTemplate: StateFlow<ServerTemplate> = _activeTemplate.asStateFlow()

    private val _activeEngineVersion = MutableStateFlow<com.example.server.version.EngineVersion?>(null)
    val activeEngineVersion: StateFlow<com.example.server.version.EngineVersion?> = _activeEngineVersion.asStateFlow()

    private val _currentFiles = MutableStateFlow<List<FileInfo>>(emptyList())
    val currentFiles: StateFlow<List<FileInfo>> = _currentFiles.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _selectedFilePath = MutableStateFlow<String?>(null)
    val selectedFilePath: StateFlow<String?> = _selectedFilePath.asStateFlow()

    private val _selectedFileContent = MutableStateFlow<String?>(null)
    val selectedFileContent: StateFlow<String?> = _selectedFileContent.asStateFlow()
    private val filePathByServer = ConcurrentHashMap<String, String>()
    private val editorPathByServer = ConcurrentHashMap<String, String>()

    private val _plugins = MutableStateFlow<List<PluginEntry>>(emptyList())
    val plugins: StateFlow<List<PluginEntry>> = _plugins.asStateFlow()

    private val _backups = MutableStateFlow<List<BackupEntry>>(emptyList())
    val backups: StateFlow<List<BackupEntry>> = _backups.asStateFlow()

    private val _worlds = MutableStateFlow<List<WorldEntry>>(emptyList())
    val worlds: StateFlow<List<WorldEntry>> = _worlds.asStateFlow()

    private val _pendingWorldImport = MutableStateFlow<WorldManagerV2.WorldImportPreview?>(null)
    val pendingWorldImport: StateFlow<WorldManagerV2.WorldImportPreview?> = _pendingWorldImport.asStateFlow()

    private val _worldImportProgress = MutableStateFlow<WorldManagerV2.ImportProgress?>(null)
    val worldImportProgress: StateFlow<WorldManagerV2.ImportProgress?> = _worldImportProgress.asStateFlow()

    private val _serverSettings = MutableStateFlow(ServerSettingsState(memoryMb = _memoryMb.value, onlineMode = _onlineMode.value))
    val serverSettings: StateFlow<ServerSettingsState> = _serverSettings.asStateFlow()

    private val _players = MutableStateFlow<List<PlayerSession>>(emptyList())
    val players: StateFlow<List<PlayerSession>> = _players.asStateFlow()
    private val _playersByServer = MutableStateFlow<Map<String, List<PlayerSession>>>(emptyMap())
    val playersByServer: StateFlow<Map<String, List<PlayerSession>>> = _playersByServer.asStateFlow()
    private val _playerAccessListsByServer = MutableStateFlow<Map<String, ServerAccessListState>>(emptyMap())
    val playerAccessListsByServer: StateFlow<Map<String, ServerAccessListState>> =
        _playerAccessListsByServer.asStateFlow()

    private val _metrics = MutableStateFlow(RuntimeMetrics())
    val metrics: StateFlow<RuntimeMetrics> = _metrics.asStateFlow()
    private val _metricsByServer = MutableStateFlow<Map<String, RuntimeMetrics>>(emptyMap())
    val metricsByServer: StateFlow<Map<String, RuntimeMetrics>> = _metricsByServer.asStateFlow()

    private val _dashboardUiState = MutableStateFlow(
        DashboardUiState(
            hasServerProfile = false,
            serverStatus = ServerStatus.STOPPED,
            isProcessAlive = false,
            activeOperationServerId = null,
            isServerReady = false,
            totalServerCount = 0,
            onlineServerCount = 0,
            activePlayers = null,
            ramUsedBytes = null,
            cpuUsagePercent = null,
            tps = null,
            pingMs = null,
            healthSummary = null,
            uptimeMillis = null,
            latestMetricTimestamp = null
        )
    )
    val dashboardUiState: StateFlow<DashboardUiState> = _dashboardUiState.asStateFlow()

    private val _activities = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val activities: StateFlow<List<ActivityEvent>> = _activities.asStateFlow()

    private val _crashes = MutableStateFlow<List<CrashEntry>>(emptyList())
    val crashes: StateFlow<List<CrashEntry>> = _crashes.asStateFlow()

    private val _health = MutableStateFlow(HealthSnapshot())
    val health: StateFlow<HealthSnapshot> = _health.asStateFlow()

    private val _deviceHealth = MutableStateFlow<DeviceHealthSnapshot?>(null)
    val deviceHealth: StateFlow<DeviceHealthSnapshot?> = _deviceHealth.asStateFlow()

    private val _recommendations = MutableStateFlow<List<String>>(emptyList())
    val recommendations: StateFlow<List<String>> = _recommendations.asStateFlow()

    private val _preparePlan = MutableStateFlow<PrepareServerCoordinator.Plan?>(null)
    val preparePlan: StateFlow<PrepareServerCoordinator.Plan?> = _preparePlan.asStateFlow()

    private val _optimizationPlan = MutableStateFlow<OptimizationPlan?>(null)
    val optimizationPlan: StateFlow<OptimizationPlan?> = _optimizationPlan.asStateFlow()

    private val _tunnelStatus = MutableStateFlow(TunnelStatus.STOPPED)
    val tunnelStatus: StateFlow<TunnelStatus> = _tunnelStatus.asStateFlow()
    private val _tunnelHealth = MutableStateFlow(TunnelHealth())
    val tunnelHealth: StateFlow<TunnelHealth> = _tunnelHealth.asStateFlow()
    private val _tunnelStatusByServer = MutableStateFlow<Map<String, TunnelStatus>>(emptyMap())
    val tunnelStatusByServer: StateFlow<Map<String, TunnelStatus>> = _tunnelStatusByServer.asStateFlow()
    private val _tunnelHealthByServer = MutableStateFlow<Map<String, TunnelHealth>>(emptyMap())
    val tunnelHealthByServer: StateFlow<Map<String, TunnelHealth>> = _tunnelHealthByServer.asStateFlow()

    private val _assistantMessages = MutableStateFlow(
        listOf(
            AssistantMessage(
                id = UUID.randomUUID().toString(),
                fromUser = false,
                text = "I can inspect your local server status, settings, and recent logs. Ask me about crashes, performance, or configuration."
            )
        )
    )
    val assistantMessages: StateFlow<List<AssistantMessage>> = _assistantMessages.asStateFlow()

    private val _aiChangePlan = MutableStateFlow<AiChangePlan?>(null)
    val aiChangePlan: StateFlow<AiChangePlan?> = _aiChangePlan.asStateFlow()

    private var lastAppliedAiPlan: AiRecommendationTransactionManager.Applied? = null

    private val _appSettings = MutableStateFlow(
        AppSettingsState(
            darkMode = uiPrefs.getBoolean("dark_mode", false),
            language = uiPrefs.getString("language", "English") ?: "English",
            startupScreen = uiPrefs.getString("startup_screen", "Dashboard") ?: "Dashboard",
            dataSaver = uiPrefs.getBoolean("data_saver", false),
            hapticFeedback = uiPrefs.getBoolean("haptic", true),
            confirmDestructiveActions = uiPrefs.getBoolean("confirm_destructive", true),
            automaticEngineUpdateChecks = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.AUTOMATIC_ENGINE_UPDATE_CHECKS, true)
        )
    )
    val appSettings: StateFlow<AppSettingsState> = _appSettings.asStateFlow()

    private val _notificationSettings = MutableStateFlow(
        NotificationSettingsState(
            serverOfflineAlerts = uiPrefs.getBoolean("notify_offline", true),
            crashAlerts = uiPrefs.getBoolean("notify_crash", true),
            backupReminders = uiPrefs.getBoolean("notify_backup", true),
            pluginUpdateAlerts = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.NOTIFY_PLUGINS, false),
            marketplaceAnnouncements = uiPrefs.getBoolean("notify_marketplace", false),
            sound = uiPrefs.getBoolean("notify_sound", true),
            vibration = uiPrefs.getBoolean("notify_vibration", true),
            quietHoursEnabled = uiPrefs.getBoolean(com.example.data.MineHostPreferenceKeys.QUIET_HOURS, false)
        )
    )
    val notificationSettings: StateFlow<NotificationSettingsState> = _notificationSettings.asStateFlow()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val activeOperationCount = AtomicInteger(0)
    private val _operationInProgress = MutableStateFlow(false)
    val operationInProgress: StateFlow<Boolean> = _operationInProgress.asStateFlow()

    private fun beginOperation() {
        activeOperationCount.incrementAndGet()
        _operationInProgress.value = true
    }

    private fun endOperation() {
        val remaining = activeOperationCount.decrementAndGet()
        if (remaining <= 0) {
            activeOperationCount.set(0)
            _operationInProgress.value = false
        }
    }

    private val _marketplaceCatalog = MutableStateFlow<MarketplaceCatalog?>(null)
    val marketplaceCatalog: StateFlow<MarketplaceCatalog?> = _marketplaceCatalog.asStateFlow()

    private val _marketplaceError = MutableStateFlow<String?>(null)
    val marketplaceError: StateFlow<String?> = _marketplaceError.asStateFlow()

    private val _membersByServer = MutableStateFlow<Map<String, List<ServerMember>>>(emptyMap())
    val membersByServer: StateFlow<Map<String, List<ServerMember>>> = _membersByServer.asStateFlow()
    private val _invitationsByServer = MutableStateFlow<Map<String, List<ServerInvitation>>>(emptyMap())
    val invitationsByServer: StateFlow<Map<String, List<ServerInvitation>>> = _invitationsByServer.asStateFlow()

    sealed class SaveServerResult {
        object Success : SaveServerResult()
        data class Failure(val message: String) : SaveServerResult()
    }

    private val _saveResult = MutableStateFlow<SaveServerResult?>(null)
    val saveResult: StateFlow<SaveServerResult?> = _saveResult.asStateFlow()

    fun clearSaveResult() {
        _saveResult.value = null
    }

    private val serverManager = mineHostApplication.serverManager
    private val runtimeLogListener: (String, String, String) -> Unit =
        { serverId, sessionId, line -> handleLog(serverId, sessionId, line) }
    private val runtimeStatusListener: (ServerRuntimeEvent) -> Unit =
        { event -> handleStatusEvent(event) }

    private fun profileFor(serverId: String?): ServerProfile? =
        serverId?.let { id -> profiles.value.firstOrNull { it.id == id } }

    private fun dataServiceFor(serverId: String): LocalServerDataService {
        val profile = profileFor(serverId) ?: throw IllegalArgumentException("Server profile not found: $serverId")
        val root = File(profile.serverDirectory).canonicalFile
        return LocalServerDataService(
            rootProvider = { root },
            contextProvider = { appContext },
            profileProvider = { profile },
        )
    }

    private fun requireMutableServer(serverId: String, action: String): ServerProfile? {
        val profile = profileFor(serverId)
        if (profile == null) {
            showMessage("Server profile not found")
            return null
        }
        if (serverManager.getStatus(serverId).isBlocking()) {
            showMessage("Stop ${profile.name} before $action")
            return null
        }
        return profile
    }

    private fun migratePreferences() {
        val editor = prefs.edit()
        var changed = false
        
        // Migration 1: engineVersion -> engine_version_id
        if (prefs.contains("engineVersion")) {
            val oldVal = prefs.getString("engineVersion", null)
            if (oldVal != null) editor.putString("engine_version_id", oldVal)
            editor.remove("engineVersion")
            changed = true
        }
        
        // Migration 2: serverPort -> global_server_port
        if (prefs.contains("serverPort")) {
            val oldVal = prefs.getInt("serverPort", 19132)
            editor.putInt("global_server_port", oldVal)
            editor.remove("serverPort")
            changed = true
        }
        
        // Migration 3: activeServerId -> selected_server_id
        if (prefs.contains("activeServerId")) {
            val oldVal = prefs.getString("activeServerId", null)
            if (oldVal != null) editor.putString("selected_server_id", oldVal)
            editor.remove("activeServerId")
            changed = true
        }
        
        if (changed) editor.apply()
    }

    init {
        serverManager.setSelectedProfileProvider {
            profiles.value.find { it.id == _selectedServerId.value }
        }
        serverManager.addLogListener(runtimeLogListener)
        serverManager.addStatusListener(runtimeStatusListener)
        viewModelScope.launch {
            com.example.server.ForegroundServiceFailureBus.events.collect { failure ->
                val prefix = failure.ownerId?.let { "Server $it: " }.orEmpty()
                showMessage(prefix + failure.message)
                failure.ownerId?.let { id -> handleLog(id, "foreground-service", "ERROR: ${failure.message}") }
            }
        }
        migratePreferences()
        
        viewModelScope.launch {
            serverManager.events.collect { event ->
                if (event is ServerManagerEvent.SessionStarted) {
                    _logsByServer.update { it + (event.serverId to emptyList()) }
                    if (_selectedServerId.value == event.serverId) {
                        _logs.value = emptyList()
                    }
                    serverManager.clearLogs(event.serverId)
                    
                    profileRepository.updateProfile(
                        event.serverId,
                        com.example.data.ServerProfileChanges(
                            lastStartedSessionId = event.sessionId,
                            lastStartedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            profileRepository.loadProfiles()
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                profiles.value.forEach { profile ->
                    try {
                        com.example.world.WorldRegenerationManager.recoverIncompleteTransactions(
                            java.io.File(profile.serverDirectory),
                            profile,
                            profileRepository
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            
            // If we have a selected ID but it's not in profiles, clear it
            val currentId = _selectedServerId.value
            if (currentId != null && profiles.value.none { it.id == currentId }) {
                _selectedServerId.value = profiles.value.firstOrNull()?.id
                prefs.edit().putString("selected_server_id", _selectedServerId.value).apply()
            }
            
            // If no ID selected but profiles exist, select the first one
            if (_selectedServerId.value == null && profiles.value.isNotEmpty()) {
                _selectedServerId.value = profiles.value.firstOrNull()?.id
                prefs.edit().putString("selected_server_id", _selectedServerId.value).apply()
            }

            val activeId = _selectedServerId.value
            val profile = profiles.value.find { it.id == activeId }
            if (profile != null) {
                _memoryMb.value = profile.memoryMb
                val template = TemplateRegistry.ALL_TEMPLATES.find { it.id == profile.engineId } ?: initialTemplate
                _activeTemplate.value = template
                val version = catalogRepository.findVersion(profile.engineVersionId)
                _activeEngineVersion.value = version
                
                if (version != null) {
                    serverManager.setTemplate(template, version)
                }
                
                // Migration check: Ensure local profile.properties is in sync with EngineBuild
                val service = dataServiceFor(profile.id)
                val localProfile = service.readLocalServerProfile(appContext)
                if (localProfile != null && localProfile.engineVersionId != profile.engineVersionId) {
                    val settings = service.readProperties()
                    service.saveLocalServerProfile(settings, profile.engineId, profile.engineVersionId, profile.bedrockVersion, profile.iconPath)
                }
                _onlineMode.value = profile.onlineMode
            } else {
                val version = catalogRepository.getDefaultVersion(initialTemplate.id)
                if (version != null) {
                    serverManager.setTemplate(initialTemplate, version)
                }
            }
            
            _status.value = serverManager.getCurrentStatus()
            updateIpAddress()
            serverManager.checkIntegrity()
            if (_appSettings.value.automaticEngineUpdateChecks) {
                EngineReleaseCheckWorker.schedule(appContext)
            }
            refreshAllLocalData()
            refreshMarketplaceCatalog()
            startRuntimeReconciliationLoop()
            startMetricsLoop()
            startAutomationLoop()
            startDashboardUiStateLoop()

            // PART 16: Startup check respects cooldown and settings
            if (appSettings.value.automaticEngineUpdateChecks) {
                updateRepository.checkAutomatically(notificationSettings.value, appSettings.value)
            }
        }
    }

    fun engineVersionFor(versionId: String) = catalogRepository.findVersion(versionId)

    fun refreshMarketplaceCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            marketplaceCatalogRepository.load()
                .onSuccess { catalog ->
                    _marketplaceCatalog.value = catalog
                    _marketplaceError.value = null
                }
                .onFailure { error ->
                    _marketplaceError.value = error.message ?: "Marketplace catalog could not be loaded"
                }
        }
    }

    fun installMarketplaceItem(
        item: MarketplaceItem,
        worldChoice: MarketplaceInstaller.WorldChoice = MarketplaceInstaller.WorldChoice.IMPORT_AS_ANOTHER,
        requestedWorldName: String? = null,
        serverId: String? = _selectedServerId.value,
    ) {
        val profile = profiles.value.firstOrNull { it.id == serverId }
        val catalog = _marketplaceCatalog.value
        if (profile == null || catalog == null) {
            showMessage("Select a server and load the verified marketplace catalog first")
            return
        }
        val serverRoot = File(profile.serverDirectory).canonicalFile
        viewModelScope.launch(Dispatchers.IO) {
            beginOperation()
            val result = marketplaceInstaller.install(
                item = item,
                catalog = catalog,
                request = MarketplaceInstaller.RequestContext(
                    serverUuid = profile.id,
                    engineId = profile.engineId,
                    engineVersionId = profile.engineVersionId,
                    minecraftVersion = profile.bedrockVersion,
                    javaVersion = catalogRepository.findVersion(profile.engineVersionId)?.runtimeJavaVersion ?: 17,
                    serverRoot = serverRoot,
                    createBackup = { reason ->
                        LocalServerDataService(
                            rootProvider = { serverRoot },
                            contextProvider = { appContext },
                            profileProvider = { profile },
                        ).createBackup(reason)
                    },
                    serverStopped = !serverManager.getStatus(profile.id).isBlocking(),
                ),
                worldChoice = worldChoice,
                requestedWorldName = requestedWorldName,
            )
            endOperation()
            withContext(Dispatchers.Main) {
                when (result) {
                    is MarketplaceInstaller.Result.Success -> {
                        showMessage(result.message)
                        addActivity("Marketplace item installed", item.name, ActivityType.SUCCESS, profile.id)
                        refreshAllLocalData(profile.id)
                        if (result.restartRequired && serverManager.getStatus(profile.id) == ServerStatus.ONLINE) {
                            serverManager.restartServer(profile.id)
                        }
                    }
                    is MarketplaceInstaller.Result.PendingVerification -> {
                        showMessage(result.message)
                        addActivity("Marketplace verification pending", item.name, ActivityType.WARNING, profile.id)
                        if (result.restartRequired && serverManager.getStatus(profile.id) == ServerStatus.ONLINE) {
                            serverManager.restartServer(profile.id)
                        }
                    }
                    is MarketplaceInstaller.Result.Failure -> {
                        showMessage(result.message)
                        addActivity("Marketplace install failed", result.message, ActivityType.ERROR, profile.id)
                    }
                }
            }
        }
    }

    fun refreshEngineVersions() {
        viewModelScope.launch {
            // PART 1: Manual refresh bypasses cooldown
            updateRepository.checkManually(notificationSettings.value, appSettings.value)
        }
    }

    fun verifyRelease(release: DetectedEngineRelease) {
        viewModelScope.launch {
            updateRepository.verifyRelease(release, profileRepository, serverManager)
        }
    }

    fun installEngineVersion(targetVersionId: String, serverId: String? = _selectedServerId.value) {
        val profile = profiles.value.firstOrNull { it.id == serverId }
        val target = catalogRepository.findVersion(targetVersionId)
        if (profile == null || target == null) { showMessage("Select a valid server and engine version"); return }
        viewModelScope.launch {
            beginOperation()
            val result = EngineVersionTransactionManager(appContext).install(
                profile = profile,
                target = target,
                profiles = profileRepository,
                serverManager = serverManager,
                onProgress = { line -> handleLog(profile.id, "version-update", line) },
            )
            endOperation()
            when (result) {
                is EngineVersionTransactionManager.Result.Success -> {
                    addActivity("Engine version verified", result.message, ActivityType.SUCCESS, profile.id)
                    showMessage(result.message)
                }
                is EngineVersionTransactionManager.Result.Failure -> {
                    addActivity("Engine update failed", result.message, ActivityType.ERROR, profile.id)
                    showMessage(result.message + if (result.rolledBack) " Previous engine restored." else " Rollback needs attention.")
                }
            }
        }
    }

    fun importManuallyVerifiedEngineJar(
        uri: Uri,
        targetVersionId: String,
        expectedSha256: String,
        serverId: String? = _selectedServerId.value,
    ) {
        val exactServerId = serverId ?: run { showMessage("Select the exact server first"); return }
        val profile = profiles.value.firstOrNull { it.id == exactServerId }
        val target = catalogRepository.findVersion(targetVersionId)
        if (profile == null || target == null || target.engineId != profile.engineId) {
            showMessage("Select a valid server and matching engine build")
            return
        }
        if (!expectedSha256.trim().matches(Regex("^[A-Fa-f0-9]{64}$"))) {
            showMessage("Enter the complete publisher SHA-256 before choosing the JAR")
            return
        }
        if (serverManager.getStatus(exactServerId).isBlocking()) {
            showMessage("Stop this exact server before importing or changing its engine JAR")
            return
        }

        viewModelScope.launch {
            beginOperation()
            try {
                val temporary = File(
                    appContext.cacheDir,
                    "manual-engine-${target.engineId}-${System.currentTimeMillis()}.jar.part"
                )
                val importResult = try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Unable to open the selected engine JAR")
                        Downloader.importManualJar(
                            context = appContext,
                            sourceFile = temporary,
                            version = target,
                            expectedSha256 = expectedSha256,
                            onProgress = { line -> handleLog(profile.id, "unknown", line) },
                        )
                    }
                } catch (error: Throwable) {
                    handleLog(profile.id, "unknown", "ERROR: ${error.message}")
                    false
                } finally {
                    temporary.delete()
                }

                val installResult = if (importResult) {
                    EngineVersionTransactionManager(appContext).install(
                        profile = profile,
                        target = target,
                        profiles = profileRepository,
                        serverManager = serverManager,
                        onProgress = { line -> handleLog(profile.id, "unknown", line) },
                    )
                } else null

                when (installResult) {
                    is EngineVersionTransactionManager.Result.Success -> {
                        addActivity("Manual engine verification completed", installResult.message, ActivityType.SUCCESS, profile.id)
                        showMessage(installResult.message)
                    }
                    is EngineVersionTransactionManager.Result.Failure -> {
                        addActivity("Engine update failed", installResult.message, ActivityType.ERROR, profile.id)
                        showMessage(
                            installResult.message +
                                if (installResult.rolledBack) " Previous engine restored." else " Rollback needs attention."
                        )
                    }
                    null -> showMessage("Manual engine verification failed. Check the console for the exact reason.")
                }
            } finally {
                endOperation()
            }
        }
    }

    fun selectServer(serverId: String) {
        if (_selectedServerId.value == serverId) return

        _selectedServerId.value = serverId
        prefs.edit().putString("selected_server_id", serverId).apply()
        
        val profile = profiles.value.find { it.id == serverId }
        if (profile != null) {
            _memoryMb.value = profile.memoryMb
            _onlineMode.value = profile.onlineMode
            val template = TemplateRegistry.ALL_TEMPLATES.find { it.id == profile.engineId } ?: initialTemplate
            _activeTemplate.value = template
            val version = catalogRepository.findVersion(profile.engineVersionId)
            _activeEngineVersion.value = version
            
            serverManager.switchProfile()
            if (version != null) {
                serverManager.setTemplate(template, version)
            }
            val runtime = _runtimeStates.value[serverId]
                ?: ActiveServerRuntimeState(serverId = serverId, status = serverManager.getStatus(serverId))
            _activeRuntime.value = runtime
            _status.value = runtime.status
            _logs.value = _logsByServer.value[serverId].orEmpty()
            _currentPath.value = filePathByServer[serverId].orEmpty()
            _selectedFilePath.value = null
            _selectedFileContent.value = null
            refreshAllLocalData(serverId)
            addActivity("Switched server", "Selected profile: ${profile.name}", ActivityType.INFO)
        }
    }

    fun createServer(
        draft: ServerCreationDraft,
        iconUri: Uri? = null,
        manualEngineJarUri: Uri? = null,
        manualEngineSha256: String? = null,
    ) {
        viewModelScope.launch {
            beginOperation()
            
            // Strict Validation (Part 1 & 8)
            val tempProfile = ServerProfile.createDraft(
                name = draft.name,
                engineId = draft.engineId,
                engineVersionId = draft.engineVersionId,
                bedrockVersion = draft.bedrockVersion,
                serverDirectory = "",
                worldSeed = draft.worldSeed,
                worldSeedMode = draft.worldSeedMode,
                worldSeedKnown = draft.worldSeedKnown,
                port = draft.port,
                memoryMb = draft.memoryMb,
                maxPlayers = draft.maxPlayers
            )
            val selectedVersion = catalogRepository.findVersion(draft.engineVersionId)
            val installability = selectedVersion?.installability() ?: EngineInstallability.UNAVAILABLE
            var trustedChecksum = selectedVersion?.let {
                Downloader.getTrustedChecksumForInstall(appContext, it)
            }

            if (selectedVersion != null &&
                installability == EngineInstallability.MANUAL_VERIFICATION_REQUIRED &&
                trustedChecksum == null
            ) {
                val checksum = manualEngineSha256
                    ?.trim()
                    ?.takeIf { it.matches(Regex("^[A-Fa-f0-9]{64}$")) }
                if (manualEngineJarUri == null || checksum == null) {
                    val message = "Select the exact engine JAR and enter its complete independently obtained SHA-256 before creating this profile."
                    endOperation()
                    showMessage(message)
                    _saveResult.value = SaveServerResult.Failure(message)
                    return@launch
                }

                val temporary = File(
                    appContext.cacheDir,
                    "create-engine-${selectedVersion.engineId}-${System.currentTimeMillis()}.jar.part",
                )
                val imported = try {
                    withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(manualEngineJarUri)?.use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Unable to open the selected engine JAR")
                        Downloader.importManualJar(
                            context = appContext,
                            sourceFile = temporary,
                            version = selectedVersion,
                            expectedSha256 = checksum,
                            onProgress = { line ->
                                if (line.startsWith("ERROR:")) showMessage(line.removePrefix("ERROR:").trim())
                            },
                        )
                    }
                } catch (error: Throwable) {
                    showMessage(error.message ?: "Manual engine verification failed")
                    false
                } finally {
                    temporary.delete()
                }
                if (!imported) {
                    val message = "Profile was not created because the selected engine JAR failed manual verification."
                    endOperation()
                    _saveResult.value = SaveServerResult.Failure(message)
                    return@launch
                }
                trustedChecksum = Downloader.getTrustedChecksumForInstall(appContext, selectedVersion)
            }

            val officialDynamicResolution =
                selectedVersion?.validationPolicy() == ArtifactValidationPolicy.OFFICIAL_RESOLVED_JAR

            val installabilityError = when {
                selectedVersion == null || selectedVersion.engineId != draft.engineId ->
                    "The selected engine build does not belong to this engine."
                installability == EngineInstallability.UNAVAILABLE ->
                    selectedVersion.unavailableReason ?: "The selected engine build is unavailable."
                trustedChecksum == null && !officialDynamicResolution ->
                    "Profile creation blocked: no trusted publisher checksum, official resolved-artifact policy, or exact manually verified JAR is available."
                else -> null
            }
            if (installabilityError != null) {
                endOperation()
                showMessage(installabilityError)
                _saveResult.value = SaveServerResult.Failure(installabilityError)
                return@launch
            }

            val validation = EngineCompatibilityValidator.validate(tempProfile, catalogRepository)
            if (!validation.success) {
                endOperation()
                showMessage(validation.message)
                _saveResult.value = SaveServerResult.Failure(validation.message)
                return@launch
            }

            val result = profileRepository.createProfile(draft)
            result.fold(
                onSuccess = { profile ->
                    // Copy icon if provided
                    var finalIconPath = profile.iconPath
                    if (iconUri != null) {
                        val serverDataService = LocalServerDataService(rootProvider = { File(profile.serverDirectory) })
                        val iconResult = serverDataService.saveServerIcon(appContext.contentResolver, iconUri)
                        if (iconResult.success) {
                            finalIconPath = iconResult.message
                            profileRepository.updateProfile(profile.id, ServerProfileChanges(iconPath = finalIconPath))
                        }
                    }

                    // 5. Write server.properties into the new directory
                    val serverDataService = LocalServerDataService(rootProvider = { File(profile.serverDirectory) })
                    val settings = ServerSettingsState(
                        serverName = profile.name,
                        levelName = profile.levelName,
                        port = profile.port,
                        memoryMb = profile.memoryMb,
                        maxPlayers = profile.maxPlayers
                    )
                    serverDataService.writeProperties(settings)
                    serverDataService.saveLocalServerProfile(settings, profile.engineId, profile.engineVersionId, profile.bedrockVersion, finalIconPath)
                    
                    selectServer(profile.id)
                    endOperation()
                    showMessage("Server '${profile.name}' created")
                    addActivity("Server created", profile.name, ActivityType.SUCCESS)
                    _saveResult.value = SaveServerResult.Success
                },
                onFailure = {
                    endOperation()
                    val msg = "Failed to create server: ${it.message}"
                    showMessage(msg)
                    _saveResult.value = SaveServerResult.Failure(msg)
                }
            )
        }
    }

    fun updateServer(serverId: String, changes: ServerProfileChanges, iconUri: Uri? = null) {
        viewModelScope.launch {
            beginOperation()
            
            val profile = profiles.value.find { it.id == serverId }
            if (serverManager.getStatus(serverId).isBlocking()) {
                endOperation()
                val message = "Stop this exact server before changing its profile or settings"
                showMessage(message)
                _saveResult.value = SaveServerResult.Failure(message)
                return@launch
            }
            if (profile == null) {
                endOperation()
                _saveResult.value = SaveServerResult.Failure("Profile not found")
                return@launch
            }
            
            // Strict Validation (Part 1 & 8)
            val tempProfile = profile.copy(
                name = changes.name ?: profile.name,
                engineId = changes.engineId ?: profile.engineId,
                engineVersionId = changes.engineVersionId ?: profile.engineVersionId,
                bedrockVersion = changes.bedrockVersion ?: profile.bedrockVersion,
                levelName = changes.levelName ?: profile.levelName,
                port = changes.port ?: profile.port,
                memoryMb = changes.memoryMb ?: profile.memoryMb,
                maxPlayers = changes.maxPlayers ?: profile.maxPlayers,
                iconPath = changes.iconPath ?: profile.iconPath,
                onlineMode = changes.onlineMode ?: profile.onlineMode,
                autoRestart = changes.autoRestart ?: profile.autoRestart,
                autoBackup = changes.autoBackup ?: profile.autoBackup,
            )
            val selectedVersion = catalogRepository.findVersion(tempProfile.engineVersionId)
            val installability = selectedVersion?.installability() ?: EngineInstallability.UNAVAILABLE
            val locallyVerified = selectedVersion?.let { version ->
                InstalledEngineVersionRepository.matches(
                    File(profile.serverDirectory),
                    version,
                    tempProfile.bedrockVersion,
                )
            } == true
            val installabilityError = when {
                selectedVersion == null || selectedVersion.engineId != tempProfile.engineId ->
                    "The selected engine build does not belong to this engine."
                installability == EngineInstallability.UNAVAILABLE ->
                    selectedVersion.unavailableReason ?: "The selected engine build is unavailable."
                installability == EngineInstallability.MANUAL_VERIFICATION_REQUIRED && !locallyVerified ->
                    "This engine build has no trusted publisher SHA-256 and is not manually verified for this server. Install it through Version Manager first."
                else -> null
            }
            if (installabilityError != null) {
                endOperation()
                showMessage(installabilityError)
                _saveResult.value = SaveServerResult.Failure(installabilityError)
                return@launch
            }

            val validation = EngineCompatibilityValidator.validate(tempProfile, catalogRepository)
            if (!validation.success) {
                endOperation()
                showMessage(validation.message)
                _saveResult.value = SaveServerResult.Failure(validation.message)
                return@launch
            }

            var updatedChanges = changes
            if (iconUri != null) {
                val serverDataService = LocalServerDataService(rootProvider = { File(profile.serverDirectory) })
                val iconResult = serverDataService.saveServerIcon(appContext.contentResolver, iconUri)
                if (iconResult.success) {
                    updatedChanges = changes.copy(iconPath = iconResult.message)
                }
            }

            val result = profileRepository.updateProfile(serverId, updatedChanges)
            result.fold(
                onSuccess = { updated ->
                    // If the updated server is the active one, refresh settings
                    if (_selectedServerId.value == serverId) {
                        val serverDataService = LocalServerDataService(rootProvider = { File(updated.serverDirectory) })
                        val currentSettings = serverDataService.readProperties()
                        val newSettings = currentSettings.copy(
                            serverName = updated.name,
                            levelName = updated.levelName,
                            port = updated.port,
                            memoryMb = updated.memoryMb,
                            maxPlayers = updated.maxPlayers,
                            onlineMode = updated.onlineMode,
                            autoRestart = updated.autoRestart,
                            autoBackup = updated.autoBackup,
                        )
                        serverDataService.writeProperties(newSettings)
                        serverDataService.saveLocalServerProfile(newSettings, updated.engineId, updated.engineVersionId, updated.bedrockVersion, updated.iconPath)
                        
                        _memoryMb.value = updated.memoryMb
                        _serverSettings.value = newSettings
                    }
                    endOperation()
                    showMessage("Server '${updated.name}' updated")
                    _saveResult.value = SaveServerResult.Success
                },
                onFailure = {
                    endOperation()
                    val msg = "Failed to update server: ${it.message}"
                    showMessage(msg)
                    _saveResult.value = SaveServerResult.Failure(msg)
                }
            )
        }
    }

    fun deleteServer(serverId: String, deleteFiles: Boolean = true) {
        viewModelScope.launch {
            if (serverManager.getStatus(serverId).isBlocking()) {
                showMessage("Stop this exact server before deleting it")
                return@launch
            }
            
            val profile = profiles.value.find { it.id == serverId } ?: return@launch
            val result = profileRepository.deleteProfile(serverId, deleteFiles)
            result.fold(
                onSuccess = {
                    if (_selectedServerId.value == serverId) {
                        _selectedServerId.value = profiles.value.firstOrNull()?.id
                        prefs.edit().putString("selected_server_id", _selectedServerId.value).apply()
                        
                        val nextProfile = profiles.value.find { it.id == _selectedServerId.value }
                        if (nextProfile != null) {
                            val template = TemplateRegistry.ALL_TEMPLATES.find { it.id == nextProfile.engineId } ?: initialTemplate
                            _activeTemplate.value = template
                            val version = catalogRepository.findVersion(nextProfile.engineVersionId)
                            _activeEngineVersion.value = version
                            serverManager.switchProfile()
                            if (version != null) {
                                serverManager.setTemplate(template, version)
                            }
                        } else {
                            serverManager.switchProfile()
                        }
                        refreshAllLocalData()
                    }
                    showMessage("Server '${profile.name}' deleted")
                },
                onFailure = {
                    showMessage("Failed to delete server: ${it.message}")
                }
            )
        }
    }

    fun startServer(serverId: String? = null) {
        viewModelScope.launch {
            val targetId = serverId ?: _selectedServerId.value
            if (targetId == null) {
                showMessage("No server profile selected.")
                return@launch
            }

            manualStopRequested.remove(targetId)
            autoRestartAttempts.remove(targetId)
            autoRestartJobs.remove(targetId)?.cancel()

            // Check availability
            val profile = profiles.value.find { it.id == targetId }
            val template = TemplateRegistry.getTemplate(profile?.engineId ?: "")
            if (template != null && !template.available) {
                showMessage("${template.name} is temporarily disabled: ${template.unavailableReason}")
                return@launch
            }

            val preparing = ActiveServerRuntimeState(
                serverId = targetId,
                status = ServerStatus.PREPARING,
                processAlive = false,
                port = profile?.port
            )
            _runtimeStates.update { it + (targetId to preparing) }
            if (_selectedServerId.value == targetId) {
                _activeRuntime.value = preparing
                _status.value = preparing.status
            }

            val result = serverManager.startServer(targetId, profile?.memoryMb ?: _memoryMb.value)
            if (result is StartServerResult.ValidationFailed) {
                showMessage(result.reason)
            } else if (result is StartServerResult.OperationBlocked) {
                showMessage(result.reason)
            }
            
            val snapshot = serverManager.getRuntimeSnapshot(targetId)
            _runtimeStates.update { it + (targetId to snapshot) }
            if (_selectedServerId.value == targetId) {
                _activeRuntime.value = snapshot
                _status.value = snapshot.status
            }

            handleStartResult(targetId, result)
        }
    }

    private fun handleStartResult(serverId: String, result: StartServerResult) {
        when (result) {
            is StartServerResult.Started -> {
                manualStopRequested.remove(serverId)
                val profile = profiles.value.find { it.id == serverId }
                addActivity("Server start requested", profile?.name ?: "Unknown", ActivityType.INFO, serverId)
            }
            is StartServerResult.OperationBlocked -> {
                showMessage(result.reason)
            }
            is StartServerResult.ValidationFailed -> {
                showMessage(result.reason)
                handleLog(serverId, "unknown", "ERROR: Validation failed: ${result.reason}")
            }
            is StartServerResult.Failed -> {
                showMessage(result.reason)
                handleLog(serverId, "unknown", "ERROR: Start failed: ${result.reason}")
            }
        }
    }

    fun stopServer(serverId: String? = null) {
        val targetId = serverId ?: _selectedServerId.value ?: return
        val runtime = _runtimeStates.value[targetId]
        if (runtime?.status == ServerStatus.STOPPED || runtime?.status == ServerStatus.STOPPING) return
        manualStopRequested.add(targetId)
        autoRestartJobs.remove(targetId)?.cancel()
        val profile = profiles.value.find { it.id == targetId }
        addActivity("Server stop requested", "Stopping ${profile?.name ?: "local server"} safely", ActivityType.WARNING, targetId)
        serverManager.stopServer(targetId)
    }

    fun restartServer(serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        if (serverManager.getStatus(targetId) != ServerStatus.ONLINE) {
            showMessage("The selected server must be running before it can restart")
            return
        }
        addActivity("Server restart requested", "Restarting with ${_memoryMb.value} MB", ActivityType.WARNING, targetId)
        serverManager.restartServer(targetId)
    }

    fun sendCommand(command: String, serverId: String? = _selectedServerId.value) {
        val clean = command.trim().removePrefix("/")
        if (clean.isBlank()) return
        val targetId = serverId ?: return
        val status = serverManager.getStatus(targetId)
        val isImportedPlayerClaim = clean.matches(
            Regex("^minehost\\s+claim-imported-player\\s+.+$", RegexOption.IGNORE_CASE),
        )
        val allowed = status == ServerStatus.ONLINE ||
            (status == ServerStatus.WORLD_PROVISIONALLY_LOADED && isImportedPlayerClaim)
        if (!allowed) {
            showMessage(
                if (status == ServerStatus.WORLD_PROVISIONALLY_LOADED) {
                    "Only the imported-player claim command is allowed during protected verification"
                } else {
                    "Start this exact server before sending commands"
                },
            )
            return
        }
        serverManager.sendCommand(targetId, clean)
        addActivity("Console command sent", "/$clean", ActivityType.INFO, targetId)
    }

    enum class LogClearChoice {
        UI_ONLY,
        UI_AND_FILE,
        EXPORT
    }

    fun clearAndExportLogs(choice: LogClearChoice, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        viewModelScope.launch {
            when (choice) {
                LogClearChoice.UI_ONLY -> {
                    serverManager.clearLogs(targetId)
                    _logsByServer.update { it + (targetId to emptyList()) }
                    if (_selectedServerId.value == targetId) _logs.value = emptyList()
                    showMessage("UI console logs cleared")
                }
                LogClearChoice.UI_AND_FILE -> {
                    serverManager.clearLogs(targetId)
                    _logsByServer.update { it + (targetId to emptyList()) }
                    if (_selectedServerId.value == targetId) _logs.value = emptyList()
                    withContext(Dispatchers.IO) {
                        val serverDir = serverManager.getServerDir(targetId)
                        listOf("logs.txt", "server.log").forEach { name ->
                            val file = File(serverDir, name)
                            if (file.exists()) file.delete()
                        }
                    }
                    showMessage("UI logs and real log files deleted")
                }
                LogClearChoice.EXPORT -> {
                    // This is handled by the UI triggering a file picker which then calls performLogExport
                }
            }
        }
    }

    fun performLogExport(uri: Uri, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val profile = profileFor(targetId) ?: return
        val logs = _logsByServer.value[targetId].orEmpty()
        
        viewModelScope.launch(Dispatchers.IO) {
            beginOperation()
            val manager = DiagnosticExportManager(appContext)
            val input = DiagnosticExportManager.Input(
                profile = profile,
                status = serverManager.getStatus(targetId),
                metrics = _metricsByServer.value[targetId] ?: RuntimeMetrics(),
                plugins = emptyList(), 
                logs = logs,
                crashes = _crashes.value.filter { it.serverId == targetId },
                device = _deviceHealth.value
            )
            val result = manager.export(appContext.contentResolver, uri, input)
            endOperation()
            withContext(Dispatchers.Main) {
                showMessage(result.message)
                if (result.success) addActivity("Logs exported", profile.name, ActivityType.INFO, targetId)
            }
        }
    }

    fun clearLogs(serverId: String? = _selectedServerId.value) {
        clearAndExportLogs(LogClearChoice.UI_ONLY, serverId)
    }

    fun updateIpAddress() {
        _ipAddress.value = NetworkUtils.getLocalIpAddress(appContext)
    }

    fun setMemoryMb(memory: Int) {
        val safe = memory.coerceIn(ServerProfile.MIN_MEMORY_MB, ServerProfile.MAX_MEMORY_MB)
        _memoryMb.value = safe
        _serverSettings.value = _serverSettings.value.copy(memoryMb = safe)
        prefs.edit().putInt("memory_mb", safe).apply()
    }

    fun setTemplate(template: ServerTemplate) {
        if (!template.available) {
            showMessage(template.unavailableReason ?: "This server engine is disabled")
            return
        }
        if (_status.value.isBlocking()) {
            showMessage("Stop the server before changing its engine")
            return
        }
        _activeTemplate.value = template
        prefs.edit().putString("template_id", template.id).apply()
        
        val version = catalogRepository.getDefaultVersion(template.id)
        _activeEngineVersion.value = version
        
        if (version != null) {
            serverManager.setTemplate(template, version)
        }
        
        refreshAllLocalData()
        addActivity("Server engine selected", template.name, ActivityType.SUCCESS)
    }

    fun setOnlineMode(enabled: Boolean, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (serverManager.getStatus(id).isBlocking()) {
            showMessage("Stop this exact server before changing online mode")
            return
        }
        viewModelScope.launch {
            val result = profileRepository.updateProfile(id, ServerProfileChanges(onlineMode = enabled))
            result.onSuccess {
                if (_selectedServerId.value == id) {
                    _onlineMode.value = enabled
                    _serverSettings.value = _serverSettings.value.copy(onlineMode = enabled)
                }
            }.onFailure { showMessage(it.message ?: "Unable to update online mode") }
        }
    }

    fun setEngineVersion(
        version: com.example.server.version.EngineVersion,
        serverId: String? = _selectedServerId.value,
    ) {
        val id = serverId ?: return showMessage("Select the exact server first")
        val profile = profiles.value.firstOrNull { it.id == id }
            ?: return showMessage("Server profile not found")
        if (version.engineId != profile.engineId) {
            showMessage("This version belongs to another server engine")
            return
        }
        installEngineVersion(version.id, id)
    }

    fun refreshAllLocalData(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val profile = profileFor(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val service = dataServiceFor(id)
            val settings = service.readProperties().copy(
                memoryMb = profile.memoryMb,
                onlineMode = profile.onlineMode,
                autoRestart = profile.autoRestart,
                autoBackup = profile.autoBackup,
            )
            val files = toFileInfo(service.list(""), "")
            val plugins = service.listPlugins()
            val backups = service.listBackups()
            val worlds = service.listWorlds(settings.levelName)
            withContext(Dispatchers.Main) {
                if (_selectedServerId.value != id) return@withContext
                _serverSettings.value = settings
                _currentFiles.value = files
                _plugins.value = plugins
                _backups.value = backups
                _worlds.value = worlds
                refreshHealthAndRecommendations()
            }
        }
    }

    fun navigateToFolder(relativePath: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        filePathByServer[id] = relativePath
        if (_selectedServerId.value == id) _currentPath.value = relativePath
        refreshFiles(id)
    }

    fun navigateUp(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val path = filePathByServer[id].orEmpty()
        if (path.isBlank()) return
        val parent = path.substringBeforeLast('/', "")
        filePathByServer[id] = parent
        if (_selectedServerId.value == id) _currentPath.value = parent
        refreshFiles(id)
    }

    fun refreshFiles(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val path = filePathByServer[id].orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            val files = toFileInfo(dataServiceFor(id).list(path), path)
            withContext(Dispatchers.Main) {
                if (_selectedServerId.value == id) _currentFiles.value = files
            }
        }
    }

    fun openTextFile(relativePath: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = dataServiceFor(id).readText(relativePath)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    editorPathByServer[id] = relativePath
                    if (_selectedServerId.value == id) {
                        _selectedFilePath.value = relativePath
                        _selectedFileContent.value = result.message
                    }
                } else showMessage(result.message)
            }
        }
    }

    fun closeTextFile(serverId: String? = _selectedServerId.value) {
        val id = serverId
        if (id != null) editorPathByServer.remove(id)
        if (id == null || _selectedServerId.value == id) {
            _selectedFilePath.value = null
            _selectedFileContent.value = null
        }
    }

    fun saveTextFile(content: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "editing files") == null) return
        val path = editorPathByServer[id] ?: return showMessage("The file editor no longer belongs to this server")
        val service = dataServiceFor(id)
        runOperation({ service.writeText(path, content) }) { refreshFiles(id) }
    }

    fun createFolder(name: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "creating files") == null) return
        val path = filePathByServer[id].orEmpty()
        val service = dataServiceFor(id)
        runOperation({ service.createFolder(path, name) }) { refreshFiles(id) }
    }

    fun createFile(name: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "creating files") == null) return
        val path = filePathByServer[id].orEmpty()
        val service = dataServiceFor(id)
        runOperation({ service.createFile(path, name) }) { refreshFiles(id) }
    }

    fun renamePath(relativePath: String, newName: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "renaming files") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.rename(relativePath, newName) }) { refreshFiles(id) }
    }

    fun deletePath(relativePath: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "deleting files") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.delete(relativePath) }) {
            if (_selectedServerId.value == id) closeTextFile(id)
            refreshFiles(id)
            refreshPlugins(id)
            refreshBackups(id)
            refreshWorlds(id)
        }
    }

    fun importFile(uri: Uri, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "importing files") == null) return
        val path = filePathByServer[id].orEmpty()
        val service = dataServiceFor(id)
        runOperation({ service.importDocument(appContext.contentResolver, uri, path) }) { refreshFiles(id) }
    }

    fun closeFileManager(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        filePathByServer[id] = ""
        if (_selectedServerId.value == id) _currentPath.value = ""
        closeTextFile(id)
        refreshFiles(id)
    }

    fun refreshPlugins(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val value = dataServiceFor(id).listPlugins()
            withContext(Dispatchers.Main) {
                if (_selectedServerId.value == id) _plugins.value = value
            }
        }
    }

    fun importPlugin(uri: Uri, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "installing plugins") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.importPlugin(appContext.contentResolver, uri) }) { refreshPlugins(id) }
    }

    fun togglePlugin(plugin: PluginEntry, enable: Boolean, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val profile = requireMutableServer(id, "changing plugin state") ?: return
        viewModelScope.launch(Dispatchers.IO) {
            beginOperation()
            val root = File(profile.serverDirectory).canonicalFile
            val result = PluginLifecycleManager(
                serverUuid = profile.id,
                engineId = profile.engineId,
                serverRoot = root,
                createBackup = { reason -> BackupManagerV2(root).create(backupMetadata(profile), reason) },
            ).setEnabled(plugin.name, enable)
            withContext(Dispatchers.Main) {
                endOperation()
                if (result.success) {
                    refreshPlugins(id)
                    addActivity("Plugin state changed", result.message, ActivityType.WARNING, id)
                }
                showMessage(result.message)
            }
        }
    }

    fun removePlugin(plugin: PluginEntry, removeConfiguration: Boolean, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val profile = requireMutableServer(id, "removing a plugin") ?: return
        viewModelScope.launch(Dispatchers.IO) {
            beginOperation()
            val root = File(profile.serverDirectory).canonicalFile
            val result = PluginLifecycleManager(
                serverUuid = id,
                engineId = profile.engineId,
                serverRoot = root,
                createBackup = { reason -> BackupManagerV2(root).create(backupMetadata(profile), reason) },
            ).remove(plugin.name, removeConfiguration)
            withContext(Dispatchers.Main) {
                endOperation()
                if (result.success) {
                    refreshPlugins(id)
                    addActivity("Plugin removed", result.message, ActivityType.WARNING, id)
                }
                showMessage(result.message)
            }
        }
    }

    fun refreshBackups(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val value = dataServiceFor(id).listBackups()
            withContext(Dispatchers.Main) {
                if (_selectedServerId.value == id) _backups.value = value
            }
        }
    }

    fun createBackup(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val profile = profileFor(id) ?: return showMessage("Server profile not found")
        val service = dataServiceFor(id)
        viewModelScope.launch {
            if (_operationInProgress.value) return@launch
            beginOperation()
            val status = serverManager.getStatus(id)
            if (status == ServerStatus.ONLINE) {
                val save = serverManager.requestConsistentSave(id)
                if (save.isFailure) {
                    endOperation()
                    showMessage("Backup cancelled: ${save.exceptionOrNull()?.message}")
                    return@launch
                }
            } else if (status.isBlocking()) {
                endOperation()
                showMessage("Wait for ${profile.name} to finish starting or stopping")
                return@launch
            }
            val result = withContext(Dispatchers.IO) { service.createBackup() }
            endOperation()
            if (result.success) {
                uiPrefs.edit().putLong("last_auto_backup_ms_$id", System.currentTimeMillis()).apply()
                refreshBackups(id)
                addActivity("Backup completed", result.message, ActivityType.SUCCESS, id)
                notificationManager.notifyBackupCompleted(result.message, _notificationSettings.value)
            }
            showMessage(result.message)
        }
    }

    fun restoreBackup(backup: BackupEntry, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val profile = requireMutableServer(id, "restoring a backup") ?: return
        val service = dataServiceFor(id)
        runOperation({ service.restoreBackup(backup.fileName) }) {
            refreshAllLocalData(id)
            addActivity("Backup restored", backup.fileName, ActivityType.WARNING, profile.id)
        }
    }

    fun deleteBackup(backup: BackupEntry, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val service = dataServiceFor(id)
        runOperation({ service.deleteBackup(backup.fileName) }) { refreshBackups(id) }
    }

    fun refreshWorlds(serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val profile = profileFor(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val service = dataServiceFor(id)
            val recovery = service.cleanAbandonedTransactions()
            if (!recovery.success) {
                withContext(Dispatchers.Main) { showMessage(recovery.message) }
                return@launch
            }
            val value = service.listWorlds(profile.levelName)
            withContext(Dispatchers.Main) {
                if (_selectedServerId.value == id) _worlds.value = value
            }
        }
    }

    fun importWorld(uri: Uri, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "importing a world") == null) return
        val service = dataServiceFor(id)
        viewModelScope.launch {
            beginOperation()
            try {
                val result = withContext(Dispatchers.IO) { 
                    service.prepareWorldImport(appContext.contentResolver, uri) { progress ->
                        _worldImportProgress.value = progress
                    }
                }
                result.onSuccess {
                    _pendingWorldImport.value = it
                }.onFailure {
                    showMessage(it.message ?: "Failed to prepare world import")
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Throwable) {
                showMessage(e.message ?: "Failed to prepare world import")
            } finally {
                _worldImportProgress.value = null
                endOperation()
            }
        }
    }

    fun importWorldFolder(treeUri: Uri, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "importing a world") == null) return
        val service = dataServiceFor(id)
        viewModelScope.launch {
            beginOperation()
            try {
                val result = withContext(Dispatchers.IO) { 
                    service.prepareWorldFolderImport(treeUri) { progress ->
                        _worldImportProgress.value = progress
                    }
                }
                result.onSuccess {
                    _pendingWorldImport.value = it
                }.onFailure {
                    showMessage(it.message ?: "Failed to prepare world folder import")
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Throwable) {
                showMessage(e.message ?: "Failed to prepare world folder import")
            } finally {
                _worldImportProgress.value = null
                endOperation()
            }
        }
    }

    fun confirmWorldImport(finalName: String, replaceActive: Boolean, selectedWorldName: String? = null) {
        val preview = _pendingWorldImport.value ?: return
        val id = preview.serverUuid
        if (requireMutableServer(id, "finalizing world import") == null) return
        val service = dataServiceFor(id)
        val mode = if (replaceActive) WorldManagerV2.ImportMode.REPLACE_ACTIVE else WorldManagerV2.ImportMode.IMPORT_AS_ANOTHER
        _pendingWorldImport.value = null
        _worldImportProgress.value = WorldManagerV2.ImportProgress("Starting", indeterminate = true)
        
        viewModelScope.launch {
            beginOperation()
            var opResult: OperationResult? = null
            try {
                opResult = withContext(Dispatchers.IO) {
                    service.finalizeWorldImport(preview, mode, finalName, selectedWorldName) { progress ->
                        _worldImportProgress.value = progress
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Throwable) {
                opResult = OperationResult(false, e.message ?: "World import failed")
            } finally {
                _worldImportProgress.value = null
                endOperation()
            }

            val finalResult = opResult ?: OperationResult(false, "World import failed")
            if (finalResult.success) {
                refreshAllLocalData(id)
            }
            showMessage(finalResult.message)
        }
    }

    fun cancelWorldImport() {
        val preview = _pendingWorldImport.value ?: return
        _pendingWorldImport.value = null
        viewModelScope.launch(Dispatchers.IO) {
            preview.transactionDir.deleteRecursively()
        }
    }

    fun exportWorld(world: WorldEntry, destination: Uri, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        val service = dataServiceFor(id)
        runOperation({ service.exportWorld(appContext.contentResolver, world.name, destination) }) { refreshWorlds(id) }
    }

    fun renameWorld(world: WorldEntry, newName: String, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "renaming a world") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.renameWorld(world.name, newName) }) { refreshWorlds(id) }
    }

    fun duplicateWorld(world: WorldEntry, newName: String? = null, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "duplicating a world") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.duplicateWorld(world.name, newName) }) { refreshWorlds(id) }
    }

    fun deleteWorld(world: WorldEntry, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "deleting a world") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.deleteWorld(world.name) }) { refreshWorlds(id) }
    }

    fun activateWorld(world: WorldEntry, serverId: String? = _selectedServerId.value) {
        val id = serverId ?: return
        if (requireMutableServer(id, "switching worlds") == null) return
        val service = dataServiceFor(id)
        runOperation({ service.setActiveWorld(world.name) }) {
            if (_selectedServerId.value == id) _serverSettings.value = _serverSettings.value.copy(levelName = world.name)
            refreshWorlds(id)
        }
    }

    fun regenerateExact(serverId: String? = _selectedServerId.value, worldName: String? = null) {
        val id = serverId ?: return
        val profile = profiles.value.find { it.id == id } ?: return
        regenerateWorld(id, worldName ?: profile.levelName, profile.worldSeed, profile.worldSeedMode)
    }

    fun regenerateNewSeed(serverId: String? = _selectedServerId.value, worldName: String? = null) {
        regenerateWorld(serverId, worldName, com.example.world.WorldSeedFactory.next(), com.example.server.engine.WorldSeedMode.RANDOM)
    }

    fun regenerateWorld(
        serverId: String? = _selectedServerId.value,
        worldName: String? = null,
        newSeed: Long = com.example.world.WorldSeedFactory.next(),
        newSeedMode: com.example.server.engine.WorldSeedMode = com.example.server.engine.WorldSeedMode.RANDOM
    ) {
        val id = serverId ?: return
        if (requireMutableServer(id, "regenerating world") == null) return
        viewModelScope.launch {
            _operationInProgress.value = true
            try {
                val profile = profileRepository.getProfile(id)
                if (profile == null) {
                    showMessage("Server profile not found")
                    return@launch
                }
                val targetWorld = worldName ?: profile.levelName
                val engineVer = _activeEngineVersion.value
                    ?: catalogRepository.findVersion(profile.engineVersionId)
                    ?: com.example.server.version.EngineVersion(
                        id = profile.engineVersionId,
                        engineId = profile.engineId,
                        versionName = profile.engineVersionId,
                        displayName = profile.engineId,
                        channel = com.example.server.version.ReleaseChannel.STABLE,
                        downloadUrl = "",
                        jarFileName = "${profile.engineId}.jar",
                        requiredJavaVersion = 17,
                        compatibilityLabel = "Standard",
                        recommended = true,
                        supportedBedrockVersions = listOf(profile.bedrockVersion),
                        recommendedBedrockVersion = profile.bedrockVersion
                    )
                val result = com.example.world.WorldRegenerationManager.regenerateWorld(
                    context = appContext,
                    serverId = id,
                    worldName = targetWorld,
                    newSeed = newSeed,
                    newSeedMode = newSeedMode,
                    profileRepository = profileRepository,
                    engineVersion = engineVer
                )
                if (result.isSuccess) {
                    refreshWorlds(id)
                    showMessage("World prepared for regeneration with seed $newSeed")
                } else {
                    showMessage("Failed to regenerate world: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                showMessage("Error regenerating world: ${e.message}")
            } finally {
                _operationInProgress.value = false
            }
        }
    }

    fun exportDiagnosticBundle(destination: Uri) {
        val profile = profiles.value.firstOrNull { it.id == _selectedServerId.value }
            ?: return showMessage("Select the exact server profile first")
        viewModelScope.launch(Dispatchers.IO) {
            beginOperation()
            val result = DiagnosticExportManager(appContext).export(
                appContext.contentResolver,
                destination,
                DiagnosticExportManager.Input(
                    profile = profile,
                    status = serverManager.getStatus(profile.id),
                    metrics = _metricsByServer.value[profile.id] ?: RuntimeMetrics(),
                    plugins = LocalServerDataService(
                        rootProvider = { File(profile.serverDirectory) },
                        contextProvider = { appContext },
                        profileProvider = { profile },
                    ).listPlugins(),
                    logs = _logsByServer.value[profile.id].orEmpty(),
                    crashes = _crashes.value,
                    device = _deviceHealth.value,
                ),
            )
            withContext(Dispatchers.Main) {
                endOperation()
                if (result.success) addActivity("Diagnostic bundle exported", result.message, ActivityType.SUCCESS, profile.id)
                showMessage(result.message)
            }
        }
    }

    fun updateServerSettingsDraft(settings: ServerSettingsState) {
        _serverSettings.value = settings
    }

    fun saveServerSettings(
        settings: ServerSettingsState = _serverSettings.value,
        serverId: String? = _selectedServerId.value,
    ) {
        val id = serverId ?: return showMessage("Select the exact server first")
        val profile = requireMutableServer(id, "applying settings") ?: return
        val safe = settings.copy(
            maxPlayers = settings.maxPlayers.coerceIn(1, 100),
            viewDistance = settings.viewDistance.coerceIn(2, 32),
            port = settings.port.coerceIn(1024, 65535),
            memoryMb = settings.memoryMb.coerceIn(ServerProfile.MIN_MEMORY_MB, ServerProfile.MAX_MEMORY_MB),
        )
        viewModelScope.launch {
            beginOperation()
            val update = profileRepository.updateProfile(
                id,
                ServerProfileChanges(
                    name = safe.serverName,
                    memoryMb = safe.memoryMb,
                    maxPlayers = safe.maxPlayers,
                    port = safe.port,
                    levelName = safe.levelName,
                    engineVersionId = profile.engineVersionId,
                    onlineMode = safe.onlineMode,
                    autoRestart = safe.autoRestart,
                    autoBackup = safe.autoBackup,
                ),
            )
            val result = update.fold(
                onSuccess = { updated ->
                    val service = dataServiceFor(id)
                    val write = withContext(Dispatchers.IO) { service.writeProperties(safe) }
                    if (write.success) {
                        withContext(Dispatchers.IO) {
                            service.saveLocalServerProfile(
                                safe,
                                updated.engineId,
                                updated.engineVersionId,
                                updated.bedrockVersion,
                                updated.iconPath,
                            )
                        }
                    }
                    write
                },
                onFailure = { OperationResult(false, it.message ?: "Unable to save server settings") },
            )
            endOperation()
            if (result.success) {
                if (_selectedServerId.value == id) {
                    _memoryMb.value = safe.memoryMb
                    _onlineMode.value = safe.onlineMode
                    _serverSettings.value = safe
                }
                refreshAllLocalData(id)
                addActivity("Server settings saved", "Changes will apply on the next start", ActivityType.SUCCESS, id)
            }
            showMessage(result.message)
        }
    }

    fun calculateOptimizationPlan(
        preset: OptimizationPreset,
        customValues: Map<String, String> = emptyMap(),
        serverId: String? = _selectedServerId.value,
    ) {
        val profile = profiles.value.firstOrNull { it.id == serverId }
        if (profile == null) { showMessage("Select a server first"); return }
        val root = File(profile.serverDirectory)
        _optimizationPlan.value = ServerOptimizationManager(root) { reason ->
            com.example.backup.BackupManagerV2(root).create(backupMetadata(profile), reason)
        }.propose(profile.id, profile.engineId, preset, customValues)
    }

    fun applyOptimizationPlan() {
        val plan = _optimizationPlan.value ?: run { showMessage("Review an optimization plan first"); return }
        if (serverManager.getStatus(plan.serverUuid).isBlocking()) {
            showMessage("Stop the exact server before applying optimization")
            return
        }
        val profile = profiles.value.firstOrNull { it.id == plan.serverUuid } ?: return
        runOperation({
            ServerOptimizationManager(File(profile.serverDirectory)) { reason ->
                com.example.backup.BackupManagerV2(File(profile.serverDirectory)).create(backupMetadata(profile), reason)
            }.apply(plan)
        }) {
            refreshAllLocalData(plan.serverUuid)
            addActivity("Optimization applied", plan.preset.name, ActivityType.SUCCESS, plan.serverUuid)
        }
    }

    fun applySafeOptimization(serverId: String? = _selectedServerId.value) {
        calculateOptimizationPlan(OptimizationPreset.SAFE, serverId = serverId)
        applyOptimizationPlan()
    }

    fun calculatePrepareServerPlan(
        selectedPlugins: List<MarketplacePlugin> = emptyList(),
        includeTunnelClient: Boolean = false,
        serverId: String? = _selectedServerId.value,
    ) {
        val profile = profiles.value.firstOrNull { it.id == serverId }
        val engine = profile?.let { catalogRepository.findVersion(it.engineVersionId) }
        if (profile == null || engine == null) { showMessage("Select a valid server build first"); return }
        viewModelScope.launch {
            val root = File(profile.serverDirectory)
            val installer = PluginInstaller(createBackup = { reason ->
                com.example.backup.BackupManagerV2(root).create(backupMetadata(profile), reason)
            })
            _preparePlan.value = PrepareServerCoordinator(appContext, installer)
                .calculatePlan(profile, engine, selectedPlugins, includeTunnelClient)
        }
    }

    fun clearPrepareServerPlan() {
        _preparePlan.value = null
    }

    fun prepareSelectedServer(
        selectedPlugins: List<MarketplacePlugin> = emptyList(),
        includeTunnelClient: Boolean = false,
        serverId: String? = _selectedServerId.value,
    ) {
        val profile = profiles.value.firstOrNull { it.id == serverId }
        val engine = profile?.let { catalogRepository.findVersion(it.engineVersionId) }
        if (profile == null || engine == null) { showMessage("Select a valid server build first"); return }
        viewModelScope.launch {
            beginOperation()
            val root = File(profile.serverDirectory)
            val installer = PluginInstaller(createBackup = { reason ->
                com.example.backup.BackupManagerV2(root).create(backupMetadata(profile), reason)
            })
            val result = PrepareServerCoordinator(appContext, installer).prepare(
                profile, engine, selectedPlugins, includeTunnelClient,
            ) { progress -> handleLog(profile.id, "prepare", progress) }
            endOperation()
            when (result) {
                is PrepareServerCoordinator.Result.Success -> {
                    refreshAllLocalData(profile.id)
                    showMessage("Server prepared: ${result.completed.joinToString()}")
                }
                is PrepareServerCoordinator.Result.PendingVerification -> {
                    refreshAllLocalData(profile.id)
                    showMessage("Preparation is incomplete: ${result.pending.joinToString("; ")}")
                }
                is PrepareServerCoordinator.Result.Failure -> showMessage(
                    "Preparation failed at ${result.stage}: ${result.message}",
                )
            }
        }
    }

    fun startTunnel(
        frpsHost: String,
        frpsPort: Int,
        remoteUdpPort: Int,
        token: String,
        tlsEnabled: Boolean = true,
        serverId: String? = _selectedServerId.value,
    ) {
        val profile = profiles.value.firstOrNull { it.id == serverId }
        if (profile == null) { showMessage("Select a server first"); return }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                serverManager.startTunnel(
                    FrpTunnelConfig(
                        serverId = profile.id,
                        frpsHost = frpsHost.trim(),
                        frpsPort = frpsPort,
                        remoteUdpPort = remoteUdpPort,
                        localUdpPort = profile.port,
                        authToken = token,
                        tlsEnabled = tlsEnabled,
                    ),
                )
            }
            refreshTunnelState(profile.id)
            result.fold(
                onSuccess = { showMessage("Tunnel ready: $it") },
                onFailure = { showMessage(it.message ?: "Tunnel failed") },
            )
        }
    }

    fun stopTunnel(serverId: String? = _selectedServerId.value) {
        serverId ?: return
        serverManager.stopTunnel(serverId)
        refreshTunnelState(serverId)
        showMessage("Tunnel stopped")
    }

    fun installTunnelBinary(uri: Uri, expectedSha256: String, serverId: String? = _selectedServerId.value) {
        val exactId = serverId ?: run { showMessage("Select an exact server first"); return }
        if (!expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            showMessage("Enter the publisher SHA-256 for the FRPC ARM64 binary")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val temporary = File(appContext.cacheDir, "frpc-$exactId-${System.currentTimeMillis()}.part")
            val result = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Unable to open selected FRPC binary")
                serverManager.installTunnelBinary(exactId, temporary, expectedSha256).getOrThrow()
            }
            temporary.delete()
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { showMessage("Verified FRPC ARM64 binary installed for this server runtime") },
                    onFailure = { showMessage(it.message ?: "FRPC binary installation failed") },
                )
            }
        }
    }

    private fun refreshTunnelState(serverId: String) {
        val statusValue = serverManager.getTunnelStatus(serverId)
        val healthValue = serverManager.getTunnelHealth(serverId)
        _tunnelStatusByServer.value = _tunnelStatusByServer.value + (serverId to statusValue)
        _tunnelHealthByServer.value = _tunnelHealthByServer.value + (serverId to healthValue)
        if (_selectedServerId.value == serverId) {
            _tunnelStatus.value = statusValue
            _tunnelHealth.value = healthValue
        }
    }

    private fun backupMetadata(profile: ServerProfile): com.example.backup.BackupManagerV2.Metadata =
        com.example.backup.BackupManagerV2.Metadata(
            serverUuid = profile.id,
            engineId = profile.engineId,
            engineVersionId = profile.engineVersionId,
            javaVersion = catalogRepository.findVersion(profile.engineVersionId)?.runtimeJavaVersion ?: 17,
            levelName = profile.levelName,
        )

    private fun safePlayerCommandName(name: String): String? {
        val cleaned = name
            .replace(Regex("[\r\n\u0000]"), "")
            .trim()
        if (cleaned.isBlank() || cleaned.length > 64) return null
        if (!cleaned.all { it.isLetterOrDigit() || it in setOf('_', ' ', '.', '-') }) return null
        return if (cleaned.any(Char::isWhitespace)) "\"$cleaned\"" else cleaned
    }

    fun messagePlayer(name: String, message: String, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val safeName = safePlayerCommandName(name) ?: return
        val safeMessage = message.replace(Regex("[\r\n\u0000]"), " ").trim().take(256)
        if (safeMessage.isBlank()) return
        sendCommand("tell $safeName $safeMessage", targetId)
    }

    fun kickPlayer(name: String, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val safeName = safePlayerCommandName(name) ?: return
        sendCommand("kick $safeName", targetId)
    }

    fun banPlayer(name: String, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val safeName = safePlayerCommandName(name) ?: return
        sendCommand("ban $safeName", targetId)
        refreshPlayerAccessListsAfterCommand(targetId)
    }

    fun unbanPlayer(name: String, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val safeName = safePlayerCommandName(name) ?: return
        sendCommand("pardon $safeName", targetId)
        refreshPlayerAccessListsAfterCommand(targetId)
    }

    fun whitelistPlayer(name: String, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val safeName = safePlayerCommandName(name) ?: return
        sendCommand("whitelist add $safeName", targetId)
        refreshPlayerAccessListsAfterCommand(targetId)
    }

    fun removeWhitelistedPlayer(name: String, serverId: String? = _selectedServerId.value) {
        val targetId = serverId ?: return
        val safeName = safePlayerCommandName(name) ?: return
        sendCommand("whitelist remove $safeName", targetId)
        refreshPlayerAccessListsAfterCommand(targetId)
    }

    fun refreshPlayerAccessLists(serverId: String, pollCount: Int = 0) {
        val generation = playerAccessListRefreshGeneration
            .getOrPut(serverId) { AtomicInteger(0) }
            .incrementAndGet()
        val current = _playerAccessListsByServer.value[serverId] ?: ServerAccessListState()
        _playerAccessListsByServer.update { it + (serverId to current.copy(loading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            val serverDir = serverManager.getServerDir(serverId)
            val initial = runCatching { ServerAccessListRepository.read(serverDir) }
                .getOrElse { error ->
                    ServerAccessListState(error = error.message ?: "Unable to read server access lists")
                }
            var result = initial
            if (pollCount > 0) {
                var previous = initial
                repeat(pollCount) {
                    delay(1000)
                    if (playerAccessListRefreshGeneration[serverId]?.get() != generation) return@launch
                    val next = ServerAccessListRepository.read(serverDir)
                    if (next != previous) {
                        result = next
                        return@repeat
                    }
                    previous = next
                }
            }
            if (playerAccessListRefreshGeneration[serverId]?.get() != generation) return@launch
            withContext(Dispatchers.Main) {
                _playerAccessListsByServer.update { it + (serverId to result.copy(loading = false)) }
                com.example.server.players.PlayerStateProvider.updateAccessLists(
                    serverId,
                    result.whitelist,
                    result.bannedPlayers
                )
            }
        }
    }

    private fun refreshPlayerAccessListsAfterCommand(serverId: String) {
        refreshPlayerAccessLists(serverId, pollCount = 5)
    }

    fun askAssistant(prompt: String, serverId: String? = _selectedServerId.value) {
        val clean = prompt.trim()
        if (clean.isBlank()) return

        _assistantMessages.value = _assistantMessages.value +
            AssistantMessage(UUID.randomUUID().toString(), true, clean)

        viewModelScope.launch {
            val selected = profiles.value.firstOrNull { it.id == serverId }
            val selectedId = selected?.id
            val metrics = selectedId?.let { _metricsByServer.value[it] } ?: RuntimeMetrics()
            val context = AiServerContext(
                serverUuid = selected?.id,
                engineId = selected?.engineId ?: _activeTemplate.value.id,
                engineVersion = selected?.engineVersionId ?: _activeEngineVersion.value?.id,
                minecraftVersion = selected?.bedrockVersion,
                javaVersion = _activeEngineVersion.value?.runtimeJavaVersion,
                allocatedMemoryMb = selected?.memoryMb ?: _memoryMb.value,
                deviceMemoryMb = null,
                processMemoryMb = metrics.ramBytes?.div(1024L * 1024L),
                cpuPercent = metrics.cpuPercent?.toDouble(),
                tps = metrics.tps?.toDouble(),
                playerCount = selectedId?.let { _playersByServer.value[it].orEmpty().size } ?: 0,
                installedPlugins = selected?.let { dataServiceFor(it.id).listPlugins().map(PluginEntry::name) }.orEmpty(),
                currentSettings = mapOf(
                    "gamemode" to _serverSettings.value.gameMode,
                    "difficulty" to _serverSettings.value.difficulty,
                    "view-distance" to _serverSettings.value.viewDistance.toString(),
                    "max-players" to _serverSettings.value.maxPlayers.toString(),
                    "online-mode" to _serverSettings.value.onlineMode.toString(),
                    "level-name" to _serverSettings.value.levelName,
                ),
                recentLogs = selectedId?.let { _logsByServer.value[it].orEmpty().takeLast(200) }.orEmpty(),
                recentCrashes = _crashes.value.takeLast(20).map { "${it.title}: ${it.details}" },
            )
            val result = withContext(Dispatchers.IO) { aiAssistantService.ask(clean, context) }
            val reply = result.fold(
                onSuccess = { it.text },
                onFailure = { error ->
                    val local = buildAssistantReply(clean)
                    "AI service unavailable: ${error.message ?: "unknown error"}\n\nLocal read-only analysis:\n$local"
                },
            )
            _assistantMessages.value = _assistantMessages.value +
                AssistantMessage(UUID.randomUUID().toString(), false, reply)
        }
    }

    fun requestAiChangePlan(prompt: String, serverId: String? = _selectedServerId.value) {
        val selected = profiles.value.firstOrNull { it.id == serverId }
        if (selected == null) { showMessage("Select an exact server first"); return }
        val metrics = _metricsByServer.value[selected.id] ?: RuntimeMetrics()
        val context = AiServerContext(
            serverUuid = selected.id,
            engineId = selected.engineId,
            engineVersion = selected.engineVersionId,
            minecraftVersion = selected.bedrockVersion,
            javaVersion = catalogRepository.findVersion(selected.engineVersionId)?.runtimeJavaVersion,
            allocatedMemoryMb = selected.memoryMb,
            deviceMemoryMb = null,
            processMemoryMb = metrics.ramBytes?.div(1024L * 1024L),
            cpuPercent = metrics.cpuPercent?.toDouble(),
            tps = metrics.tps?.toDouble(),
            playerCount = _playersByServer.value[selected.id].orEmpty().size,
            installedPlugins = LocalServerDataService(
                rootProvider = { File(selected.serverDirectory) },
                contextProvider = { appContext },
                profileProvider = { selected },
            ).listPlugins().map { it.fileName },
            currentSettings = mapOf(
                "gamemode" to _serverSettings.value.gameMode,
                "difficulty" to _serverSettings.value.difficulty,
                "view-distance" to _serverSettings.value.viewDistance.toString(),
                "max-players" to _serverSettings.value.maxPlayers.toString(),
                "online-mode" to _serverSettings.value.onlineMode.toString(),
                "level-name" to _serverSettings.value.levelName,
            ),
            recentLogs = _logsByServer.value[selected.id].orEmpty().takeLast(200),
            recentCrashes = _crashes.value.takeLast(20).map { "${it.title}: ${it.details}" },
        )
        viewModelScope.launch {
            beginOperation()
            val result = withContext(Dispatchers.IO) { aiAssistantService.askForPlan(prompt, context) }
            endOperation()
            result.fold(
                onSuccess = { plan ->
                    _aiChangePlan.value = plan
                    showMessage("AI plan ready for review. Nothing has been changed.")
                },
                onFailure = { showMessage(it.message ?: "AI plan generation failed") },
            )
        }
    }

    fun applyAiChangePlan(confirmationPlanId: String) {
        val plan = _aiChangePlan.value ?: run { showMessage("Generate and review a plan first"); return }
        val profile = profiles.value.firstOrNull { it.id == plan.serverUuid }
            ?: run { showMessage("The plan's server profile no longer exists"); return }
        if (serverManager.getStatus(profile.id).isBlocking()) {
            showMessage("Stop the exact server before applying AI changes")
            return
        }
        val metrics = _metricsByServer.value[profile.id] ?: RuntimeMetrics()
        viewModelScope.launch(Dispatchers.IO) {
            beginOperation()
            val manager = AiRecommendationTransactionManager(profile, backupMetadata(profile))
            val result = manager.apply(
                plan = plan,
                confirmationPlanId = confirmationPlanId,
                baseline = AiRecommendationTransactionManager.Baseline(
                    cpuPercent = metrics.cpuPercent?.toDouble(),
                    processMemoryMb = metrics.ramBytes?.div(1024L * 1024L),
                    tps = metrics.tps?.toDouble(),
                ),
            )
            endOperation()
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { applied ->
                        lastAppliedAiPlan = applied
                        _aiChangePlan.value = null
                        refreshAllLocalData(profile.id)
                        showMessage(applied.message + if (applied.restartRequired) "; restart required" else "")
                    },
                    onFailure = { showMessage(it.message ?: "AI plan apply failed; use the verified backup to roll back") },
                )
            }
        }
    }

    fun compareLastAiPlan(serverId: String? = _selectedServerId.value): JSONObject? {
        val applied = lastAppliedAiPlan ?: return null
        val profileId = serverId ?: return null
        if (profileId != applied.serverUuid) return null
        val metrics = _metricsByServer.value[profileId] ?: return null
        val profile = profiles.value.firstOrNull { it.id == profileId } ?: return null
        return AiRecommendationTransactionManager(profile, backupMetadata(profile)).compare(
            applied,
            AiRecommendationTransactionManager.Baseline(
                cpuPercent = metrics.cpuPercent?.toDouble(),
                processMemoryMb = metrics.ramBytes?.div(1024L * 1024L),
                tps = metrics.tps?.toDouble(),
            ),
        )
    }

    fun saveUserAiApiKey(apiKey: String): OperationResult =
        aiCredentialStore.saveUserGeminiKey(apiKey).fold(
            onSuccess = { OperationResult(true, "AI API key stored securely on this device") },
            onFailure = { OperationResult(false, it.message ?: "Unable to store AI API key") },
        )

    fun clearUserAiApiKey(): OperationResult {
        aiCredentialStore.clearUserGeminiKey()
        return OperationResult(true, "AI API key removed")
    }

    fun createGoogleLoginIntent(): Result<android.content.Intent> = authManager.createGoogleLoginIntent()

    fun signOut() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { authManager.signOut() }
            result.exceptionOrNull()?.let { showMessage(it.message ?: "Sign out failed") }
        }
    }

    fun isRemoteAccessEnabled(serverId: String): Boolean =
        uiPrefs.getBoolean("remote_access_$serverId", false)

    fun setRemoteAccessEnabled(serverId: String, enabled: Boolean) {
        viewModelScope.launch {
            val profile = profiles.value.firstOrNull { it.id == serverId }
            if (profile == null) {
                showMessage("Server profile not found")
                return@launch
            }
            if (!enabled) {
                uiPrefs.edit().putBoolean("remote_access_$serverId", false).apply()
                withContext(Dispatchers.IO) {
                    friendAccessRepository.registerOrUpdateServer(
                        serverUuid = profile.id,
                        displayName = profile.name,
                        engineId = profile.engineId,
                        engineVersion = profile.engineVersionId,
                        deviceId = null,
                        remoteAccessEnabled = false,
                    )
                }
                RemoteAccessForegroundService.refresh(appContext).fold(
                    onSuccess = { showMessage("Remote access disabled") },
                    onFailure = { showMessage(it.message ?: "Unable to update remote-access service") },
                )
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                friendAccessRepository.registerDevice(android.os.Build.MODEL).fold(
                    onSuccess = { deviceId ->
                        friendAccessRepository.registerOrUpdateServer(
                            serverUuid = profile.id,
                            displayName = profile.name,
                            engineId = profile.engineId,
                            engineVersion = profile.engineVersionId,
                            deviceId = deviceId,
                            remoteAccessEnabled = true,
                        )
                    },
                    onFailure = { Result.failure(it) },
                )
            }
            result.fold(
                onSuccess = {
                    uiPrefs.edit().putBoolean("remote_access_$serverId", true).apply()
                    RemoteAccessForegroundService.refresh(appContext).fold(
                        onSuccess = { showMessage("Remote access enabled for ${profile.name}") },
                        onFailure = { error ->
                            uiPrefs.edit().putBoolean("remote_access_$serverId", false).apply()
                            viewModelScope.launch(Dispatchers.IO) {
                                friendAccessRepository.registerOrUpdateServer(
                                    serverUuid = profile.id,
                                    displayName = profile.name,
                                    engineId = profile.engineId,
                                    engineVersion = profile.engineVersionId,
                                    deviceId = null,
                                    remoteAccessEnabled = false,
                                )
                            }
                            showMessage(error.message ?: "Android blocked the remote-access foreground service")
                        },
                    )
                },
                onFailure = { showMessage(it.message ?: "Remote access setup failed") },
            )
        }
    }

    fun inviteFriend(serverId: String, email: String, role: ServerRole) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { friendAccessRepository.inviteFriend(serverId, email, role) }
            result.fold(
                onSuccess = { showMessage("Invitation created for ${it.invitedEmail}"); refreshFriendAccess(serverId) },
                onFailure = { showMessage(it.message ?: "Invitation failed") },
            )
        }
    }

    fun refreshFriendAccess(serverId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val members = friendAccessRepository.listMembers(serverId)
            val invitations = friendAccessRepository.listInvitations(serverId)
            withContext(Dispatchers.Main) {
                members.onSuccess { _membersByServer.value = _membersByServer.value + (serverId to it) }
                    .onFailure { showMessage(it.message ?: "Unable to load server members") }
                invitations.onSuccess { _invitationsByServer.value = _invitationsByServer.value + (serverId to it) }
                    .onFailure { showMessage(it.message ?: "Unable to load invitations") }
            }
        }
    }

    fun changeFriendRole(serverId: String, userId: String, role: ServerRole) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = friendAccessRepository.changeRole(serverId, userId, role)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { showMessage("Member role updated"); refreshFriendAccess(serverId) },
                    onFailure = { showMessage(it.message ?: "Role update failed") },
                )
            }
        }
    }

    fun removeFriendAccess(serverId: String, userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = friendAccessRepository.removeAccess(serverId, userId)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { showMessage("Member access removed"); refreshFriendAccess(serverId) },
                    onFailure = { showMessage(it.message ?: "Unable to remove access") },
                )
            }
        }
    }

    fun revokeInvitation(serverId: String, invitationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = friendAccessRepository.revokeInvitation(invitationId)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { showMessage("Invitation revoked"); refreshFriendAccess(serverId) },
                    onFailure = { showMessage(it.message ?: "Unable to revoke invitation") },
                )
            }
        }
    }

    fun clearAssistantChat() {
        _assistantMessages.value = emptyList()
    }

    fun updateAppSettings(value: AppSettingsState) {
        _appSettings.value = value
        uiPrefs.edit()
            .putBoolean("dark_mode", value.darkMode)
            .putString("language", value.language)
            .putString("startup_screen", value.startupScreen)
            .putBoolean("data_saver", value.dataSaver)
            .putBoolean("haptic", value.hapticFeedback)
            .putBoolean("confirm_destructive", value.confirmDestructiveActions)
            .putBoolean(com.example.data.MineHostPreferenceKeys.AUTOMATIC_ENGINE_UPDATE_CHECKS, value.automaticEngineUpdateChecks)
            .apply()
        
        if (value.automaticEngineUpdateChecks) {
            EngineReleaseCheckWorker.schedule(appContext)
        } else {
            EngineReleaseCheckWorker.cancel(appContext)
        }
    }

    fun updateNotificationSettings(value: NotificationSettingsState) {
        _notificationSettings.value = value
        uiPrefs.edit()
            .putBoolean("notify_offline", value.serverOfflineAlerts)
            .putBoolean("notify_crash", value.crashAlerts)
            .putBoolean("notify_backup", value.backupReminders)
            .putBoolean(com.example.data.MineHostPreferenceKeys.NOTIFY_PLUGINS, value.pluginUpdateAlerts)
            .putBoolean("notify_marketplace", value.marketplaceAnnouncements)
            .putBoolean("notify_sound", value.sound)
            .putBoolean("notify_vibration", value.vibration)
            .putBoolean(com.example.data.MineHostPreferenceKeys.QUIET_HOURS, value.quietHoursEnabled)
            .apply()
    }

    fun consumeOperationMessage() {
        _operationMessage.value = null
    }

    private fun handleStatusEvent(event: com.example.data.ServerRuntimeEvent) {
        val snapshot = serverManager.getRuntimeSnapshot(event.serverId)
        // Ignore stale events from older sessions if current session is active
        if (snapshot.sessionId != null && event.sessionId != snapshot.sessionId && snapshot.status.isBlocking()) {
            return
        }

        val previous = _runtimeStates.value[event.serverId]
        val authoritativeStatus = snapshot.status
        val updated = snapshot.copy(
            serverId = event.serverId,
            sessionId = if (authoritativeStatus.canStart()) null else snapshot.sessionId ?: event.sessionId,
            status = authoritativeStatus,
            terminationCause = event.terminationCause.takeUnless { it == TerminationCause.NONE } ?: snapshot.terminationCause,
            processAlive = snapshot.processAlive
        )
        _runtimeStates.update { it + (event.serverId to updated) }

        if (_selectedServerId.value == event.serverId) {
            _activeRuntime.value = updated
            handleStatus(
                serverId = event.serverId,
                previous = previous?.status ?: ServerStatus.STOPPED,
                newStatus = authoritativeStatus,
                sessionId = event.sessionId,
            )
        } else {
            handleBackgroundRuntimeStatus(
                event.serverId,
                previous?.status ?: ServerStatus.STOPPED,
                authoritativeStatus
            )
        }
    }

    private fun handleBackgroundRuntimeStatus(serverId: String, previous: ServerStatus, status: ServerStatus) {
        val name = profiles.value.find { it.id == serverId }?.name ?: "Server"
        when (status) {
            ServerStatus.ONLINE -> {
                autoRestartJobs.remove(serverId)?.cancel()
                autoRestartAttempts.remove(serverId)
                manualStopRequested.remove(serverId)
                notificationManager.cancelServerProblem()
                addActivity("Server online", name, ActivityType.SUCCESS, serverId)
                serverManager.requestPlayerRefresh(serverId)
                viewModelScope.launch { delay(8_000); verifyPendingPlugins(serverId) }
            }
            ServerStatus.CRASHED, ServerStatus.FAILED -> {
                addActivity("Server problem detected", "$name: ${status.name}", ActivityType.ERROR, serverId)
                val serverLogs = _logsByServer.value[serverId].orEmpty()
                addCrash("Server $name ${status.name.lowercase()}", serverLogs.takeLast(20).joinToString("\n"), CrashSeverity.CRITICAL)
                if (previous == ServerStatus.ONLINE) scheduleAutoRestart(serverId)
            }
            ServerStatus.STOPPED -> if (previous != ServerStatus.STOPPED) {
                addActivity("Server stopped", "$name is offline", ActivityType.INFO, serverId)
                manualStopRequested.remove(serverId)
            }
            else -> Unit
        }
    }

    private fun handleStatus(
        serverId: String,
        previous: ServerStatus,
        newStatus: ServerStatus,
        sessionId: String?,
    ) {
        _status.value = newStatus
        
        // Reset metrics on stop or start
        if (newStatus.canStart() || newStatus == ServerStatus.STARTING || newStatus == ServerStatus.PREPARING) {
            _players.value = emptyList()
            _metrics.value = RuntimeMetrics()
        }

        val profile = profiles.value.find { it.id == serverId }
        val engineName = profile?.name ?: _activeTemplate.value.name

        when (newStatus) {
            ServerStatus.ONLINE -> {
                autoRestartJobs.remove(serverId)?.cancel()
                autoRestartAttempts.remove(serverId)
                manualStopRequested.remove(serverId)
                notificationManager.cancelServerProblem()
                addActivity("Server online", engineName, ActivityType.SUCCESS, serverId)
                serverManager.requestPlayerRefresh(serverId)
                viewModelScope.launch { delay(2_500); verifyPendingPlugins(serverId) }
            }
            ServerStatus.STOPPED -> {
                if (previous != ServerStatus.STOPPED) {
                    addActivity("Server stopped", "Server $engineName is offline", ActivityType.INFO, serverId)
                    if (previous == ServerStatus.ONLINE && serverId !in manualStopRequested) {
                        notificationManager.notifyServerProblem(
                            title = "MineHost server went offline",
                            message = "Server $engineName stopped unexpectedly.",
                            settings = _notificationSettings.value,
                            critical = false
                        )
                    }
                }
                manualStopRequested.remove(serverId)
            }
            ServerStatus.CRASHED, ServerStatus.FAILED -> {
                addActivity("Server problem detected", "$engineName: ${newStatus.name}", ActivityType.ERROR, serverId)
                addCrash("Server $engineName ${newStatus.name.lowercase()}", _logs.value.takeLast(20).joinToString("\n"), CrashSeverity.CRITICAL)
                notificationManager.notifyServerProblem(
                    title = "MineHost server $engineName ${newStatus.name.lowercase()}",
                    message = _logs.value.takeLast(3).joinToString(" ").take(500).ifBlank { "Open Crash Analysis for details." },
                    settings = _notificationSettings.value,
                    critical = true
                )
                
                val currentSnapshot = serverManager.getRuntimeSnapshot(serverId)
                val wasOnlineBefore = previous == ServerStatus.ONLINE || currentSnapshot.startedAtMillis != null
                if (wasOnlineBefore && serverId !in manualStopRequested) {
                    scheduleAutoRestart(serverId)
                } else {
                    handleLog(
                        serverId,
                        sessionId ?: "unknown",
                        "[Main] Server process stopped with status ${newStatus.name}. Auto-restart skipped.",
                    )
                }
            }
            else -> Unit
        }
        refreshHealthAndRecommendations()
    }

    private fun handleLog(serverId: String, sessionId: String, newLog: String) {
        val runtime = _runtimeStates.value[serverId]
        if (runtime?.sessionId != null && sessionId != "unknown" && sessionId != runtime.sessionId) return

        viewModelScope.launch {
            _logsByServer.update { current ->
                current + (serverId to (current[serverId].orEmpty() + newLog).takeLast(1000))
            }
            syncPlayersFromStateProvider(serverId)
            parseTps(newLog, serverId)
            parseCrash(newLog, serverId)
            if (_selectedServerId.value == serverId) {
                _logs.value = _logsByServer.value[serverId].orEmpty()
                _players.value = _playersByServer.value[serverId].orEmpty()
                _metrics.value = _metricsByServer.value[serverId] ?: _metrics.value
            }
        }
    }

    private fun syncPlayersFromStateProvider(serverId: String) {
        val lock = playerRosterLocks.getOrPut(serverId) { Any() }
        synchronized(lock) {
            val playerState = com.example.server.players.PlayerStateProvider.getState(serverId).value
            val authoritativeNames = playerState.onlineNames
            val previous = _playersByServer.value[serverId].orEmpty()
            val previousByKey = previous.associateBy { it.name.lowercase() }
            val nextKeys = authoritativeNames.mapTo(linkedSetOf()) { it.lowercase() }
            val previousKeys = previousByKey.keys
            if (nextKeys == previousKeys) return

            val now = System.currentTimeMillis()
            val next = authoritativeNames.map { name ->
                previousByKey[name.lowercase()]?.copy(name = name, lastSeenAt = now, online = true)
                    ?: PlayerSession(name = name, joinedAt = now, lastSeenAt = now, online = true)
            }.sortedBy { it.name.lowercase() }
            _playersByServer.update { current -> current + (serverId to next) }
            if (_selectedServerId.value == serverId) _players.value = next

            authoritativeNames.filter { it.lowercase() !in previousKeys }.forEach { name ->
                addActivity("$name joined", "Player detected from the real server console", ActivityType.SUCCESS, serverId)
            }
            previous.filter { it.name.lowercase() !in nextKeys }.forEach { player ->
                addActivity("${player.name} left", "Player removed by the real server roster", ActivityType.INFO, serverId)
            }
        }
    }

    private fun clearPlayerRoster(serverId: String) {
        val lock = playerRosterLocks.getOrPut(serverId) { Any() }
        synchronized(lock) {
            val previous = _playersByServer.value[serverId].orEmpty()
            if (previous.isEmpty()) return
            _playersByServer.update { current -> current + (serverId to emptyList()) }
            if (_selectedServerId.value == serverId) _players.value = emptyList()
        }
    }

    private fun parseTps(line: String, serverId: String) {
        val match = Regex("(?:TPS|tps)[^0-9]*(\\d{1,2}(?:\\.\\d+)?)").find(line)
        val value = match?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return
        val safeTps = value.coerceIn(0f, 20f)
        _metricsByServer.update { current ->
            current + (serverId to (current[serverId] ?: RuntimeMetrics()).copy(tps = safeTps.toDouble()))
        }
        if (_selectedServerId.value == serverId) _metrics.value = _metricsByServer.value[serverId] ?: _metrics.value
    }

    private fun parseCrash(line: String, serverId: String) {
        val lower = line.lowercase()
        val severity = when {
            "fatal" in lower || "outofmemory" in lower || "crash" in lower -> CrashSeverity.CRITICAL
            "exception" in lower || "[error]" in lower || " error" in lower -> CrashSeverity.ERROR
            "[warn]" in lower || " warning" in lower -> CrashSeverity.WARNING
            else -> null
        } ?: return
        if (severity != CrashSeverity.WARNING) addCrash("Runtime ${severity.name.lowercase()}", line, severity, serverId)
    }

    private fun addCrash(title: String, details: String, severity: CrashSeverity, serverId: String? = null) {
        val entry = CrashEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), title, details.take(4000), severity, serverId)
        _crashes.value = (listOf(entry) + _crashes.value).take(50)
        refreshHealthAndRecommendations()
    }

    private fun addActivity(title: String, description: String, type: ActivityType, serverId: String? = null) {
        val entry = ActivityEvent(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            title = title,
            description = description,
            type = type,
            serverId = serverId ?: _activeRuntime.value.serverId
        )
        _activities.value = (listOf(entry) + _activities.value).take(100)
    }

    private fun verifyPendingPlugins(serverId: String) {
        val profile = profiles.value.firstOrNull { it.id == serverId } ?: return
        val root = serverManager.getServerDir(serverId)
        val metadata = com.example.backup.BackupManagerV2.Metadata(
            serverUuid = profile.id,
            engineId = profile.engineId,
            engineVersionId = profile.engineVersionId,
            javaVersion = catalogRepository.findVersion(profile.engineVersionId)?.runtimeJavaVersion ?: 17,
            levelName = profile.levelName,
        )
        val installer = PluginInstaller(createBackup = { reason ->
            com.example.backup.BackupManagerV2(root).create(metadata, reason)
        })
        installer.verifyAllPending(root, profile.engineId, _logsByServer.value[serverId].orEmpty())
            .forEach { result ->
                val ledger = com.example.marketplace.MarketplaceInstallationRegistry(root)
                when (result) {
                    is PluginInstallResult.Success -> {
                        runCatching {
                            ledger.markByInstalledFile(
                                result.installedFile,
                                com.example.marketplace.MarketplaceInstallationRegistry.Status.INSTALLED,
                                result.message,
                            )
                        }
                        addActivity("Plugin verified", result.message, ActivityType.SUCCESS, serverId)
                    }
                    is PluginInstallResult.Failure -> addActivity("Plugin load failed", result.message, ActivityType.ERROR, serverId)
                    is PluginInstallResult.PendingVerification -> addActivity("Plugin verification pending", result.message, ActivityType.WARNING, serverId)
                }
            }
    }

    fun rollbackPendingPlugin(pluginName: String, serverId: String? = _selectedServerId.value) {
        val profile = profiles.value.firstOrNull { it.id == serverId } ?: return
        val root = serverManager.getServerDir(profile.id)
        val metadata = com.example.backup.BackupManagerV2.Metadata(
            profile.id, profile.engineId, profile.engineVersionId,
            catalogRepository.findVersion(profile.engineVersionId)?.runtimeJavaVersion ?: 17,
            profile.levelName,
        )
        val result = PluginInstaller(createBackup = { reason ->
            com.example.backup.BackupManagerV2(root).create(metadata, reason)
        }).rollbackPending(root, pluginName)
        showMessage(when (result) {
            is PluginInstallResult.Success -> result.message
            is PluginInstallResult.PendingVerification -> result.message
            is PluginInstallResult.Failure -> result.message
        })
    }

    private fun refreshHealthAndRecommendations() {
        val warnings = mutableListOf<String>()
        val status = _status.value
        val recentErrors = _crashes.value.count { System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000L && it.severity != CrashSeverity.WARNING }
        val metrics = _metrics.value
        val tps = metrics.tps
        
        // Critical alerts (Only if actively bad)
        if (status == ServerStatus.CRASHED) {
            warnings += "CRITICAL: Server crashed and is offline."
        } else if (status == ServerStatus.FAILED) {
            warnings += "Startup failed. Inspect Java/Engine logs."
        }
        
        // Current status warnings
        if (status == ServerStatus.ONLINE) {
            if (tps != null && tps < 16.0) warnings += "Server lag detected (TPS < 16)."
            if (metrics.cpuPercent != null && metrics.cpuPercent > 95) warnings += "Maximum CPU load detected."
        }
        
        // Historical and Configuration warnings
        if (recentErrors > 0 && status != ServerStatus.CRASHED) {
            warnings += "Recovered from $recentErrors crash(es) today."
        }
        if (_serverSettings.value.viewDistance > 12) {
             warnings += "View distance is set high (${_serverSettings.value.viewDistance})."
        }
        if (_memoryMb.value < 768) warnings += "Allocated memory is low (< 768MB)."
        val device = _deviceHealth.value
        if (device?.overheating == true) warnings += "Device overheating detected (${device.temperatureCelsius}°C)."
        if (device?.lowStorage == true) warnings += "Low storage: less than 512 MB is available."
        if (device?.batteryPercent != null && device.batteryPercent < 15 && !device.charging) {
            warnings += "Battery is below 15% and the device is not charging."
        }
        val latestBackupAt = _backups.value.maxOfOrNull { it.createdAt }
        if (_backups.value.isNotEmpty() && latestBackupAt != null && System.currentTimeMillis() - latestBackupAt > 7L * 24 * 60 * 60 * 1000) {
            warnings += "Latest backup is more than 7 days old."
        }
        if (recentErrors >= 3) warnings += "Repeated-crash pattern detected in the last 24 hours."
        
        val score = when (status) {
            ServerStatus.ONLINE -> 95
            ServerStatus.STARTING, ServerStatus.PREPARING, ServerStatus.DOWNLOADING,
            ServerStatus.PROCESS_STARTED, ServerStatus.NETWORK_READY, ServerStatus.ENGINE_READY -> 85
            ServerStatus.WORLD_PROVISIONALLY_LOADED -> 55
            ServerStatus.WORLD_VERIFIED -> 92
            ServerStatus.STOPPING -> 80
            ServerStatus.STOPPED -> 75
            ServerStatus.FAILED, ServerStatus.CRASHED, ServerStatus.WORLD_LOAD_FAILED, ServerStatus.PORT_MISMATCH, ServerStatus.PROTOCOL_MISMATCH -> 30
        } - (warnings.size * 5)
        
        _health.value = HealthSnapshot(status, score.coerceIn(0, 100), warnings.distinct())
        
        _recommendations.value = buildList {
            if (_serverSettings.value.viewDistance > 8) add("Reduce view distance to 8 for smoother mobile hosting.")
            if (_memoryMb.value < 1024) add("Consider allocating at least 1024 MB for plugin-heavy worlds.")
            if (_plugins.value.count { it.enabled } > 8) add("Review enabled plugins and remove anything unused.")
            if (_backups.value.isEmpty()) add("Create a backup before changing engines or server settings.")
            if (latestBackupAt != null && System.currentTimeMillis() - latestBackupAt > 7L * 24 * 60 * 60 * 1000) add("Create a fresh backup; the latest verified backup is older than 7 days.")
            if (device?.lowStorage == true) add("Free device storage before downloading engines, worlds, or backups.")
            if (device?.overheating == true) add("Stop extra servers or reduce load until the device temperature falls.")
            if (recentErrors > 0 && status == ServerStatus.ONLINE) add("Review Crash Analysis to prevent future downtime.")
            if (isEmpty()) add("No urgent optimization is currently suggested.")
        }
    }

    private fun startRuntimeReconciliationLoop() {
        viewModelScope.launch {
            while (isActive) {
                val ids = profiles.value.map { it.id }
                val snapshots = serverManager.getAllRuntimeSnapshots(ids)
                val previous = _runtimeStates.value

                snapshots.forEach { (serverId, snapshot) ->
                    val old = previous[serverId]
                    if (old != null && old.status != snapshot.status) {
                        if (_selectedServerId.value == serverId) {
                            handleStatus(
                                serverId = serverId,
                                previous = old.status,
                                newStatus = snapshot.status,
                                sessionId = snapshot.sessionId,
                            )
                        } else {
                            handleBackgroundRuntimeStatus(serverId, old.status, snapshot.status)
                        }
                    }
                }

                _runtimeStates.value = snapshots
                val selected = _selectedServerId.value
                val selectedSnapshot = selected?.let(snapshots::get)
                    ?: ActiveServerRuntimeState(serverId = selected, status = ServerStatus.STOPPED)
                _activeRuntime.value = selectedSnapshot
                _status.value = selectedSnapshot.status
                val tunnelStatuses = ids.associateWith(serverManager::getTunnelStatus)
                val tunnelHealth = ids.associateWith(serverManager::getTunnelHealth)
                _tunnelStatusByServer.value = tunnelStatuses
                _tunnelHealthByServer.value = tunnelHealth
                if (selected != null) {
                    _tunnelStatus.value = tunnelStatuses[selected] ?: TunnelStatus.STOPPED
                    _tunnelHealth.value = tunnelHealth[selected] ?: TunnelHealth()
                }
                delay(750)
            }
        }
    }

    private fun startMetricsLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            var playerRefreshTick = 0
            while (isActive) {
                playerRefreshTick++
                val now = System.currentTimeMillis()
                val snapshots = LinkedHashMap<String, RuntimeMetrics>()
                profiles.value.forEach { profile ->
                    val serverId = profile.id
                    val status = serverManager.getStatus(serverId)
                    val pid = serverManager.getProcessId(serverId)
                    val processAlive = serverManager.getRuntimeSnapshot(serverId).processAlive
                    val reader = metricsReaders.getOrPut(serverId) { ProcessMetricsReader(appContext) }
                    val (cpu, ram) = if (processAlive && pid != null) reader.read(pid) else reader.read(null)
                    val root = serverManager.getServerDir(serverId)
                    val service = LocalServerDataService(rootProvider = { root })
                    val old = _metricsByServer.value[serverId] ?: RuntimeMetrics()
                    val isOnline = status == ServerStatus.ONLINE
                    if (isOnline) {
                        if (playerRefreshTick % 10 == 0) serverManager.requestPlayerRefresh(serverId)
                        syncPlayersFromStateProvider(serverId)
                    } else {
                        clearPlayerRoster(serverId)
                    }
                    snapshots[serverId] = old.copy(
                        cpuPercent = if (processAlive) cpu?.toDouble() else null,
                        ramBytes = if (processAlive) ram else null,
                        serverDataBytes = service.directorySize(root),
                        availableStorageBytes = root.usableSpace,
                        playersOnline = if (isOnline) _playersByServer.value[serverId].orEmpty().size else null,
                        uptimeMillis = if (isOnline) serverManager.getStartedAtMillis(serverId)?.let { (now - it).coerceAtLeast(0) } else null,
                        processId = pid,
                        isPlayerTrackingAvailable = isOnline && serverManager.isPlayerTrackingAvailable(serverId),
                    )
                }
                metricsReaders.keys.filter { id -> profiles.value.none { it.id == id } }.forEach(metricsReaders::remove)
                playerRosterLocks.keys.filter { id -> profiles.value.none { it.id == id } }.forEach(playerRosterLocks::remove)
                val selectedDeviceHealth = _selectedServerId.value
                    ?.let { selectedId -> profiles.value.firstOrNull { it.id == selectedId } }
                    ?.let { selectedProfile -> runCatching { deviceHealthMonitor.snapshot(File(selectedProfile.serverDirectory)) }.getOrNull() }
                withContext(Dispatchers.Main) {
                    _deviceHealth.value = selectedDeviceHealth
                    _metricsByServer.value = snapshots
                    val selected = _selectedServerId.value
                    _metrics.value = selected?.let(snapshots::get) ?: RuntimeMetrics()
                    _players.value = selected?.let { _playersByServer.value[it] }.orEmpty()
                    refreshHealthAndRecommendations()
                }
                val anyRunning = snapshots.values.any { it.processId != null }
                delay(if (anyRunning) 1_500 else 3_000)
            }
        }
    }

    private fun startDashboardUiStateLoop() {
        viewModelScope.launch {
            while (isActive) {
                val metrics = _metrics.value
                val runtime = _activeRuntime.value
                val status = runtime.status
                val currentProfiles = profiles.value
                val selectedId = _selectedServerId.value
                val hasProfile = selectedId != null
                val isOnline = status == ServerStatus.ONLINE
                val activeOpId = serverManager.activeOperationServerId

                _dashboardUiState.value = DashboardUiState(
                    hasServerProfile = hasProfile,
                    serverStatus = status,
                    isProcessAlive = runtime.processAlive,
                    activeOperationServerId = activeOpId,
                    isServerReady = isOnline,
                    totalServerCount = currentProfiles.size,
                    onlineServerCount = serverManager.runningServerCount(),
                    activePlayers = if (isOnline) metrics.playersOnline else null,
                    ramUsedBytes = if (runtime.serverId != null) metrics.ramBytes else null,
                    cpuUsagePercent = if (runtime.serverId != null) metrics.cpuPercent else null,
                    tps = if (isOnline) metrics.tps else null,
                    pingMs = null,
                    healthSummary = _health.value,
                    uptimeMillis = if (isOnline) metrics.uptimeMillis else null,
                    latestMetricTimestamp = System.currentTimeMillis()
                )
                delay(1000)
            }
        }
    }

    private fun scheduleAutoRestart(serverId: String) {
        if (profileFor(serverId)?.autoRestart != true) return
        if (serverId in manualStopRequested || autoRestartJobs[serverId]?.isActive == true) return
        val attempts = autoRestartAttempts[serverId] ?: 0
        if (attempts >= 3) return
        val attempt = attempts + 1
        autoRestartAttempts[serverId] = attempt
        autoRestartJobs[serverId] = viewModelScope.launch {
            val name = profiles.value.find { it.id == serverId }?.name ?: "Server"
            addActivity("Auto-restart scheduled", "$name: attempt $attempt of 3 in 5 seconds", ActivityType.WARNING, serverId)
            delay(5_000)
            val currentStatus = serverManager.getStatus(serverId)
            if (currentStatus == ServerStatus.FAILED || currentStatus == ServerStatus.CRASHED) {
                addActivity("Auto-restart attempt", "Starting $name again", ActivityType.INFO, serverId)
                startServer(serverId)
            }
        }.also { job ->
            job.invokeOnCompletion { autoRestartJobs.remove(serverId, job) }
        }
    }

    private fun startAutomationLoop() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15 * 60 * 1000L)
                val now = System.currentTimeMillis()
                profiles.value.filter { it.autoBackup }.forEach { profile ->
                    val key = "last_auto_backup_ms_${profile.id}"
                    val last = uiPrefs.getLong(key, now)
                    if (now - last < 6 * 60 * 60 * 1000L) return@forEach
                    val root = File(profile.serverDirectory).canonicalFile
                    if (!root.exists()) return@forEach
                    val status = serverManager.getStatus(profile.id)
                    if (status == ServerStatus.ONLINE) {
                        val saved = serverManager.requestConsistentSave(profile.id)
                        if (saved.isFailure) {
                            withContext(Dispatchers.Main) {
                                addActivity("Automatic backup skipped", saved.exceptionOrNull()?.message ?: "Save was not confirmed", ActivityType.ERROR, profile.id)
                            }
                            return@forEach
                        }
                    } else if (status.isBlocking()) {
                        return@forEach
                    }
                    val result = com.example.backup.BackupManagerV2(root).create(
                        backupMetadata(profile),
                        "scheduled-backup",
                    )
                    if (result.success) {
                        uiPrefs.edit().putLong(key, now).apply()
                        withContext(Dispatchers.Main) {
                            if (_selectedServerId.value == profile.id) refreshBackups()
                            addActivity("Automatic backup completed", result.message, ActivityType.SUCCESS, profile.id)
                            notificationManager.notifyBackupCompleted(result.message, _notificationSettings.value)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            addActivity("Automatic backup failed", result.message, ActivityType.ERROR, profile.id)
                        }
                    }
                }
            }
        }
    }

    private fun buildAssistantReply(prompt: String): String {
        val lower = prompt.lowercase()
        val errors = _crashes.value.take(3)
        val metrics = _metrics.value
        return when {
            "crash" in lower || "error" in lower -> {
                if (errors.isEmpty()) "I did not find a recorded crash in this app session. Check the Console after the next failure so I can summarize the exact error lines."
                else "I found ${errors.size} recent error event(s). The newest is: ${errors.first().details.take(240)}"
            }
            "lag" in lower || "tps" in lower || "performance" in lower -> {
                val tpsText = metrics.tps?.let { "Current parsed TPS is ${"%.1f".format(it)}. " } ?: "The server has not reported a TPS value yet. "
                tpsText + _recommendations.value.joinToString(" ")
            }
            "player" in lower -> "I currently see ${_players.value.size} player(s) based on real join and leave messages in the console."
            "memory" in lower || "ram" in lower -> {
                val used = metrics.ramBytes?.let(::formatBytes) ?: "unavailable"
                "The server is configured for ${_memoryMb.value} MB. Current process memory is $used."
            }
            "log" in lower -> {
                val important = _logs.value.filter { it.contains("warn", true) || it.contains("error", true) || it.contains("exception", true) }.takeLast(5)
                if (important.isEmpty()) "I did not find warning or error lines in the current console buffer."
                else "Important recent log lines:\n" + important.joinToString("\n")
            }
            "optimiz" in lower -> "The safe optimization profile creates a backup, limits view distance to 8, keeps max players at 50 or lower, and enables automatic restart. Open Auto Optimization to review and apply it."
            else -> "I can answer using the local server's real status, logs, settings, players, backups, and metrics. Try asking: “Why is my server lagging?”, “Explain recent errors”, or “How much memory is used?”"
        }
    }

    private fun toFileInfo(files: List<File>, path: String): List<FileInfo> = files.map { file ->
        val relative = if (path.isBlank()) file.name else "$path/${file.name}"
        FileInfo(file.name, if (file.isDirectory) 0L else file.length(), file.isDirectory, file.lastModified(), relative)
    }

    private fun runOperation(block: suspend () -> OperationResult, afterSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            beginOperation()
            val result = try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: Throwable) {
                OperationResult(false, error.message ?: "Operation failed")
            } finally {
                endOperation()
            }
            if (result.success) afterSuccess()
            showMessage(result.message)
        }
    }

    fun showMessage(message: String) {
        _operationMessage.value = message
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    override fun onCleared() {
        serverManager.removeLogListener(runtimeLogListener)
        serverManager.removeStatusListener(runtimeStatusListener)
        super.onCleared()
    }

}
