package com.example.ui.screens.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.marketplace.MarketplaceCategory
import com.example.marketplace.MarketplaceInstaller
import com.example.marketplace.MarketplaceItem
import com.example.plugins.CompatibilityState
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostTextSecondary

private val supportedMarketplaceEngines = listOf(
    "bedrock_power_nukkit" to "PowerNukkit",
    "bedrock_power_nukkit_x" to "PowerNukkitX",
    "bedrock_nukkit" to "PM1E",
    "nukkit-mot" to "Nukkit-MOT",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val catalog by viewModel.marketplaceCatalog.collectAsState()
    val error by viewModel.marketplaceError.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val operationInProgress by viewModel.operationInProgress.collectAsState()
    val selectedProfileId by viewModel.selectedServerId.collectAsState()
    var chosenServerId by remember { mutableStateOf<String?>(null) }
    var serverMenuExpanded by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<MarketplaceCategory?>(null) }

    LaunchedEffect(profiles, selectedProfileId) {
        val valid = profiles.any { it.id == chosenServerId }
        if (!valid) chosenServerId = selectedProfileId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.firstOrNull()?.id
    }

    val selectedProfile = profiles.firstOrNull { it.id == chosenServerId }
    val visibleItems = catalog?.items.orEmpty()
        .filter { it.enabled }
        .filter { selectedCategory == null || it.category == selectedCategory }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MineHostBackgroundTop, MineHostBackgroundBottom)))
            .padding(horizontal = 16.dp),
    ) {
        MineHostBrandHeader(compact = true, showBack = true, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MineHostButton(
                    text = "Reload catalog",
                    icon = Icons.Outlined.Refresh,
                    onClick = viewModel::refreshMarketplaceCatalog,
                    enabled = !operationInProgress,
                )
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verified marketplace", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        catalog?.let { "Catalog revision ${it.revision} • ${it.items.size} real item(s)" }
                            ?: "No verified catalog loaded",
                        color = MineHostTextSecondary,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }

            ExposedDropdownMenuBox(
                expanded = serverMenuExpanded,
                onExpandedChange = { serverMenuExpanded = !serverMenuExpanded },
            ) {
                OutlinedTextField(
                    value = selectedProfile?.name ?: "Select exact server",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Install into server") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(serverMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = serverMenuExpanded,
                    onDismissRequest = { serverMenuExpanded = false },
                ) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text("${profile.name} • ${profile.engineId} • ${profile.id.take(8)}") },
                            onClick = {
                                chosenServerId = profile.id
                                serverMenuExpanded = false
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MineHostButton(
                    text = "All",
                    onClick = { selectedCategory = null },
                    outlined = selectedCategory != null,
                    modifier = Modifier.weight(1f),
                )
                MineHostButton(
                    text = "Plugins",
                    onClick = { selectedCategory = MarketplaceCategory.PLUGIN },
                    outlined = selectedCategory != MarketplaceCategory.PLUGIN,
                    modifier = Modifier.weight(1f),
                )
            }

            if (catalog != null && visibleItems.isEmpty()) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No verified items available", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "MineHost will not invent plugins, templates, checksums, licences, or download links. Install a signed catalog before using the marketplace.",
                            color = MineHostTextSecondary,
                        )
                    }
                }
            }

            visibleItems.forEach { item ->
                MarketplaceItemCard(
                    item = item,
                    selectedEngineId = selectedProfile?.engineId,
                    enabled = selectedProfile != null && !operationInProgress,
                    onInstall = {
                        viewModel.installMarketplaceItem(
                            item = item,
                            worldChoice = MarketplaceInstaller.WorldChoice.IMPORT_AS_ANOTHER,
                            serverId = chosenServerId,
                        )
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketplaceItemCard(
    item: MarketplaceItem,
    selectedEngineId: String?,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${item.category.label()} • ${item.version} • ${item.author}", color = MineHostTextSecondary)
                }
                androidx.compose.material3.Icon(Icons.Outlined.Storefront, contentDescription = null)
            }
            Text(item.description.ifBlank { "No description supplied by the verified catalog." })
            Text("License: ${item.license}", style = MaterialTheme.typography.bodySmall)
            supportedMarketplaceEngines.forEach { (engineId, displayName) ->
                val state = item.compatibility[engineId]?.state ?: CompatibilityState.UNKNOWN
                Text("$displayName: ${state.name.lowercase().replaceFirstChar { it.titlecase() }}")
            }
            val selectedState = selectedEngineId?.let { item.compatibility[it]?.state } ?: CompatibilityState.UNKNOWN
            MineHostButton(
                text = when {
                    selectedEngineId == null -> "Select a server"
                    selectedState == CompatibilityState.UNSUPPORTED -> "Unsupported for selected server"
                    selectedState == CompatibilityState.UNKNOWN -> "Compatibility unknown"
                    !item.enabled -> "Unavailable"
                    else -> if (item.category == MarketplaceCategory.SEED) "Apply seed" else "Install and verify"
                },
                onClick = onInstall,
                enabled = enabled && item.enabled &&
                    selectedState != CompatibilityState.UNSUPPORTED &&
                    selectedState != CompatibilityState.UNKNOWN,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun MarketplaceCategory.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }
