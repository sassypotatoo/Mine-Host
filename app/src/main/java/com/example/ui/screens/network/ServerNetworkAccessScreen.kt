package com.example.ui.screens.network

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.example.auth.AuthState
import com.example.friends.ServerRole
import com.example.tunnel.TunnelStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostButton
import com.example.ui.components.MineHostPageTitle
import com.example.ui.theme.MineHostBackgroundBottom
import com.example.ui.theme.MineHostBackgroundTop
import com.example.ui.theme.MineHostTextSecondary

@Composable
fun ServerNetworkAccessScreen(
    viewModel: MainViewModel,
    serverId: String,
    onBack: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == serverId }
    val tunnelStatuses by viewModel.tunnelStatusByServer.collectAsState()
    val tunnelHealthByServer by viewModel.tunnelHealthByServer.collectAsState()
    val tunnelStatus = tunnelStatuses[serverId] ?: TunnelStatus.STOPPED
    val tunnelHealth = tunnelHealthByServer[serverId]
    val authState by viewModel.authState.collectAsState()
    val membersByServer by viewModel.membersByServer.collectAsState()
    val invitationsByServer by viewModel.invitationsByServer.collectAsState()
    val members = membersByServer[serverId].orEmpty()
    val invitations = invitationsByServer[serverId].orEmpty()

    var frpsHost by remember { mutableStateOf("") }
    var frpsPort by remember { mutableStateOf("7000") }
    var remotePort by remember(profile?.port) { mutableStateOf((profile?.port ?: 19132).toString()) }
    var token by remember { mutableStateOf("") }
    var checksum by remember { mutableStateOf("") }
    var remoteAccessEnabled by remember(serverId) { mutableStateOf(viewModel.isRemoteAccessEnabled(serverId)) }
    var inviteEmail by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf(ServerRole.VIEWER) }
    var roleMenu by remember { mutableStateOf(false) }

    val binaryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.installTunnelBinary(uri, checksum, serverId)
    }

    LaunchedEffect(serverId, authState) {
        viewModel.selectServer(serverId)
        if (authState is AuthState.SignedIn) viewModel.refreshFriendAccess(serverId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MineHostBackgroundTop, MineHostBackgroundBottom)))
            .padding(horizontal = 16.dp),
    ) {
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MineHostPageTitle("Network & Friends", "Configure the exact server UUID. Nothing is simulated.")

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("FRP UDP tunnel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Status: ${tunnelStatus.name}")
                    tunnelHealth?.publicAddress?.takeIf(String::isNotBlank)?.let { Text("Public address: $it") }
                    tunnelHealth?.latencyMs?.let { Text("Relay latency: ${it} ms") }
                    tunnelHealth?.lastError?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }

                    OutlinedTextField(frpsHost, { frpsHost = it }, label = { Text("FRPS host") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(frpsPort, { frpsPort = it.filter(Char::isDigit) }, label = { Text("FRPS port") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(remotePort, { remotePort = it.filter(Char::isDigit) }, label = { Text("Public UDP port") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(token, { token = it }, label = { Text("FRPS token") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(checksum, { checksum = it.trim() }, label = { Text("FRPC publisher SHA-256") }, modifier = Modifier.fillMaxWidth())

                    MineHostButton(
                        text = "Install verified FRPC binary",
                        icon = Icons.Outlined.CloudUpload,
                        onClick = { binaryPicker.launch(arrayOf("application/octet-stream", "application/x-executable")) },
                        enabled = checksum.matches(Regex("[a-fA-F0-9]{64}")),
                        modifier = Modifier.fillMaxWidth(),
                        outlined = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MineHostButton(
                            text = "Start tunnel",
                            icon = Icons.Outlined.Link,
                            onClick = {
                                viewModel.startTunnel(
                                    frpsHost = frpsHost,
                                    frpsPort = frpsPort.toIntOrNull() ?: 0,
                                    remoteUdpPort = remotePort.toIntOrNull() ?: 0,
                                    token = token,
                                    serverId = serverId,
                                )
                            },
                            enabled = tunnelStatus == TunnelStatus.STOPPED || tunnelStatus == TunnelStatus.ERROR,
                            modifier = Modifier.weight(1f),
                        )
                        MineHostButton(
                            text = "Stop",
                            icon = Icons.Outlined.StopCircle,
                            onClick = { viewModel.stopTunnel(serverId) },
                            enabled = tunnelStatus != TunnelStatus.STOPPED,
                            modifier = Modifier.weight(1f),
                            outlined = true,
                        )
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Friend access", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    when (authState) {
                        is AuthState.SignedIn -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("Remote access")
                                    Text("Every action is checked by UUID, role and RLS.", color = MineHostTextSecondary)
                                }
                                Switch(
                                    checked = remoteAccessEnabled,
                                    onCheckedChange = {
                                        remoteAccessEnabled = it
                                        viewModel.setRemoteAccessEnabled(serverId, it)
                                    },
                                )
                            }
                            OutlinedTextField(inviteEmail, { inviteEmail = it }, label = { Text("Friend Google email") }, modifier = Modifier.fillMaxWidth())
                            Column {
                                MineHostButton(
                                    text = "Role: ${inviteRole.name}",
                                    onClick = { roleMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    outlined = true,
                                )
                                DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                                    listOf(ServerRole.ADMIN, ServerRole.OPERATOR, ServerRole.VIEWER).forEach { role ->
                                        DropdownMenuItem(text = { Text(role.name) }, onClick = { inviteRole = role; roleMenu = false })
                                    }
                                }
                            }
                            MineHostButton(
                                text = "Invite friend",
                                icon = Icons.Outlined.GroupAdd,
                                onClick = { viewModel.inviteFriend(serverId, inviteEmail, inviteRole) },
                                enabled = remoteAccessEnabled && inviteEmail.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            MineHostButton(
                                text = "Refresh members",
                                icon = Icons.Outlined.Refresh,
                                onClick = { viewModel.refreshFriendAccess(serverId) },
                                modifier = Modifier.fillMaxWidth(),
                                outlined = true,
                            )
                            Text("Members (${members.size})", fontWeight = FontWeight.Bold)
                            members.forEach { member ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${member.userId.take(8)} • ${member.role.name}", modifier = Modifier.weight(1f))
                                    if (member.role != ServerRole.OWNER) {
                                        MineHostButton(
                                            text = "Remove",
                                            onClick = { viewModel.removeFriendAccess(serverId, member.userId) },
                                            outlined = true,
                                        )
                                    }
                                }
                            }
                            Text("Invitations (${invitations.size})", fontWeight = FontWeight.Bold)
                            invitations.forEach { invitation ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${invitation.invitedEmail} • ${invitation.role.name} • ${invitation.status}", modifier = Modifier.weight(1f))
                                    if (invitation.status == "pending") {
                                        MineHostButton(
                                            text = "Revoke",
                                            onClick = { viewModel.revokeInvitation(serverId, invitation.id) },
                                            outlined = true,
                                        )
                                    }
                                }
                            }
                        }
                        is AuthState.NotConfigured -> Text("Supabase/Google login is not configured. Friend access remains disabled.", color = MineHostTextSecondary)
                        else -> Text("Sign in from Account before enabling friend access.", color = MineHostTextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
