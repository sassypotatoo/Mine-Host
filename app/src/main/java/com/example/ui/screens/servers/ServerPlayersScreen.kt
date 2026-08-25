package com.example.ui.screens.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GroupOff
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.MainViewModel
import com.example.server.players.BannedPlayerEntry
import com.example.server.players.ServerAccessListState
import com.example.ui.components.EmptyState
import com.example.ui.components.GlassCard
import com.example.ui.theme.BlueSoft
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostDivider
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostRed
import com.example.ui.theme.MineHostTextPrimary
import com.example.ui.theme.MineHostTextSecondary

@Composable
fun ServerPlayersScreen(viewModel: MainViewModel, serverId: String) {
    val playersByServer by viewModel.playersByServer.collectAsState()
    val accessListsByServer by viewModel.playerAccessListsByServer.collectAsState()
    val players = playersByServer[serverId].orEmpty()
    val accessState = accessListsByServer[serverId] ?: ServerAccessListState()
    var selectedTab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(serverId) {
        viewModel.refreshPlayerAccessLists(serverId)
    }
    LaunchedEffect(serverId, selectedTab) {
        if (selectedTab == 2 || selectedTab == 3) {
            viewModel.refreshPlayerAccessLists(serverId)
        }
    }

    val labels = listOf(
        "Online ${players.size}",
        "Requests",
        "Whitelist ${accessState.whitelist.size}",
        "Banned ${accessState.bannedPlayers.size}",
    )
    val filteredPlayers = players.filter { it.name.contains(query, ignoreCase = true) }
    val filteredWhitelist = accessState.whitelist.filter { it.contains(query, ignoreCase = true) }
    val filteredBans = accessState.bannedPlayers.filter {
        it.name.contains(query, ignoreCase = true) || it.reason.orEmpty().contains(query, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                labels.forEachIndexed { index, label ->
                    val active = selectedTab == index
                    Surface(
                        onClick = {
                            selectedTab = index
                            query = ""
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (active) MineHostBlue else Color.Transparent,
                    ) {
                        Text(
                            text = label,
                            color = if (active) Color.White else MineHostTextPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 11.dp),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search players…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (selectedTab == 2 || selectedTab == 3) {
                    IconButton(
                        onClick = { viewModel.refreshPlayerAccessLists(serverId) },
                        enabled = !accessState.loading,
                    ) {
                        if (accessState.loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh real server list")
                        }
                    }
                } else {
                    Icon(Icons.Outlined.Tune, contentDescription = null)
                }
            },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color.White.copy(alpha = 0.7f),
                focusedContainerColor = Color.White,
            ),
        )

        GlassCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (selectedTab) {
                0 -> OnlinePlayersTab(
                    players = filteredPlayers.map { it.name },
                    viewModel = viewModel,
                    serverId = serverId,
                )
                1 -> EmptyState(
                    title = "No join requests",
                    message = "Join requests are not exposed consistently by the supported engines. This tab does not invent them.",
                    icon = Icons.Outlined.PersonAdd,
                    modifier = Modifier.fillMaxSize(),
                )
                2 -> WhitelistTab(
                    state = accessState,
                    names = filteredWhitelist,
                    query = query,
                    onRemove = { viewModel.removeWhitelistedPlayer(it, serverId) },
                )
                else -> BannedPlayersTab(
                    state = accessState,
                    entries = filteredBans,
                    query = query,
                    onUnban = { viewModel.unbanPlayer(it, serverId) },
                )
            }
        }
    }
}

