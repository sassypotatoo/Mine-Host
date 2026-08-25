package com.example.ui.screens.servers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainViewModel
import com.example.server.ServerStatus
import com.example.ui.components.GlassCard
import com.example.ui.theme.ConsoleBackground
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleText
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostGreen
import com.example.ui.theme.MineHostTextSecondary

@Composable
fun ServerConsoleScreen(viewModel: MainViewModel, serverId: String) {
    val logsByServer by viewModel.logsByServer.collectAsState()
    val runtimeStates by viewModel.runtimeStates.collectAsState()
    val logs = logsByServer[serverId].orEmpty()
    val status = runtimeStates[serverId]?.status ?: ServerStatus.STOPPED
    val canSendCommands = status == ServerStatus.ONLINE ||
        status == ServerStatus.WORLD_PROVISIONALLY_LOADED
    val commandHint = if (status == ServerStatus.WORLD_PROVISIONALLY_LOADED) {
        "minehost claim-imported-player <player>"
    } else {
        "Type a command…"
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var command by remember { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val visibleLogs = remember(logs, searchQuery) {
        if (searchQuery.isBlank()) logs else logs.filter {
            it.contains(searchQuery, ignoreCase = true)
        }
    }

    LaunchedEffect(visibleLogs.size) {
        if (visibleLogs.isNotEmpty()) {
            listState.animateScrollToItem(visibleLogs.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Live Console", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.size(8.dp))
                    Box(Modifier.size(8.dp).background(MineHostGreen, CircleShape))
                    Spacer(Modifier.size(5.dp))
                    Text(
                        "Streaming",
                        style = MaterialTheme.typography.bodySmall,
                        color = MineHostTextSecondary
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(logs.joinToString("\n")))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy logs",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    var showLogDialog by remember { mutableStateOf(false) }
                    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
                    ) { uri ->
                        uri?.let { viewModel.performLogExport(it, serverId) }
                    }

                    if (showLogDialog) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showLogDialog = false },
                            title = { Text("Clear and Export Logs") },
                            text = { Text("Choose how you want to manage logs for this server. Exporting creates a redacted diagnostic ZIP for support.") },
                            confirmButton = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            viewModel.clearAndExportLogs(com.example.MainViewModel.LogClearChoice.UI_ONLY, serverId)
                                            showLogDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Clear UI Logs Only") }
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            viewModel.clearAndExportLogs(com.example.MainViewModel.LogClearChoice.UI_AND_FILE, serverId)
                                            showLogDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Clear UI and Real logs.txt") }
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = {
                                            exportLauncher.launch("minehost-logs-${serverId.take(8)}.zip")
                                            showLogDialog = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Export Redacted Log Bundle") }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLogDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    TextButton(onClick = { showLogDialog = true }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(5.dp))
                        Text("Clear & Export...")
                    }
                    IconButton(onClick = { searchVisible = !searchVisible }) {
                        Icon(
                            imageVector = if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search,
                            contentDescription = if (searchVisible) "Close search" else "Search logs"
                        )
                    }
                }

                if (searchVisible) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        placeholder = { Text("Filter console logs…") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(ConsoleBackground)
                        .padding(12.dp)
                ) {
                    if (visibleLogs.isEmpty()) {
                        Text(
                            text = if (logs.isEmpty()) {
                                "[INFO] Console ready. Start the server to stream logs."
                            } else {
                                "No console lines match your search."
                            },
                            color = ConsoleText.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(visibleLogs) { line ->
                                Text(
                                    text = line,
                                    color = consoleColor(line),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (canSendCommands) commandHint else "Start the server to send commands") },
                        enabled = canSendCommands,
                        singleLine = true,
                        shape = RoundedCornerShape(15.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ConsoleText,
                            unfocusedTextColor = ConsoleText,
                            focusedContainerColor = ConsoleSurface,
                            unfocusedContainerColor = ConsoleSurface,
                            focusedBorderColor = MineHostBlue,
                            unfocusedBorderColor = Color(0xFF3A4A5D),
                            focusedPlaceholderColor = Color(0xFF8290A2),
                            unfocusedPlaceholderColor = Color(0xFF8290A2)
                        )
                    )
                    Button(
                        onClick = {
                            viewModel.sendCommand(command, serverId)
                            command = ""
                        },
                        enabled = canSendCommands && command.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MineHostBlue)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Send")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    listOf("list", "say ", "help", "save-all").forEach { quickCommand ->
                        AssistChip(
                            onClick = { command = quickCommand },
                            enabled = canSendCommands,
                            label = { Text("/$quickCommand") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = ConsoleSurface,
                                labelColor = ConsoleText
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun consoleColor(line: String): Color = when {
    line.contains("error", true) || line.contains("exception", true) -> Color(0xFFFF5664)
    line.contains("warn", true) -> Color(0xFFFFB020)
    line.contains("join", true) || line.contains("done", true) -> Color(0xFF42DB7B)
    else -> ConsoleText
}
