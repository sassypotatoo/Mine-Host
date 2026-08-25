package com.example.ui.screens.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostButton
import com.example.ui.components.PastelIcon
import com.example.ui.components.SettingsRow
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.MineHostTextSecondary
import com.example.ui.theme.PurpleSoft

@Composable
fun ServerSettingsScreen(viewModel: MainViewModel, serverId: String) {
    val saved by viewModel.serverSettings.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val status = runtimeStates[serverId]?.status ?: ServerStatus.STOPPED
    val canEdit = status == ServerStatus.STOPPED || status == ServerStatus.FAILED || status == ServerStatus.CRASHED
    LaunchedEffect(serverId) { viewModel.selectServer(serverId) }
    var draft by remember(saved) { mutableStateOf(saved) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Basic Settings", style = MaterialTheme.typography.titleLarge)
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 15.dp)) {
                EditableTextRow(
                    title = "Server Name",
                    subtitle = "Name shown in MineHost",
                    icon = Icons.Outlined.Dns,
                    value = draft.serverName,
                    onValue = { draft = draft.copy(serverName = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                ChoiceRow(
                    title = "Game Mode",
                    subtitle = "Default player mode",
                    icon = Icons.Outlined.SportsEsports,
                    value = draft.gameMode,
                    choices = listOf("survival", "creative", "adventure"),
                    onValue = { draft = draft.copy(gameMode = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                ChoiceRow(
                    title = "Difficulty",
                    subtitle = "World difficulty",
                    icon = Icons.Outlined.BarChart,
                    value = draft.difficulty,
                    choices = listOf("peaceful", "easy", "normal", "hard"),
                    onValue = { draft = draft.copy(difficulty = it) }
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 15.dp)) {
                SliderRow(
                    title = "Max Players",
                    subtitle = "${draft.maxPlayers} players",
                    icon = Icons.Outlined.Group,
                    value = draft.maxPlayers.toFloat(),
                    range = 1f..100f,
                    onValue = { draft = draft.copy(maxPlayers = it.toInt()) }
                )
                HorizontalDivider(color = MineHostDivider)
                SwitchRow(
                    title = "Whitelist",
                    subtitle = "Only allow whitelisted players",
                    icon = Icons.Outlined.Shield,
                    checked = draft.whitelistEnabled,
                    onChecked = { draft = draft.copy(whitelistEnabled = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                SwitchRow(
                    title = "Online Mode",
                    subtitle = "Verify player identities where supported",
                    icon = Icons.Outlined.VerifiedUser,
                    checked = draft.onlineMode,
                    onChecked = { draft = draft.copy(onlineMode = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                SwitchRow(
                    title = "Auto-Restart",
                    subtitle = "Restart after unexpected process exit",
                    icon = Icons.Outlined.Refresh,
                    checked = draft.autoRestart,
                    onChecked = { draft = draft.copy(autoRestart = it) }
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 15.dp)) {
                SliderRow(
                    title = "Startup Memory",
                    subtitle = "${draft.memoryMb} MB allocated",
                    icon = Icons.Outlined.Memory,
                    value = draft.memoryMb.toFloat(),
                    range = 384f..4096f,
                    onValue = {
                        draft = draft.copy(memoryMb = (it / 64).toInt() * 64)
                    }
                )
                HorizontalDivider(color = MineHostDivider)
                SliderRow(
                    title = "View Distance",
                    subtitle = "${draft.viewDistance} chunks",
                    icon = Icons.Outlined.Visibility,
                    value = draft.viewDistance.toFloat(),
                    range = 2f..24f,
                    onValue = { draft = draft.copy(viewDistance = it.toInt()) }
                )
                HorizontalDivider(color = MineHostDivider)
                EditableTextRow(
                    title = "Server Port",
                    subtitle = "Bedrock UDP port",
                    icon = Icons.Outlined.Router,
                    value = draft.port.toString(),
                    onValue = { value ->
                        value.toIntOrNull()?.let { port -> draft = draft.copy(port = port) }
                    }
                )
                HorizontalDivider(color = MineHostDivider)
                EditableTextRow(
                    title = "World Name",
                    subtitle = "Active level folder",
                    icon = Icons.Outlined.Public,
                    value = draft.levelName,
                    onValue = { draft = draft.copy(levelName = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                SwitchRow(
                    title = "Scheduled Backups",
                    subtitle = "Create automatic local backups",
                    icon = Icons.Outlined.CloudUpload,
                    checked = draft.autoBackup,
                    onChecked = { draft = draft.copy(autoBackup = it) }
                )
            }
        }

        Text("Automation", style = MaterialTheme.typography.titleLarge)
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 15.dp)) {
                AutomationRow(
                    title = "Crash Auto-Restart",
                    subtitle = "Retry up to three times after an unexpected crash",
                    icon = Icons.Outlined.Refresh,
                    checked = draft.autoRestart,
                    onChecked = { draft = draft.copy(autoRestart = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                AutomationRow(
                    title = "Automatic Backup",
                    subtitle = "Create a local snapshot every six hours while MineHost is active",
                    icon = Icons.Outlined.Backup,
                    checked = draft.autoBackup,
                    onChecked = { draft = draft.copy(autoBackup = it) }
                )
                HorizontalDivider(color = MineHostDivider)
                AutomationRow(
                    title = "Cleanup Entities",
                    subtitle = "Run manually from Auto Optimization",
                    icon = Icons.Outlined.CleaningServices,
                    checked = false,
                    enabled = false,
                    onChecked = {}
                )
            }
        }

        if (!canEdit) {
            Text(
                text = "Stop the server before changing settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MineHostTextSecondary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MineHostButton(
                text = "Reset",
                onClick = { draft = saved },
                modifier = Modifier.weight(1f),
                icon = Icons.Outlined.RestartAlt,
                outlined = true,
                enabled = canEdit && !operationInProgress
            )
            MineHostButton(
                text = "Save Changes",
                onClick = { viewModel.saveServerSettings(draft, serverId) },
                modifier = Modifier.weight(1.5f),
                icon = Icons.Outlined.Save,
                enabled = canEdit && !operationInProgress && draft.serverName.isNotBlank()
            )
        }
    }
}

@Composable
private fun EditableTextRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: String,
    onValue: (String) -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    if (editing) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            label = { Text(title) },
            supportingText = { Text(subtitle) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { editing = false }) {
                    Icon(Icons.Outlined.Check, contentDescription = "Done")
                }
            }
        )
    } else {
        SettingsRow(
            title = title,
            subtitle = value.ifBlank { subtitle },
            icon = icon,
            onClick = { editing = true }
        )
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: String,
    choices: List<String>,
    onValue: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        trailing = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(value.replaceFirstChar { it.uppercase() })
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    choices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                onValue(choice)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun SliderRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row {
            PastelIcon(icon, MineHostPurple, PurpleSoft, size = 42.dp)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MineHostBlue,
                activeTrackColor = MineHostBlue
            )
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = MineHostGreen,
        soft = GreenSoft,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(checkedTrackColor = MineHostBlue)
            )
        }
    )
}

@Composable
private fun AutomationRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        accent = MineHostBlue,
        soft = BlueSoft,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = MineHostBlue)
            )
        }
    )
}
