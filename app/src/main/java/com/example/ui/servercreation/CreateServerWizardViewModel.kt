package com.example.ui.servercreation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MainViewModel
import com.example.server.Downloader
import com.example.data.ServerCreationDraft
import com.example.server.template.ServerTemplate
import com.example.server.template.TemplateRegistry
import com.example.server.version.BedrockVersionOption
import com.example.server.version.EngineVersion
import com.example.server.version.EngineInstallability
import com.example.server.version.installability
import com.example.server.version.EngineVersionCatalogRepository
import com.example.server.version.VersionSourceType
import com.example.javaedition.PaperResolver
import com.example.MineHostApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class CreateServerWizardViewModel(application: Application) : AndroidViewModel(application) {
    private val catalogRepository = (application as MineHostApplication).catalogRepository
    private val _dynamicVersions = MutableStateFlow<List<BedrockVersionOption>>(emptyList())

    sealed class DynamicVersionState {
        object IDLE : DynamicVersionState()
        object LOADING : DynamicVersionState()
        data class LOADED(val versions: List<BedrockVersionOption>) : DynamicVersionState()
        data class ERROR(val message: String) : DynamicVersionState()
    }

    private val _dynamicVersionState = MutableStateFlow<DynamicVersionState>(DynamicVersionState.IDLE)
    val dynamicVersionState: StateFlow<DynamicVersionState> = _dynamicVersionState.asStateFlow()

    private fun effectiveInstallability(version: EngineVersion): EngineInstallability {
        val catalogStatus = version.installability()
        return if (catalogStatus == EngineInstallability.MANUAL_VERIFICATION_REQUIRED &&
            Downloader.getTrustedChecksumForInstall(getApplication(), version) != null
        ) {
            EngineInstallability.AUTOMATIC_DOWNLOAD
        } else {
            catalogStatus
        }
    }

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage = _operationMessage.asStateFlow()

    private fun showMessage(message: String) {
        _operationMessage.value = message
    }

    private val _draft = MutableStateFlow(CreateServerDraft(
        engine = TemplateRegistry.BEDROCK_NUKKIT_MOT,
        engineVersionId = catalogRepository.getDefaultVersion(TemplateRegistry.BEDROCK_NUKKIT_MOT.id)?.id,
        bedrockVersion = catalogRepository.getDefaultVersion(TemplateRegistry.BEDROCK_NUKKIT_MOT.id)?.recommendedBedrockVersion
    ))
    val draft: StateFlow<CreateServerDraft> = _draft.asStateFlow()

    private val _currentStep = MutableStateFlow(WizardStep.BASICS)
    val currentStep: StateFlow<WizardStep> = _currentStep.asStateFlow()

    fun updateDraft(update: (CreateServerDraft) -> CreateServerDraft) {
        _draft.update(update)
    }

    fun setDraft(newDraft: CreateServerDraft) {
        _draft.value = newDraft
    }

    fun selectEngine(template: ServerTemplate) {
        val defaultEngineVersion = catalogRepository.getDefaultVersion(template.id)
        val defaultBedrockVersion = when {
            template.id == "java_paper" -> null
            defaultEngineVersion == null -> null
            defaultEngineVersion.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION -> "AUTO"
            else -> defaultEngineVersion.recommendedBedrockVersion ?: defaultEngineVersion.supportedBedrockVersions.firstOrNull()
        }
        
        updateDraft {
            it.copy(
                engine = template,
                engineVersionId = defaultEngineVersion?.id ?: if (template.id == "java_paper") "java_paper:api" else null,
                bedrockVersion = defaultBedrockVersion,
                manualEngineInstallAcknowledged = false,
                manualEngineJarUri = null,
                manualEngineSha256 = ""
            )
        }
    }

    fun selectBedrockVersion(option: BedrockVersionOption) {
        val selectedEngineId = _draft.value.engine?.id ?: return
        
        // Validation (Part 8)
        val isValid = if (selectedEngineId == "java_paper") {
            !option.bedrockVersion.equals("AUTO", ignoreCase = true)
        } else {
            val version = catalogRepository.findVersion(option.engineVersionId)
            if (version == null || version.engineId != selectedEngineId) {
                false
            } else if (version.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) {
                option.bedrockVersion == "AUTO"
            } else {
                version.supportedBedrockVersions.contains(option.bedrockVersion) || version.recommendedBedrockVersion == option.bedrockVersion
            }
        }

        if (!isValid) return

        updateDraft {
            it.copy(
                bedrockVersion = option.bedrockVersion,
                engineVersionId = option.engineVersionId,
                manualEngineInstallAcknowledged = false,
                manualEngineJarUri = null,
                manualEngineSha256 = ""
            )
        }
    }

    val bedrockVersionOptions = combine(
        draft,
        catalogRepository.versions,
        _dynamicVersions
    ) { currentDraft, versions, dynamic ->
        val engineId = currentDraft.engine?.id ?: return@combine emptyList<BedrockVersionOption>()

        if (engineId == "java_paper") {
            return@combine dynamic
        }

        val engineVersions = versions.filter { it.engineId == engineId }
        
        val result = mutableListOf<BedrockVersionOption>()
        for (ev in engineVersions) {
            when (ev.compatibilityMode) {
                com.example.server.version.CompatibilityMode.SINGLE_VERSION -> {
                    val bv = ev.recommendedBedrockVersion ?: ev.supportedBedrockVersions.firstOrNull()
                    if (bv != null) {
                        result.add(BedrockVersionOption(
                            bedrockVersion = bv,
                            engineVersionId = ev.id,
                            engineBuildName = ev.displayName,
                            recommended = ev.recommended,
                            compatibilityMode = ev.compatibilityMode,
                            compatibilitySummary = ev.compatibilitySummary,
                            historical = ev.historical,
                            installability = effectiveInstallability(ev)
                        ))
                    }
                }
                com.example.server.version.CompatibilityMode.MULTI_VERSION -> {
                    result.add(BedrockVersionOption(
                        bedrockVersion = "AUTO",
                        engineVersionId = ev.id,
                        engineBuildName = ev.displayName,
                        recommended = ev.recommended,
                        compatibilityMode = ev.compatibilityMode,
                        compatibilitySummary = ev.compatibilitySummary ?: "One build accepts multiple supported Bedrock client versions.",
                        historical = ev.historical,
                        installability = effectiveInstallability(ev)
                    ))
                }
                else -> {}
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var paperVersionLoadJob: Job? = null

    fun loadPaperVersions() {
        if (_draft.value.engine?.id != "java_paper") return

        paperVersionLoadJob?.let { it.cancel() }
        paperVersionLoadJob = viewModelScope.launch {
            _dynamicVersionState.value = DynamicVersionState.LOADING
            val paperMeta = catalogRepository.getVersionsForEngine("java_paper").firstOrNull()
            val paperMetaId = paperMeta?.id ?: "java_paper:api"
            val res = PaperResolver.getAvailableVersions()

            if (_draft.value.engine?.id != "java_paper") {
                return@launch
            }

            if (res.isSuccess && res.getOrNull()?.isNotEmpty() == true) {
                if (_draft.value.engine?.id != "java_paper") {
                    return@launch
                }
                val versions = res.getOrThrow()
                val mappedVersions = versions.mapIndexed { index, v ->
                    BedrockVersionOption(
                        bedrockVersion = v,
                        engineVersionId = paperMetaId,
                        engineBuildName = "PaperMC $v",
                        recommended = index == 0,
                        compatibilityMode = com.example.server.version.CompatibilityMode.SINGLE_VERSION,
                        compatibilitySummary = "Verified STABLE build via PaperMC API",
                        historical = false,
                        installability = EngineInstallability.AUTOMATIC_DOWNLOAD
                    )
                }
                _dynamicVersions.value = mappedVersions
                _dynamicVersionState.value = DynamicVersionState.LOADED(mappedVersions)

                val currentVer = _draft.value.bedrockVersion
                if (_draft.value.engine?.id == "java_paper") {
                    if (currentVer.isNullOrBlank() || currentVer.equals("AUTO", ignoreCase = true) || !versions.contains(currentVer)) {
                        updateDraft { it.copy(
                            bedrockVersion = versions.first(),
                            engineVersionId = paperMetaId
                        ) }
                    }
                }
            } else {
                if (_draft.value.engine?.id != "java_paper") {
                    return@launch
                }
                val errorMsg = res.exceptionOrNull()?.message ?: "Failed to fetch PaperMC versions"
                _dynamicVersions.value = emptyList()
                _dynamicVersionState.value = DynamicVersionState.ERROR(errorMsg)
                updateDraft { it.copy(
                    bedrockVersion = null,
                    engineVersionId = paperMetaId
                ) }
            }
        }
    }

    init {
        // Dynamic version loader for Paper API
        draft.map { it.engine?.id }
            .distinctUntilChanged()
            .onEach { engineId ->
                if (engineId == "java_paper") {
                    loadPaperVersions()
                } else {
                    paperVersionLoadJob?.let { it.cancel() }
                    paperVersionLoadJob = null
                    _dynamicVersions.value = emptyList()
                    _dynamicVersionState.value = DynamicVersionState.IDLE
                }
            }.launchIn(viewModelScope)

        // Observe versions to ensure current selection is valid (Part 2)
        catalogRepository.versions.onEach { versions ->
            val currentDraft = _draft.value
            val engineId = currentDraft.engine?.id ?: return@onEach
            val versionId = currentDraft.engineVersionId ?: return@onEach
            
            val version = versions.find { it.id == versionId }
            if (version == null || version.engineId != engineId || version.installability() == EngineInstallability.UNAVAILABLE) {
                // Current version disappeared or belongs to another engine
                val selectableVersions = versions.filter {
                    it.engineId == engineId && it.installability() != EngineInstallability.UNAVAILABLE
                }
                val recommended = selectableVersions.find { it.recommended }
                    ?: selectableVersions.firstOrNull()
                
                if (recommended != null) {
                    val bv = if (recommended.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) {
                        "AUTO"
                    } else {
                        recommended.recommendedBedrockVersion ?: recommended.supportedBedrockVersions.firstOrNull()
                    }
                    
                    updateDraft { 
                        it.copy(
                            engineVersionId = recommended.id,
                            bedrockVersion = bv
                        )
                    }
                } else {
                    // No versions available for this engine
                    updateDraft { 
                        it.copy(
                            engineVersionId = null,
                            bedrockVersion = null
                        )
                    }
                }
            }
        }.launchIn(viewModelScope)
    }

    fun retryPaperFetch() {
        if (_draft.value.engine?.id == "java_paper") {
            loadPaperVersions()
        }
    }

    fun getBedrockVersionsForCurrentEngine(): List<BedrockVersionOption> {
        return bedrockVersionOptions.value
    }

    fun getEngineVersion(versionId: String): EngineVersion? {
        return catalogRepository.findVersion(versionId)
    }

    val availableEngineIds = catalogRepository.versions.map { versions ->
        TemplateRegistry.ALL_TEMPLATES.filter { template ->
            template.available && versions.any {
                it.engineId == template.id && it.installability() != EngineInstallability.UNAVAILABLE
            }
        }.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val manualVerificationEngineIds = catalogRepository.versions.map { versions ->
        TemplateRegistry.ALL_TEMPLATES.mapNotNull { template ->
            val selectable = versions.filter {
                it.engineId == template.id && it.installability() != EngineInstallability.UNAVAILABLE
            }
            template.id.takeIf {
                selectable.isNotEmpty() && selectable.none { version ->
                    effectiveInstallability(version) == EngineInstallability.AUTOMATIC_DOWNLOAD
                }
            }
        }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isEngineAvailable(engineId: String): Boolean = availableEngineIds.value.contains(engineId)

    fun requiresManualVerification(engineId: String): Boolean =
        manualVerificationEngineIds.value.contains(engineId)

    fun selectedInstallability(): EngineInstallability =
        _draft.value.engineVersionId
            ?.let(catalogRepository::findVersion)
            ?.let(::effectiveInstallability)
            ?: EngineInstallability.UNAVAILABLE

    fun nextStep(): Boolean {
        val steps = WizardStep.entries
        val currentIndex = steps.indexOf(_currentStep.value)
        if (currentIndex < steps.size - 1) {
            _currentStep.value = steps[currentIndex + 1]
            return true
        }
        return false
    }

    fun previousStep(): Boolean {
        val steps = WizardStep.entries
        val currentIndex = steps.indexOf(_currentStep.value)
        if (currentIndex > 0) {
            _currentStep.value = steps[currentIndex - 1]
            return true
        }
        return false
    }

    val canContinue = combine(_currentStep, _draft) { step, draft ->
        when (step) {
            WizardStep.BASICS -> draft.serverName.isNotBlank()
            WizardStep.ENGINE -> draft.engine?.id?.let(::isEngineAvailable) == true
            WizardStep.VERSION -> {
                val bedrockVersion = draft.bedrockVersion
                val engineVersionId = draft.engineVersionId
                if (bedrockVersion.isNullOrBlank() || engineVersionId == null) return@combine false
                
                val version = catalogRepository.findVersion(engineVersionId)
                if (version == null || version.engineId != draft.engine?.id ||
                    version.installability() == EngineInstallability.UNAVAILABLE
                ) return@combine false
                
                if (draft.engine?.id == "java_paper") {
                    !bedrockVersion.equals("AUTO", ignoreCase = true)
                } else if (version.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) {
                    bedrockVersion == "AUTO"
                } else {
                    version.supportedBedrockVersions.contains(bedrockVersion) || version.recommendedBedrockVersion == bedrockVersion
                }
            }
            WizardStep.WORLD -> {
                if (draft.worldSeedMode == com.example.server.engine.WorldSeedMode.RANDOM) {
                    true
                } else {
                    com.example.world.WorldSeedParser.parse(draft.seedInput) is com.example.world.SeedParseResult.Valid
                }
            }
            WizardStep.REVIEW -> {
                val installability = draft.engineVersionId
                    ?.let(catalogRepository::findVersion)
                    ?.let(::effectiveInstallability)
                    ?: EngineInstallability.UNAVAILABLE
                val installValid = installability == EngineInstallability.AUTOMATIC_DOWNLOAD ||
                    (installability == EngineInstallability.MANUAL_VERIFICATION_REQUIRED &&
                        draft.manualEngineInstallAcknowledged &&
                        draft.manualEngineJarUri != null &&
                        draft.manualEngineSha256.matches(Regex("^[A-Fa-f0-9]{64}$")))

                if (draft.engine?.id == "java_paper") {
                    installValid && draft.minecraftEulaAccepted
                } else {
                    installValid
                }
            }
            else -> true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun createServer(mainViewModel: MainViewModel) {
        val currentDraft = _draft.value
        val engineId = currentDraft.engine?.id ?: return
        val versionId = currentDraft.engineVersionId ?: return
        val bedrockVersion = currentDraft.bedrockVersion ?: return

        // Strict validation
        val version = catalogRepository.findVersion(versionId)
        val installability = version?.let(::effectiveInstallability) ?: EngineInstallability.UNAVAILABLE
        if (installability == EngineInstallability.UNAVAILABLE) {
            showMessage("The selected engine build is unavailable.")
            return
        }
        if (installability == EngineInstallability.MANUAL_VERIFICATION_REQUIRED &&
            (!currentDraft.manualEngineInstallAcknowledged ||
                currentDraft.manualEngineJarUri == null ||
                !currentDraft.manualEngineSha256.matches(Regex("^[A-Fa-f0-9]{64}$")))
        ) {
            showMessage("Select the exact engine JAR, enter its independently obtained SHA-256, and confirm manual verification.")
            return
        }

        val isValid = if (version == null || version.engineId != engineId) {
            false
        } else if (engineId == "java_paper") {
            bedrockVersion.isNotBlank() && !bedrockVersion.equals("AUTO", ignoreCase = true)
        } else if (version.compatibilityMode == com.example.server.version.CompatibilityMode.MULTI_VERSION) {
            bedrockVersion == "AUTO"
        } else {
            version.supportedBedrockVersions.contains(bedrockVersion) || version.recommendedBedrockVersion == bedrockVersion
        }

        if (!isValid) {
            showMessage("Selected Minecraft version is not supported by the selected engine build.")
            return
        }

        val isJava = engineId == "java_paper"
        if (isJava && !currentDraft.minecraftEulaAccepted) {
            showMessage("You must accept the Minecraft End User License Agreement (EULA) before creating a Paper server.")
            return
        }

        val engineCapabilities = try {
            com.example.server.engine.ConfigAdapterFactory.getAdapter(engineId).capabilities
        } catch (e: Exception) {
            com.example.server.engine.EngineWorldGenerationCapabilities(supportsDefault = true, supportsFlat = true, levelTypeProperty = "level-type", defaultValue = "DEFAULT", flatValue = "FLAT", evidenceRepository = "", evidenceRevision = "", evidenceFile = "", evidenceNote = "")
        }

        if (currentDraft.worldType == WorldType.FLAT && !engineCapabilities.supportsFlat) {
            showMessage("Flat world generation is not supported by the selected engine.")
            return
        }

        val finalSeed = if (currentDraft.worldSeedMode == com.example.server.engine.WorldSeedMode.RANDOM) {
            currentDraft.worldSeed
        } else {
            when (val parseRes = com.example.world.WorldSeedParser.parse(currentDraft.seedInput)) {
                is com.example.world.SeedParseResult.Valid -> parseRes.value
                is com.example.world.SeedParseResult.Invalid -> {
                    showMessage("Invalid custom seed: ${parseRes.message}")
                    return
                }
            }
        }

        val effectivePort = if (isJava && currentDraft.port == 19132) 25565 else currentDraft.port

        val creationDraft = ServerCreationDraft(
            name = currentDraft.serverName,
            engineId = engineId,
            engineVersionId = versionId,
            bedrockVersion = bedrockVersion,
            memoryMb = currentDraft.memoryMb,
            maxPlayers = currentDraft.maxPlayers,
            port = effectivePort,
            levelName = if (currentDraft.worldName.isNotBlank()) currentDraft.worldName else "world",
            worldSeed = finalSeed,
            worldSeedMode = currentDraft.worldSeedMode,
            worldSeedKnown = true,
            gameMode = when (currentDraft.worldType) {
                WorldType.CREATIVE -> "1"
                WorldType.ADVENTURE -> "2"
                else -> "0"
            },
            difficulty = when (currentDraft.difficulty) {
                Difficulty.EASY -> "1"
                Difficulty.NORMAL -> "2"
                Difficulty.HARD -> "3"
            },
            levelType = if (currentDraft.worldType == WorldType.FLAT) "FLAT" else "DEFAULT",
            edition = if (isJava) com.example.data.ServerEdition.JAVA else com.example.data.ServerEdition.BEDROCK,
            networkType = if (isJava) com.example.data.ServerNetworkType.JAVA_TCP else com.example.data.ServerNetworkType.BEDROCK_RAKNET_UDP,
            minecraftVersion = bedrockVersion,
            minecraftEulaAccepted = currentDraft.minecraftEulaAccepted
        )
        mainViewModel.createServer(
            draft = creationDraft,
            iconUri = currentDraft.artworkUri,
            manualEngineJarUri = currentDraft.manualEngineJarUri,
            manualEngineSha256 = currentDraft.manualEngineSha256.takeIf { it.isNotBlank() },
        )
    }
}
