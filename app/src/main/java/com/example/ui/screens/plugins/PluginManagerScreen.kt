package com.example.ui.screens.plugins

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.data.PluginEntry
import com.example.server.ServerStatus
import com.example.ui.components.*
import com.example.ui.theme.*

@Composable
fun PluginManagerScreen(
    viewModel: MainViewModel,
    onNavigateToMarketplace: () -> Unit,
    serverId: String? = null,
) {
    val plugins by viewModel.plugins.collectAsState()
    val operation by viewModel.operationInProgress.collectAsState()
    val selectedServerId by viewModel.selectedServerId.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val ownerId = serverId ?: selectedServerId
    val status = ownerId?.let { runtimeStates[it]?.status } ?: ServerStatus.STOPPED
    val canModifyPlugins = status != ServerStatus.ONLINE && status != ServerStatus.STARTING && status != ServerStatus.PREPARING && status != ServerStatus.DOWNLOADING
    var selected by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { viewModel.importPlugin(it, ownerId) } }
    LaunchedEffect(ownerId) {
        ownerId?.let {
            if (serverId != null) viewModel.selectServer(it)
            viewModel.refreshPlugins(it)
        }
    }
    val visible = plugins.filter { it.name.contains(query, true) && when(selected){0->it.enabled;1->true;2->!it.enabled;else->true} }

    MineHostScreen(scrollable = false, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
        MineHostBrandHeader()
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            MineHostPageTitle("Plugin Manager", "Manage plugins installed in the current Bedrock server.", Modifier.align(Alignment.CenterStart))
            PastelIcon(Icons.Outlined.Extension, MineHostPurple, PurpleSoft, Modifier.align(Alignment.CenterEnd), 92.dp)
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Overview", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.SpaceAround, modifier = Modifier.fillMaxWidth()) {
                    PluginStat(Icons.Outlined.Extension, plugins.size.toString(), "Total Plugins", MineHostBlue, BlueSoft)
                    PluginStat(Icons.Outlined.CheckCircle, plugins.count { it.enabled }.toString(), "Enabled", MineHostGreen, GreenSoft)
                    PluginStat(Icons.Outlined.Block, plugins.count { !it.enabled }.toString(), "Disabled", MineHostOrange, OrangeSoft)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Installed ${plugins.count{it.enabled}}", "All ${plugins.size}", "Disabled ${plugins.count{!it.enabled}}").forEachIndexed { index,label ->
                FilterChip(selected==index,{selected=index},{Text(label)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=MineHostBlue,selectedLabelColor=Color.White))
            }
            Spacer(Modifier.weight(1f))
            SoftIconButton(Icons.Outlined.Add, "Import plugin", { launcher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*")) })
        }
        OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),placeholder={Text("Search installed plugins…")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,shape=RoundedCornerShape(18.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedContainerColor=Color.White.copy(alpha=.65f),focusedContainerColor=Color.White))
        if (plugins.isEmpty()) {
            GlassCard(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState("No plugins installed", "Import a compatible Nukkit/PowerNukkit .jar file, or browse the future marketplace catalog.", icon=Icons.Outlined.Extension, modifier=Modifier.fillMaxSize())
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(9.dp), contentPadding=PaddingValues(bottom=8.dp)) {
                items(visible,key={it.fileName}) { plugin ->
                    PluginRow(plugin, operation, canModifyPlugins) { enable -> viewModel.togglePlugin(plugin, enable, ownerId) }
                }
                item { MineHostButton("Browse Marketplace",onNavigateToMarketplace,Modifier.fillMaxWidth(),icon=Icons.Outlined.Storefront,outlined=true) }
            }
        }
    }
}

@Composable private fun PluginStat(icon:androidx.compose.ui.graphics.vector.ImageVector,value:String,label:String,accent:Color,soft:Color){Column(horizontalAlignment=Alignment.CenterHorizontally){PastelIcon(icon,accent,soft,size=52.dp);Spacer(Modifier.height(6.dp));Text(value,style=MaterialTheme.typography.headlineSmall);Text(label,style=MaterialTheme.typography.bodySmall,color=MineHostTextSecondary)}}
@Composable
private fun PluginRow(
    plugin: PluginEntry,
    busy: Boolean,
    canModify: Boolean,
    onToggle: (Boolean) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PastelIcon(
                icon = Icons.Outlined.Extension,
                tint = if (plugin.enabled) MineHostGreen else MineHostTextSecondary,
                background = if (plugin.enabled) GreenSoft else MineHostSurfaceVariant,
                size = 54.dp
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(
                        text = if (plugin.enabled) "Enabled" else "Disabled",
                        color = if (plugin.enabled) MineHostGreen else MineHostTextSecondary,
                        background = if (plugin.enabled) GreenSoft else MineHostSurfaceVariant,
                        showDot = false
                    )
                }
                Text(
                    plugin.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary
                )
                Text(
                    if (canModify) formatSize(plugin.sizeBytes) else "${formatSize(plugin.sizeBytes)} • stop server to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canModify) MineHostBlue else MineHostOrange
                )
            }
            Switch(
                checked = plugin.enabled,
                onCheckedChange = onToggle,
                enabled = !busy && canModify,
                colors = SwitchDefaults.colors(checkedTrackColor = MineHostGreen)
            )
        }
    }
}

private fun formatSize(b:Long)=if(b>=1_048_576)"%.1f MB".format(b/1_048_576f) else "${b/1024} KB"
