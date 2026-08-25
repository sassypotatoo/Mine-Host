package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.server.ServerStatus
import com.example.ui.theme.*

@Composable
fun ServerSummaryCard(
    name: String,
    status: ServerStatus,
    engine: String,
    memory: String,
    players: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    customIconPath: String? = null,
    activeWorldPath: String? = null,
    featured: Boolean = false
) {
    GlassCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(if (featured) 16.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerWorldArtwork(
                customIconPath = customIconPath,
                activeWorldPath = activeWorldPath,
                modifier = Modifier.size(if (featured) 126.dp else 80.dp),
                corner = 18.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = if (featured) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        color = MineHostTextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    StatusBadge(status)
                }
                Text(engine, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Group, null, Modifier.size(16.dp), MineHostTextSecondary); Spacer(Modifier.width(4.dp)); Text(players, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Memory, null, Modifier.size(16.dp), MineHostPurple); Spacer(Modifier.width(4.dp)); Text(memory, style = MaterialTheme.typography.bodySmall) }
                }
                Text("Tap to manage this server", style = MaterialTheme.typography.labelSmall, color = MineHostTextSecondary)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Open", tint = MineHostTextSecondary)
        }
    }
}
