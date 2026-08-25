package com.example.ui.servercreation.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.NetworkMode
import com.example.ui.servercreation.TunnelProvider
import com.example.ui.servercreation.WizardTheme
import com.example.ui.servercreation.components.SelectableOptionCard
import com.example.ui.servercreation.components.TunnelArtwork
import com.example.ui.servercreation.components.WizardInfoBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkStep(
    draft: CreateServerDraft,
    onDraftUpdate: (CreateServerDraft) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                "Network & Access",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Text(
                "Configure how players will connect to your server.",
                style = MaterialTheme.typography.bodySmall,
                color = WizardTheme.SecondaryText
            )
        }

        // Network Mode
        Column {
            Text(
                "Network Mode",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NetworkMode.entries.forEach { mode ->
                    SelectableOptionCard(
                        title = mode.label,
                        description = mode.description,
                        selected = draft.networkMode == mode,
                        onClick = { onDraftUpdate(draft.copy(networkMode = mode)) },
                        icon = {
                            if (mode == NetworkMode.TUNNEL) {
                                TunnelArtwork(
                                    modifier = Modifier.fillMaxSize().padding(4.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = when (mode) {
                                        NetworkMode.LOCAL -> Icons.Outlined.Home
                                        NetworkMode.PUBLIC -> Icons.Outlined.Language
                                        else -> Icons.Outlined.Home
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = WizardTheme.PrimaryBlue
                                )
                            }
                        }
                    )
                }
            }
        }

        // Tunnel Provider (if tunnel mode selected)
        if (draft.networkMode == NetworkMode.TUNNEL) {
            Column {
                Text(
                    "Tunnel Provider",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = WizardTheme.PrimaryText
                )
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TunnelProvider.entries.forEach { provider ->
                        SelectableOptionCard(
                            title = provider.label,
                            description = provider.description,
                            selected = draft.tunnelProvider == provider,
                            onClick = { onDraftUpdate(draft.copy(tunnelProvider = provider)) }
                        )
                    }
                }
            }
        }

        // Advanced Network (Port & Visibility)
        Column {
            Text(
                "Advanced Network",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Port", style = MaterialTheme.typography.labelMedium, color = WizardTheme.SecondaryText, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = draft.port.toString(),
                        onValueChange = { onDraftUpdate(draft.copy(port = it.toIntOrNull() ?: 19132)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(WizardTheme.InputRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WizardTheme.PrimaryBlue,
                            unfocusedBorderColor = WizardTheme.Border,
                            focusedContainerColor = WizardTheme.SoftBlue.copy(alpha = 0.3f)
                        ),
                        singleLine = true
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Visibility", style = MaterialTheme.typography.labelMedium, color = WizardTheme.SecondaryText, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    var expanded by remember { mutableStateOf(false) }
                    val visibilityOptions = listOf("Invite Only", "Public", "Private")
                    
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = draft.visibility,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            shape = RoundedCornerShape(WizardTheme.InputRadius),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = WizardTheme.PrimaryBlue,
                                unfocusedBorderColor = WizardTheme.Border,
                                focusedContainerColor = WizardTheme.SoftBlue.copy(alpha = 0.3f)
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            visibilityOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        onDraftUpdate(draft.copy(visibility = option))
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        WizardInfoBanner(
            text = "Public Access requires port forwarding on your router. Use Tunnel if you can't access your router settings."
        )
    }
}
