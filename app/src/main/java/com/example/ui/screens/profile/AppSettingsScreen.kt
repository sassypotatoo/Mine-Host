package com.example.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostPageTitle
import com.example.ui.components.SectionTitle
import com.example.ui.components.SettingsRow
import com.example.ui.navigation.MineHostDestination
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.GreenSoft
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostPurple
import com.example.ui.theme.PurpleSoft

@Composable
fun AppSettingsScreen(
    viewModel: MainViewModel,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit
) {
    val settings by viewModel.appSettings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MineHostBackgroundTop, MineHostBackgroundBottom)
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MineHostPageTitle(
                title = "App settings",
                subtitle = "Customize how MineHost looks and behaves."
            )

            SectionTitle("Appearance")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ChoiceSetting(
                        title = "Theme",
                        subtitle = "MineHost currently uses the approved light theme",
                        icon = Icons.Outlined.Palette,
                        value = "Light",
                        choices = listOf("Light"),
                        onValue = {}
                    )
                    HorizontalDivider(color = MineHostDivider)
                    ChoiceSetting(
                        title = "Language",
                        subtitle = "Select the app language",
                        icon = Icons.Outlined.Language,
                        value = settings.language,
                        choices = listOf("English"),
                        onValue = {
                            viewModel.updateAppSettings(settings.copy(language = it))
                        }
                    )
                }
            }

            SectionTitle("Experience")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    SettingsRow(
                        title = "Storage & cache",
                        subtitle = "Server data is managed in internal app storage",
                        icon = Icons.Outlined.Storage,
                        accent = MineHostGreen,
                        soft = GreenSoft
                    )
                    HorizontalDivider(color = MineHostDivider)
                    SettingsRow(
                        title = "Download behavior",
                        subtitle = "Network downloads follow existing runtime rules",
                        icon = Icons.Outlined.Download,
                        accent = MineHostBlue,
                        soft = BlueSoft
                    )
                    HorizontalDivider(color = MineHostDivider)
                    ChoiceSetting(
                        title = "Startup screen",
                        subtitle = "Choose what opens at app launch",
                        icon = Icons.Outlined.Home,
                        value = settings.startupScreen,
                        choices = listOf("Dashboard", "Servers"),
                        onValue = {
                            viewModel.updateAppSettings(settings.copy(startupScreen = it))
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    ToggleSetting(
                        title = "Automatic engine updates",
                        subtitle = "Check for newly published engine builds automatically",
                        icon = Icons.Outlined.Download,
                        checked = settings.automaticEngineUpdateChecks,
                        onChecked = {
                            viewModel.updateAppSettings(
                                settings.copy(automaticEngineUpdateChecks = it)
                            )
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    ToggleSetting(
                        title = "Data saver mode",
                        subtitle = "Reserved for future optional catalog traffic",
                        icon = Icons.Outlined.Eco,
                        checked = false,
                        enabled = false,
                        onChecked = {}
                    )
                }
            }

            SectionTitle("Feedback & advanced")
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ToggleSetting(
                        title = "Haptic feedback",
                        subtitle = "Will be enabled when app-wide haptics are wired",
                        icon = Icons.Outlined.Vibration,
                        checked = false,
                        enabled = false,
                        onChecked = {}
                    )
                    HorizontalDivider(color = MineHostDivider)
                    ToggleSetting(
                        title = "Confirm destructive actions",
                        subtitle = "Ask before deleting or restoring data",
                        icon = Icons.Outlined.GppGood,
                        checked = settings.confirmDestructiveActions,
                        onChecked = {
                            viewModel.updateAppSettings(
                                settings.copy(confirmDestructiveActions = it)
                            )
                        }
                    )
                    HorizontalDivider(color = MineHostDivider)
                    SettingsRow(
                        title = "Developer information",
                        subtitle = "Build diagnostics and local runtime details",
                        icon = Icons.Outlined.Code,
                        accent = MineHostPurple,
                        soft = PurpleSoft
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun ToggleSetting(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true
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

@Composable
private fun ChoiceSetting(
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
        accent = MineHostPurple,
        soft = PurpleSoft,
        trailing = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(value)
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    choices.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice) },
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
