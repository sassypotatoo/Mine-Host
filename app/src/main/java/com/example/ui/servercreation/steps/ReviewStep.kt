package com.example.ui.servercreation.steps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.NetworkMode
import com.example.ui.servercreation.WizardTheme
import com.example.ui.servercreation.components.ReviewSuccessArtwork
import com.example.server.template.TemplateRegistry

@Composable
fun ReviewStep(
    draft: CreateServerDraft,
    engineBuildName: String,
    manualVerificationRequired: Boolean,
    onDraftUpdate: (CreateServerDraft) -> Unit
) {
    val manualJarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                onDraftUpdate(
                    draft.copy(
                        manualEngineJarUri = uri,
                        manualEngineInstallAcknowledged = false,
                    )
                )
            }
        },
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(WizardTheme.SoftBlue),
                contentAlignment = Alignment.Center
            ) {
                ReviewSuccessArtwork(
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Review Server Details",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Text(
                "Please check your server configuration before creating.",
                style = MaterialTheme.typography.bodySmall,
                color = WizardTheme.SecondaryText
            )
        }

        if (manualVerificationRequired) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFFF3E0)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Manual engine verification required",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFE65100)
                    )
                    Text(
                        "This build has no trusted publisher checksum in the catalog. Select the exact publisher JAR and enter a SHA-256 obtained independently from the publisher.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WizardTheme.PrimaryText
                    )
                    OutlinedTextField(
                        value = draft.manualEngineSha256,
                        onValueChange = { value ->
                            onDraftUpdate(
                                draft.copy(
                                    manualEngineSha256 = value.filter { it.isLetterOrDigit() }.take(64),
                                    manualEngineInstallAcknowledged = false,
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Independent SHA-256") },
                        supportingText = { Text("${draft.manualEngineSha256.length}/64 hexadecimal characters") },
                        singleLine = true,
                        isError = draft.manualEngineSha256.isNotEmpty() &&
                            !draft.manualEngineSha256.matches(Regex("^[A-Fa-f0-9]{64}$")),
                    )
                    Button(
                        onClick = {
                            manualJarLauncher.launch(
                                arrayOf(
                                    "application/java-archive",
                                    "application/octet-stream",
                                    "application/zip",
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = draft.manualEngineSha256.matches(Regex("^[A-Fa-f0-9]{64}$")),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (draft.manualEngineJarUri == null) "Select exact engine JAR" else "Replace selected JAR")
                    }
                    if (draft.manualEngineJarUri != null) {
                        Text(
                            "Selected: ${draft.manualEngineJarUri.lastPathSegment ?: "engine JAR"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = WizardTheme.Success
                        )
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = draft.manualEngineInstallAcknowledged,
                            onCheckedChange = { checked ->
                                onDraftUpdate(draft.copy(manualEngineInstallAcknowledged = checked))
                            },
                            enabled = draft.manualEngineJarUri != null &&
                                draft.manualEngineSha256.matches(Regex("^[A-Fa-f0-9]{64}$")),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "I confirm this checksum was obtained independently and matches the exact selected JAR. MineHost will verify it before creating the profile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = WizardTheme.PrimaryText
                        )
                    }
                }
            }
        }

        if (TemplateRegistry.isJavaEditionEngine(draft.engine?.id)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFEFF6FF),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Minecraft Java Server EULA",
                        style =
                            MaterialTheme.typography
                                .titleSmall
                                .copy(
                                    fontWeight =
                                        FontWeight.Bold
                                ),
                        color = WizardTheme.PrimaryText,
                    )

                    Text(
                        "Paper is Minecraft server software and requires acceptance of the Minecraft EULA before the server can start.",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color = WizardTheme.SecondaryText,
                    )

                    Row(
                        verticalAlignment =
                            Alignment.Top,
                    ) {
                        Checkbox(
                            checked =
                                draft.minecraftEulaAccepted,
                            onCheckedChange = { accepted ->
                                onDraftUpdate(
                                    draft.copy(
                                        minecraftEulaAccepted =
                                            accepted
                                    )
                                )
                            },
                        )

                        Spacer(Modifier.width(8.dp))

                        Text(
                            "I have read and agree to the Minecraft EULA.",
                            style =
                                MaterialTheme.typography.bodySmall,
                            color = WizardTheme.PrimaryText,
                        )
                    }
                }
            }
        }

        // Summary Table
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WizardTheme.MainCardRadius))
                .border(1.dp, WizardTheme.Border, RoundedCornerShape(WizardTheme.MainCardRadius))
                .background(Color.White)
        ) {
            ReviewRow(icon = Icons.Outlined.CheckCircle, label = "Minecraft Version", value = draft.bedrockVersion ?: "Unknown")
            ReviewRow(icon = Icons.Outlined.Settings, label = "Engine", value = draft.engine?.name ?: "Bedrock")
            ReviewRow(icon = Icons.Outlined.History, label = "Engine Build", value = engineBuildName)
            ReviewRow(icon = Icons.Outlined.Public, label = "World Type", value = draft.worldType.label)
            ReviewRow(
                icon = Icons.Outlined.Casino,
                label = "Seed Mode",
                value = if (draft.worldSeedMode == com.example.server.engine.WorldSeedMode.RANDOM) "Random" else "Custom"
            )
            ReviewRow(
                icon = Icons.Outlined.Tag,
                label = "World Seed",
                value = draft.worldSeed.toString()
            )
            ReviewRow(
                icon = Icons.Outlined.Language,
                label = "World Name",
                value = if (draft.worldName.isNotBlank()) draft.worldName else "world"
            )
            ReviewRow(icon = Icons.Outlined.Flag, label = "Difficulty", value = draft.difficulty.label)
            ReviewRow(icon = Icons.Outlined.Memory, label = "Memory", value = "${draft.memoryMb} MB")
            ReviewRow(icon = Icons.Outlined.Person, label = "Max Players", value = draft.maxPlayers.toString())
            ReviewRow(icon = Icons.Outlined.Lan, label = "Port", value = draft.port.toString())
            ReviewRow(icon = Icons.Outlined.Wifi, label = "Network Mode", value = draft.networkMode.label)
            if (draft.networkMode == NetworkMode.TUNNEL) {
                ReviewRow(icon = Icons.Outlined.Security, label = "Tunnel Provider", value = draft.tunnelProvider.label)
            }
        }
    }
}

@Composable
private fun ReviewRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = WizardTheme.PrimaryBlue
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = WizardTheme.PrimaryText
                )
            }
            Surface(
                color = WizardTheme.SoftBlue,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    value,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = WizardTheme.PrimaryBlue
                    )
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = WizardTheme.Border.copy(alpha = 0.5f))
    }
}