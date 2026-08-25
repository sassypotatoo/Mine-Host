package com.example.ui.servercreation.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.PerformanceProfile
import com.example.ui.servercreation.WizardTheme
import com.example.ui.servercreation.components.MineHostSlider
import com.example.ui.servercreation.components.PerformanceArtwork
import com.example.ui.servercreation.components.WizardInfoBanner

@Composable
fun PerformanceStep(
    draft: CreateServerDraft,
    onDraftUpdate: (CreateServerDraft) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                "Performance & Resources",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Text(
                "Configure the resources and performance settings for your server.",
                style = MaterialTheme.typography.bodySmall,
                color = WizardTheme.SecondaryText
            )
        }

        // Memory Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = WizardTheme.PrimaryBlue
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Memory (RAM)", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                }
                Surface(
                    color = Color(0xFFF1F7FF),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "${draft.memoryMb} MB",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = WizardTheme.PrimaryBlue
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            MineHostSlider(
                value = draft.memoryMb.toFloat(),
                onValueChange = { onDraftUpdate(draft.copy(memoryMb = (it / 256).toInt() * 256)) },
                valueRange = 512f..4096f
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("512 MB", style = MaterialTheme.typography.labelSmall, color = WizardTheme.SecondaryText)
                Text("Recommended: 1024 MB", style = MaterialTheme.typography.labelSmall, color = WizardTheme.SecondaryText)
                Text("4096 MB", style = MaterialTheme.typography.labelSmall, color = WizardTheme.SecondaryText)
            }
        }

        // Performance Profile
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = WizardTheme.PrimaryBlue
                )
                Spacer(Modifier.width(8.dp))
                Text("Performance Profile", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
            }
            Spacer(Modifier.height(12.dp))
            
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(PerformanceProfile.entries) { profile ->
                    ProfileCard(
                        profile = profile,
                        selected = draft.performanceProfile == profile,
                        onClick = { 
                            onDraftUpdate(draft.copy(
                                performanceProfile = profile,
                                memoryMb = profile.ramMb
                            ))
                        },
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }

        // Max Players Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.People,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = WizardTheme.PrimaryBlue
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Max Players", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
                }
                Surface(
                    color = Color(0xFFF1F7FF),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "${draft.maxPlayers}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = WizardTheme.PrimaryBlue
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            MineHostSlider(
                value = draft.maxPlayers.toFloat(),
                onValueChange = { onDraftUpdate(draft.copy(maxPlayers = it.toInt())) },
                valueRange = 1f..50f
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (1..5).forEach { i ->
                    Text("${i * 10}", style = MaterialTheme.typography.labelSmall, color = WizardTheme.SecondaryText)
                }
            }
        }

        // Switches
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwitchRow(
                icon = Icons.Outlined.Bolt,
                title = "CPU Priority",
                description = "Give your server higher CPU priority.",
                checked = draft.cpuPriorityEnabled,
                onCheckedChange = { onDraftUpdate(draft.copy(cpuPriorityEnabled = it)) }
            )
            SwitchRow(
                icon = Icons.Outlined.Refresh,
                title = "Auto-Restart",
                description = "Automatically restart if it crashes.",
                checked = draft.autoRestartEnabled,
                onCheckedChange = { onDraftUpdate(draft.copy(autoRestartEnabled = it)) }
            )
        }

        WizardInfoBanner(
            text = "Recommended for this device: Balanced · 1024 MB\nThis provides the best balance of performance and stability."
        )
    }
}

@Composable
private fun ProfileCard(
    profile: PerformanceProfile,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(150.dp),
        shape = RoundedCornerShape(WizardTheme.OptionCardRadius),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Color.Transparent else WizardTheme.Border
        )
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = if (selected) {
                        Brush.linearGradient(
                            listOf(WizardTheme.SelectedCardGradientLeft, WizardTheme.SelectedCardGradientRight)
                        )
                    } else {
                        Brush.linearGradient(listOf(Color.White, Color.White))
                    }
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(WizardTheme.GradientLeft, WizardTheme.GradientRight)
                            ),
                            shape = RoundedCornerShape(WizardTheme.OptionCardRadius)
                        )
                    } else Modifier
                )
                .padding(12.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    PerformanceArtwork(
                        profile = profile.label,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        profile.label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp
                        ),
                        color = if (selected) WizardTheme.PrimaryBlue else WizardTheme.PrimaryText
                    )
                    Text(
                        profile.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp, 
                            lineHeight = 13.sp
                        ),
                        color = WizardTheme.SecondaryText,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Surface(
                    color = if (profile == PerformanceProfile.BALANCED) Color(0xFFE8F1FF) else Color(0xFFF1F4F9),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        profile.tag,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (profile == PerformanceProfile.BALANCED) WizardTheme.PrimaryBlue else WizardTheme.SecondaryText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(WizardTheme.SoftBlue, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = WizardTheme.PrimaryBlue
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold))
            Text(description, style = MaterialTheme.typography.bodySmall, color = WizardTheme.SecondaryText, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = WizardTheme.PrimaryBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = WizardTheme.Border
            )
        )
    }
}
