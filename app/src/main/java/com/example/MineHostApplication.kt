package com.example

import android.app.Application
import com.example.server.updates.EngineUpdateRepository
import com.example.server.version.EngineVersionCatalogRepository
import com.example.auth.SupabaseAuthManager
import com.example.ai.AiAssistantService
import com.example.ai.AiCredentialStore
import com.example.auth.SupabaseRestClient
import com.example.friends.FriendAccessRepository
import com.example.marketplace.MarketplaceCatalogRepository
import com.example.marketplace.MarketplaceInstaller
import com.example.data.ServerProfileRepository
import com.example.server.ServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class MineHostApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Application-scoped singleton repositories
    val catalogRepository: EngineVersionCatalogRepository by lazy {
        EngineVersionCatalogRepository(this)
    }

    val updateRepository: EngineUpdateRepository by lazy {
        EngineUpdateRepository(this, OkHttpClient(), catalogRepository)
    }

    val profileRepository: ServerProfileRepository by lazy {
        ServerProfileRepository(this, catalogRepository)
    }

    /** Application-scoped owner of real server processes. UI selection cannot destroy it. */
    val serverManager: ServerManager by lazy {
        ServerManager(this, catalogRepository).apply {
            setProfileRepositoryProvider { profileRepository.profiles.value }
        }
    }

    val authManager: SupabaseAuthManager by lazy { SupabaseAuthManager(this) }

    val supabaseRestClient: SupabaseRestClient by lazy { SupabaseRestClient(authManager) }

    val friendAccessRepository: FriendAccessRepository by lazy {
        FriendAccessRepository(this, authManager, supabaseRestClient)
    }

    val marketplaceCatalogRepository: MarketplaceCatalogRepository by lazy {
        MarketplaceCatalogRepository(this)
    }

    val marketplaceInstaller: MarketplaceInstaller by lazy {
        MarketplaceInstaller(this)
    }

    val batchPreparationManager: com.example.server.BatchPreparationManager by lazy {
        com.example.server.BatchPreparationManager(this, catalogRepository, applicationScope)
    }

    val aiCredentialStore: AiCredentialStore by lazy { AiCredentialStore(this) }

    val aiAssistantService: AiAssistantService by lazy {
        AiAssistantService(aiCredentialStore, authManager)
    }

    override fun onCreate() {
        super.onCreate()
        migratePreferences()
        com.example.notifications.MineHostNotificationManager.createChannels(this)
        // Initialize long-lived repositories without coupling them to an Activity.
        serverManager
        authManager
        applicationScope.launch { profileRepository.loadProfiles() }
    }

    private fun migratePreferences() {
        val uiPrefs = getSharedPreferences("minehost_ui_prefs", MODE_PRIVATE)
        
        // Only run migration once if not recorded
        if (uiPrefs.getBoolean("migration_complete_v1", false)) {
            return
        }

        val editor = uiPrefs.edit()
        var changed = false

        val oldPrefs = getSharedPreferences("minehost_settings", MODE_PRIVATE)
        val hasOldPrefs = oldPrefs.all.isNotEmpty()

        // Helper to migrate a boolean
        fun migrateBoolean(oldKey: String, newKey: String, prefsToReadFrom: android.content.SharedPreferences) {
            if (!uiPrefs.contains(newKey) && prefsToReadFrom.contains(oldKey)) {
                val oldVal = prefsToReadFrom.getBoolean(oldKey, false)
                editor.putBoolean(newKey, oldVal)
                editor.remove(oldKey) // Remove old key from new prefs if it was somehow written there
                changed = true
            }
        }

        // Migrate notify_plugin -> notify_plugins (from old settings or old key in new prefs)
        if (hasOldPrefs) migrateBoolean(com.example.data.MineHostPreferenceKeys.OLD_NOTIFY_PLUGINS, com.example.data.MineHostPreferenceKeys.NOTIFY_PLUGINS, oldPrefs)
        migrateBoolean(com.example.data.MineHostPreferenceKeys.OLD_NOTIFY_PLUGINS, com.example.data.MineHostPreferenceKeys.NOTIFY_PLUGINS, uiPrefs)

        // Migrate notify_quiet_hours -> quiet_hours
        if (hasOldPrefs) migrateBoolean(com.example.data.MineHostPreferenceKeys.OLD_QUIET_HOURS, com.example.data.MineHostPreferenceKeys.QUIET_HOURS, oldPrefs)
        migrateBoolean(com.example.data.MineHostPreferenceKeys.OLD_QUIET_HOURS, com.example.data.MineHostPreferenceKeys.QUIET_HOURS, uiPrefs)

        // Migrate automatic_engine_updates -> automatic_engine_update_checks
        if (hasOldPrefs) migrateBoolean(com.example.data.MineHostPreferenceKeys.OLD_AUTOMATIC_ENGINE_UPDATES, com.example.data.MineHostPreferenceKeys.AUTOMATIC_ENGINE_UPDATE_CHECKS, oldPrefs)
        migrateBoolean(com.example.data.MineHostPreferenceKeys.OLD_AUTOMATIC_ENGINE_UPDATES, com.example.data.MineHostPreferenceKeys.AUTOMATIC_ENGINE_UPDATE_CHECKS, uiPrefs)

        editor.putBoolean("migration_complete_v1", true)
        editor.apply()
    }
}
