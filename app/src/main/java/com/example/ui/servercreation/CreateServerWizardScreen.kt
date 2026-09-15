package com.example.ui.servercreation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.MainViewModel
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.servercreation.components.WizardBottomBar
import com.example.ui.servercreation.components.WizardCard
import com.example.ui.servercreation.components.WizardProgressIndicator
import com.example.ui.servercreation.steps.*

@Composable
fun CreateServerWizardScreen(
    mainViewModel: MainViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val wizardViewModel: CreateServerWizardViewModel = viewModel()
    val draft by wizardViewModel.draft.collectAsState()
    val currentStep by wizardViewModel.currentStep.collectAsState()
    val canContinue by wizardViewModel.canContinue.collectAsState()
    val bedrockVersionOptions by wizardViewModel.bedrockVersionOptions.collectAsState()
    val operationMessage by wizardViewModel.operationMessage.collectAsState()
    
    val saveResult by mainViewModel.saveResult.collectAsState()
    val operationInProgress by mainViewModel.operationInProgress.collectAsState()

    LaunchedEffect(saveResult) {
        if (saveResult is MainViewModel.SaveServerResult.Success) {
            mainViewModel.clearSaveResult()
            onDone()
        }
    }

    BackHandler(enabled = !operationInProgress) {
        if (!wizardViewModel.previousStep()) {
            onBack()
        }
    }

    val availableEngineIds by wizardViewModel.availableEngineIds.collectAsState()
    val manualVerificationEngineIds by wizardViewModel.manualVerificationEngineIds.collectAsState()
    
    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(WizardTheme.Background)
            ) {
                MineHostBrandHeader(
                    showBack = true,
                    onBack = {
                        if (operationInProgress) return@MineHostBrandHeader
                        if (!wizardViewModel.previousStep()) {
                            onBack()
                        }
                    },
                    compact = true,
                    showProfile = false,
                    showNotifications = false
                )
                
                Column(modifier = Modifier.padding(horizontal = WizardTheme.HorizontalPadding)) {
                    Spacer(Modifier.height(WizardTheme.HeaderToTitle))
                    Text(
                        "Create New Server",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = WizardTheme.PrimaryText,
                            fontSize = 22.sp
                        )
                    )
                    Spacer(Modifier.height(WizardTheme.TitleToSubtitle))
                    Text(
                        "Step ${currentStep.ordinal + 1} of 8 · ${currentStep.title}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = WizardTheme.PrimaryBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    )
                    Spacer(Modifier.height(WizardTheme.SubtitleToProgress))
                    WizardProgressIndicator(currentStep = currentStep)
                    
                    if (saveResult is MainViewModel.SaveServerResult.Failure) {
                        Text(
                            (saveResult as MainViewModel.SaveServerResult.Failure).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (operationMessage != null) {
                        Text(
                            operationMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    
                    Spacer(Modifier.height(WizardTheme.ProgressToMainCard))
                }
            }
        },
        bottomBar = {
            WizardBottomBar(
                onBack = {
                    if (operationInProgress) return@WizardBottomBar
                    if (!wizardViewModel.previousStep()) {
                        onBack()
                    }
                },
                onContinue = {
                    if (operationInProgress) return@WizardBottomBar
                    if (currentStep == WizardStep.REVIEW) {
                        wizardViewModel.createServer(mainViewModel)
                    } else {
                        wizardViewModel.nextStep()
                    }
                },
                backEnabled = currentStep.ordinal > 0 && !operationInProgress,
                continueEnabled = canContinue && !operationInProgress,
                isLastStep = currentStep == WizardStep.REVIEW,
                isLoading = operationInProgress
            )
        },
        containerColor = WizardTheme.Background
    ) { padding ->
        androidx.compose.runtime.key(currentStep) {
            val scrollState = rememberScrollState()
            
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scrollState)
                    .padding(horizontal = WizardTheme.HorizontalPadding)
            ) {
                WizardCard {
                    when (currentStep) {
                        WizardStep.BASICS -> BasicsStep(draft, wizardViewModel::setDraft)
                        WizardStep.EDITION -> EditionStep(draft, wizardViewModel::setDraft)
                        WizardStep.ENGINE -> EngineStep(
                            draft = draft,
                            isEngineAvailable = { availableEngineIds.contains(it) },
                            requiresManualVerification = { manualVerificationEngineIds.contains(it) },
                            onEngineSelected = wizardViewModel::selectEngine
                        )
                        WizardStep.VERSION -> {
                            val dynamicVersionState by wizardViewModel.dynamicVersionState.collectAsState()
                            VersionStep(
                                draft = draft,
                                bedrockVersions = bedrockVersionOptions,
                                dynamicVersionState = dynamicVersionState,
                                onRetryPaperFetch = wizardViewModel::retryPaperFetch,
                                onBedrockVersionSelected = wizardViewModel::selectBedrockVersion
                            )
                        }
                        WizardStep.WORLD -> WorldStep(draft, wizardViewModel::setDraft)
                        WizardStep.PERFORMANCE -> PerformanceStep(draft, wizardViewModel::setDraft)
                        WizardStep.NETWORK -> NetworkStep(draft, wizardViewModel::setDraft)
                        WizardStep.REVIEW -> ReviewStep(
                            draft = draft,
                            engineBuildName = draft.engineVersionId?.let { wizardViewModel.getEngineVersion(it)?.displayName } ?: "Unknown",
                            manualVerificationRequired = wizardViewModel.selectedInstallability() == com.example.server.version.EngineInstallability.MANUAL_VERIFICATION_REQUIRED,
                            onDraftUpdate = wizardViewModel::setDraft
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
