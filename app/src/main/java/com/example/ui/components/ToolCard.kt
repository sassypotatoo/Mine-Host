package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

@Composable
fun ToolCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    soft: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, cornerRadius = 20.dp) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            PastelIcon(icon, accent, soft, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp)); Surface(color = PurpleSoft, shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp)) { Text(badge, color = MineHostPurple, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MineHostTextSecondary)
        }
    }
}