@Composable
private fun OnlinePlayersTab(
    players: List<String>,
    viewModel: MainViewModel,
    serverId: String,
) {
    if (players.isEmpty()) {
        EmptyState(
            title = "No online players",
            message = "Players confirmed by the real engine list and join/leave output will appear here.",
            icon = Icons.Outlined.GroupOff,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        item {
            Text("Online Players (${players.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
        }
        items(players, key = { it.lowercase() }) { name ->
            PlayerRow(name = name, viewModel = viewModel, serverId = serverId)
            HorizontalDivider(color = MineHostDivider)
        }
    }
}

@Composable
private fun WhitelistTab(
    state: ServerAccessListState,
    names: List<String>,
    query: String,
    onRemove: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AccessListError(state.error)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && !state.whitelistFilePresent ->
                    LoadingAccessList("Reading the real server whitelist…")
                !state.whitelistFilePresent -> EmptyState(
                    title = "Whitelist file not created yet",
                    message = "Start this server and use its whitelist command. MineHost will read white-list.txt after the engine creates it.",
                    icon = Icons.Outlined.VerifiedUser,
                    modifier = Modifier.fillMaxSize(),
                )
                names.isEmpty() -> EmptyState(
                    title = if (query.isBlank()) "Whitelist is empty" else "No matching whitelist entries",
                    message = "Only names stored by the real server are displayed.",
                    icon = Icons.Outlined.VerifiedUser,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    item {
                        Text("Whitelisted Players (${names.size})", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                    }
                    items(names, key = { it.lowercase() }) { name ->
                        AccessListRow(
                            name = name,
                            detail = "Stored in this server's whitelist",
                            actionLabel = "Remove",
                            actionIcon = Icons.Outlined.PersonRemove,
                            onAction = { onRemove(name) },
                        )
                        HorizontalDivider(color = MineHostDivider)
                    }
                }
            }
        }
    }
}

@Composable
private fun BannedPlayersTab(
    state: ServerAccessListState,
    entries: List<BannedPlayerEntry>,
    query: String,
    onUnban: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        AccessListError(state.error)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading && !state.bannedPlayersFilePresent ->
                    LoadingAccessList("Reading the real server ban list…")
                !state.bannedPlayersFilePresent -> EmptyState(
                    title = "Ban list file not created yet",
                    message = "MineHost will read banned-players.json after the selected engine creates it.",
                    icon = Icons.Outlined.Block,
                    modifier = Modifier.fillMaxSize(),
                )
                entries.isEmpty() -> EmptyState(
                    title = if (query.isBlank()) "Ban list is empty" else "No matching banned players",
                    message = "Only bans persisted by the real server are displayed.",
                    icon = Icons.Outlined.Block,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
                    item {
                        Text("Banned Players (${entries.size})", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                    }
                    items(entries, key = { it.name.lowercase() }) { entry ->
                        val details = buildList {
                            entry.reason?.let { add("Reason: $it") }
                            entry.source?.let { add("Source: $it") }
                            entry.expires?.let { add("Expires: $it") }
                        }.joinToString(" • ").ifBlank { "Stored in this server's name-ban list" }
                        AccessListRow(
                            name = entry.name,
                            detail = details,
                            actionLabel = "Unban",
                            actionIcon = Icons.Outlined.LockOpen,
                            onAction = { onUnban(entry.name) },
                        )
                        HorizontalDivider(color = MineHostDivider)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessListError(error: String?) {
    if (error.isNullOrBlank()) return
    Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun LoadingAccessList(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(12.dp))
        Text(message, color = MineHostTextSecondary)
    }
}

@Composable
private fun AccessListRow(
    name: String,
    detail: String,
    actionLabel: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).background(BlueSoft, RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = MineHostBlue)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
        }
        TextButton(onClick = onAction) {
            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(5.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun PlayerRow(
    name: String,
    viewModel: MainViewModel,
    serverId: String,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(50.dp).background(BlueSoft, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null, tint = MineHostBlue)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(MineHostGreen, CircleShape))
                Spacer(Modifier.size(5.dp))
                Text(
                    "Online • confirmed from server output",
                    style = MaterialTheme.typography.bodySmall,
                    color = MineHostTextSecondary,
                )
            }
        }
        IconButton(onClick = { viewModel.messagePlayer(name, "Hello from MineHost", serverId) }) {
            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = "Message", tint = MineHostBlue)
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Actions")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Whitelist") },
                    onClick = {
                        viewModel.whitelistPlayer(name, serverId)
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Kick") },
                    onClick = {
                        viewModel.kickPlayer(name, serverId)
                        menuExpanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Ban", color = MineHostRed) },
                    onClick = {
                        viewModel.banPlayer(name, serverId)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}
