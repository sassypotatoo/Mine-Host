package com.example.ui.servercreation.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.server.version.BedrockVersionOption
import com.example.server.version.CompatibilityMode
import com.example.server.version.EngineVersion
import com.example.server.version.EngineInstallability
import com.example.server.version.ReleaseChannel
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.CreateServerWizardViewModel
import com.example.ui.servercreation.WizardTheme

@Composable
fun VersionStep(
    draft: CreateServerDraft,
    bedrockVersions: List<BedrockVersionOption>,
    dynamicVersionState: CreateServerWizardViewModel.DynamicVersionState = CreateServerWizardViewModel.DynamicVersionState.IDLE,
    onRetryPaperFetch: () -> Unit = {},
    onBedrockVersionSelected: (BedrockVersionOption) -> Unit
) {
    val isJava = draft.engine?.id == "java_paper"
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                "Minecraft Version",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Text(
                if (isJava) "Choose a Minecraft Java Edition version supported by PaperMC."
                else "Choose a Minecraft Bedrock version supported by this server engine.",
                style = MaterialTheme.typography.bodySmall,
                color = WizardTheme.SecondaryText
            )
        }

        if (isJava) {
            when (dynamicVersionState) {
                is CreateServerWizardViewModel.DynamicVersionState.LOADING -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(100.dp).padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Fetching available PaperMC versions...",
                                color = WizardTheme.SecondaryText,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                is CreateServerWizardViewModel.DynamicVersionState.ERROR -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp).padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                dynamicVersionState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = onRetryPaperFetch,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                is CreateServerWizardViewModel.DynamicVersionState.LOADED -> {
                    if (dynamicVersionState.versions.isEmpty()) {
                        EmptyVersionsBox(isJava)
                    } else {
                        dynamicVersionState.versions.forEach { option ->
                            BedrockVersionCard(
                                option = option,
                                isJava = isJava,
                                selected = draft.bedrockVersion == option.bedrockVersion && draft.engineVersionId == option.engineVersionId,
                                onClick = { onBedrockVersionSelected(option) }
                            )
                        }
                    }
                }
                else -> {
                    if (bedrockVersions.isEmpty()) {
                        EmptyVersionsBox(isJava)
                    } else {
                        bedrockVersions.forEach { option ->
                            BedrockVersionCard(
                                option = option,
                                isJava = isJava,
                                selected = draft.bedrockVersion == option.bedrockVersion && draft.engineVersionId == option.engineVersionId,
                                onClick = { onBedrockVersionSelected(option) }
                            )
                        }
                    }
                }
            }
        } else {
            if (bedrockVersions.isEmpty()) {
                EmptyVersionsBox(isJava)
            } else {
                bedrockVersions.forEach { option ->
                    BedrockVersionCard(
                        option = option,
                        isJava = isJava,
                        selected = draft.bedrockVersion == option.bedrockVersion && draft.engineVersionId == option.engineVersionId,
                        onClick = { onBedrockVersionSelected(option) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyVersionsBox(isJava: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().height(100.dp).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (isJava) "No PaperMC versions found." 
            else "Verified Minecraft compatibility is not available for this engine build.",
            color = WizardTheme.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun BedrockVersionCard(
    option: BedrockVersionOption,
    isJava: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) WizardTheme.PrimaryBlue else WizardTheme.Border,
                shape = RoundedCornerShape(WizardTheme.OptionCardRadius)
            ),
        shape = RoundedCornerShape(WizardTheme.OptionCardRadius),
        color = if (selected) WizardTheme.PrimaryBlue.copy(alpha = 0.05f) else Color.White,
        tonalElevation = if (selected) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Version Type Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (selected) WizardTheme.PrimaryBlue.copy(alpha = 0.1f) 
                        else WizardTheme.DisabledBackground,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.Check,
                    contentDescription = null,
                    tint = if (selected) WizardTheme.PrimaryBlue else WizardTheme.DisabledText,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isJava) "Minecraft Java" else "Minecraft Bedrock",
                            style = MaterialTheme.typography.labelSmall,
                            color = WizardTheme.SecondaryText
                        )
                        Text(
                            text = option.bedrockVersion,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = WizardTheme.PrimaryText
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    
                    if (option.recommended) {
                        Spacer(Modifier.width(8.dp))
                        VersionTag(text = "Recommended", color = WizardTheme.Success)
                    }

                    if (option.installability == EngineInstallability.MANUAL_VERIFICATION_REQUIRED) {
                        Spacer(Modifier.width(8.dp))
                        VersionTag(text = "Manual verify", color = Color(0xFFF57C00))
                    }
                    
                    if (option.historical) {
                        Spacer(Modifier.width(8.dp))
                        VersionTag(text = "Historical", color = Color(0xFFF57C00)) // Orange
                    }
                }
                
                Spacer(Modifier.height(4.dp))
                
                if (option.historical) {
                    Text(
                        text = "⚠ This is an old version. Compatibility with modern clients may be limited.",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFFF57C00)
                    )
                    Spacer(Modifier.height(2.dp))
                }
                
                Text(
                    text = "Compatible build: ${option.engineBuildName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WizardTheme.SecondaryText
                )

                if (option.installability == EngineInstallability.MANUAL_VERIFICATION_REQUIRED) {
                    Text(
                        text = "Automatic download blocked: publisher SHA-256 is missing. Exact JAR + independent SHA-256 required.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF57C00)
                    )
                }

                if (option.compatibilityMode == CompatibilityMode.MULTI_VERSION && !option.compatibilitySummary.isNullOrBlank()) {
                    Text(
                        text = option.compatibilitySummary!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = WizardTheme.SecondaryText.copy(alpha = 0.8f)
                    )
                }
            }

            if (selected) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(WizardTheme.PrimaryBlue, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionTag(
    text: String,
    color: Color
) {
    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = Color.White
            )
        )
    }
}
