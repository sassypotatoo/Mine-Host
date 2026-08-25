package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.server.ServerStatus
import com.example.ui.theme.*

@Composable
fun ServerDetailHeader(
    serverName: String,
    status: ServerStatus,
    engine: String,
    address: String,
    modifier: Modifier = Modifier,
    customIconPath: String? = null,
    activeWorldPath: String? = null
) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerWorldArtwork(
                customIconPath = customIconPath,
                activeWorldPath = activeWorldPath,
                modifier = Modifier.size(76.dp),
                corner = 18.dp
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = serverName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MineHostTextPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    StatusBadge(status)
                }
                Text(engine, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
                Text(address, style = MaterialTheme.typography.bodySmall, color = MineHostBlue, maxLines = 1)
            }
        }
    }
}
