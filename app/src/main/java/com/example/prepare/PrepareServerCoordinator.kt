package com.example.prepare

import android.content.Context
import com.example.data.ServerProfile
import com.example.plugins.PluginInstallRequest
import com.example.plugins.PluginInstallResult
import com.example.plugins.PluginInstaller
import com.example.plugins.MarketplacePlugin
import com.example.server.Downloader
import com.example.server.JavaRuntimeManager
import com.example.server.RuntimePreparationResult
import com.example.server.version.EngineVersion
import com.example.server.version.EngineInstallationMetadataFactory
import com.example.server.version.InstalledEngineVersionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PrepareServerCoordinator(
    private val context: Context,
    private val pluginInstaller: PluginInstaller,
    private val runtimePreparer: suspend (
        context: Context,
        javaMajor: Int,
        onProgress: (String) -> Unit,
    ) -> RuntimePreparationResult = { ctx, major, progress ->
        JavaRuntimeManager.ensureRuntimeReady(ctx, major, progress)
    },
) {
    enum class ComponentState { INSTALLED_VALID, MISSING, INVALID, OPTIONAL }

    data class Component(
        val id: String,
        val label: String,
        val state: ComponentState,
        val estimatedBytes: Long? = null
    )

    data class Plan(
        val serverUuid: String,
        val components: List<Component>,
        val requiredBytes: Long,
        val availableBytes: Long,
        val canProceed: Boolean,
        val warnings: List<String>
    )

    sealed class Result {
        data class Success(val completed: List<String>, val warnings: List<String>) : Result()
        data class PendingVerification(
            val completed: List<String>,
            val pending: List<String>,
            val warnings: List<String>,
        ) : Result()
        data class Failure(val stage: String, val message: String, val completed: List<String>) : Result()
    }

    suspend fun calculatePlan(
        profile: ServerProfile,
        engine: EngineVersion,
        selectedPlugins: List<MarketplacePlugin>,
        includeTunnelClient: Boolean
    ): Plan = withContext(Dispatchers.IO) {
        val root = File(profile.serverDirectory).apply { mkdirs() }
        val targetJavaMajor = com.example.server.version.EngineRuntimeJavaPolicy.requiredMajor(
            engine,
            profile.bedrockVersion
        )
        val runtimeIntegrity = JavaRuntimeManager.verifyRuntimeIntegrity(context, targetJavaMajor)
        val engineValid = InstalledEngineVersionRepository.matches(root, engine, profile.bedrockVersion)
        val components = buildList {
            add(
                Component(
                    "java-$targetJavaMajor",
                    "Java $targetJavaMajor ARM64 runtime",
                    if (runtimeIntegrity is com.example.server.RuntimeIntegrityResult.Valid) ComponentState.INSTALLED_VALID else ComponentState.MISSING
                )
            )
            add(
                Component(
                    "engine-${engine.id}",
                    engine.displayName,
                    if (engineValid) ComponentState.INSTALLED_VALID else ComponentState.MISSING
                )
            )
            selectedPlugins.forEach { plugin ->
                val installed = File(root, "plugins/${plugin.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.jar").isFile
                add(Component("plugin-${plugin.pluginId}", plugin.name, if (installed) ComponentState.INSTALLED_VALID else ComponentState.MISSING, plugin.fileSize))
            }
            add(Component("default-config", "Default server configuration", if (File(root, "server.properties").isFile) ComponentState.INSTALLED_VALID else ComponentState.MISSING, 16 * 1024))
            if (includeTunnelClient) add(Component("frpc", "FRP tunnel client", ComponentState.OPTIONAL))
        }
        val required = components.filter { it.state == ComponentState.MISSING }.sumOf { it.estimatedBytes ?: 0L }
        val available = root.usableSpace
        Plan(
            serverUuid = profile.id,
            components = components,
            requiredBytes = required,
            availableBytes = available,
            canProceed = available > required + 256L * 1024 * 1024,
            warnings = buildList {
                if (available <= required + 256L * 1024 * 1024) add("Not enough free storage with a 256 MB safety reserve")
                if (includeTunnelClient) add("FRPC requires a configured public FRPS relay before it can connect")
            }
        )
    }

    suspend fun prepare(
        profile: ServerProfile,
        engine: EngineVersion,
        selectedPlugins: List<MarketplacePlugin>,
        includeTunnelClient: Boolean,
        onProgress: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val plan = calculatePlan(profile, engine, selectedPlugins, includeTunnelClient)
        if (!plan.canProceed) return@withContext Result.Failure("storage", plan.warnings.joinToString("\n"), emptyList())
        val completed = mutableListOf<String>()
        val warnings = plan.warnings.toMutableList()
        val pending = mutableListOf<String>()
        val root = File(profile.serverDirectory).apply { mkdirs() }

        val selectedMinecraftVersion = if (engine.engineId == "java_paper" || engine.sourceType == com.example.server.version.VersionSourceType.PAPER_API) {
            profile.bedrockVersion.trim().takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
                ?: return@withContext Result.Failure("engine", "Paper requires an exact Minecraft version.", completed)
        } else {
            profile.bedrockVersion
        }

        val targetJavaMajor = com.example.server.version.EngineRuntimeJavaPolicy.requiredMajor(
            engine,
            selectedMinecraftVersion
        )

        when (val runtime = runtimePreparer(context, targetJavaMajor, onProgress)) {
            is RuntimePreparationResult.Ready -> completed += "Java ${runtime.javaMajor}"
            is RuntimePreparationResult.Unsupported -> return@withContext Result.Failure("runtime", runtime.message, completed)
            is RuntimePreparationResult.Failure -> return@withContext Result.Failure(runtime.stage, runtime.message, completed)
        }

        val jar = File(root, engine.jarFileName)
        if (!InstalledEngineVersionRepository.matches(root, engine, selectedMinecraftVersion)) {
            val downloadRes = Downloader.downloadServerJar(
                context = context,
                version = engine,
                destination = jar,
                minecraftVersion = selectedMinecraftVersion,
            ) { onProgress(it) }
            val identity = (downloadRes as? com.example.server.ServerJarDownloadResult.Success)?.identity
            if (downloadRes !is com.example.server.ServerJarDownloadResult.Success) {
                val msg = (downloadRes as? com.example.server.ServerJarDownloadResult.Failure)?.message ?: "Engine download or validation failed"
                return@withContext Result.Failure("engine", msg, completed)
            }
            val validation = InstalledEngineVersionRepository.validateJar(
                jar,
                engine.launchMode,
                engine.mainClass,
            )
            if (!validation.valid) {
                jar.delete()
                return@withContext Result.Failure(
                    "engine",
                    validation.error ?: "Engine JAR validation failed",
                    completed,
                )
            }

            val installed = EngineInstallationMetadataFactory.create(
                version = engine,
                bedrockVersion = selectedMinecraftVersion,
                jarFile = jar,
                validation = validation,
                resolvedIdentity = identity,
                runtimeJavaVersion = targetJavaMajor,
            )
            if (!InstalledEngineVersionRepository.write(root, installed)) {
                jar.delete()
                return@withContext Result.Failure(
                    "engine-metadata",
                    "Engine was downloaded but installation metadata could not be saved",
                    completed,
                )
            }
            if (!InstalledEngineVersionRepository.matches(
                    root,
                    engine,
                    selectedMinecraftVersion,
                )
            ) {
                jar.delete()
                return@withContext Result.Failure(
                    "engine-integrity",
                    "Prepared engine installation failed final integrity verification",
                    completed,
                )
            }
        }
        completed += engine.displayName

        ensureDefaultConfiguration(root, profile, engine)
        completed += "Default configuration"

        selectedPlugins.forEach { plugin ->
            val result = pluginInstaller.install(
                PluginInstallRequest(
                    serverUuid = profile.id,
                    engineId = profile.engineId,
                    engineVersionId = profile.engineVersionId,
                    minecraftVersion = selectedMinecraftVersion,
                    javaVersion = targetJavaMajor,
                    serverDirectory = root,
                    plugin = plugin
                )
            )
            when (result) {
                is PluginInstallResult.Failure -> return@withContext Result.Failure("plugin:${plugin.pluginId}", result.message, completed)
                is PluginInstallResult.PendingVerification -> {
                    pending += "Plugin ${plugin.name}: ${result.message}"
                }
                is PluginInstallResult.Success -> completed += plugin.name
            }
        }

        if (includeTunnelClient) {
            pending += "Tunnel: a verified FRPC binary and public FRPS relay configuration are still required"
        }
        if (pending.isNotEmpty()) {
            Result.PendingVerification(completed, pending, warnings.distinct())
        } else {
            Result.Success(completed, warnings.distinct())
        }
    }

    private fun ensureDefaultConfiguration(root: File, profile: ServerProfile, engine: EngineVersion) {
        val file = File(root, "server.properties")
        if (file.exists()) return
        if (profile.edition == com.example.data.ServerEdition.JAVA || engine.engineId == "java_paper" || engine.sourceType == com.example.server.version.VersionSourceType.PAPER_API) {
            com.example.javaedition.JavaServerPropertiesAdapter.applyProperties(
                serverDir = root,
                port = profile.port,
                onlineMode = true,
                levelName = profile.levelName,
                gameMode = "survival",
                difficulty = "normal",
                maxPlayers = profile.maxPlayers,
                motd = profile.name.ifBlank { "A MineHost Java Server" }
            )
        } else {
            val part = File(root, "server.properties.part")
            part.writeText(
                """
                motd=${profile.name}
                server-port=${profile.port}
                server-ip=0.0.0.0
                max-players=${profile.maxPlayers}
                level-name=${profile.levelName}
                gamemode=survival
                difficulty=normal
                view-distance=8
                white-list=off
                xbox-auth=on
                """.trimIndent() + "\n"
            )
            if (!part.renameTo(file)) {
                part.copyTo(file, overwrite = true)
                part.delete()
            }
        }
    }
}
