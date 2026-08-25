package com.example.ui.components

import androidx.compose.foundation.clickable
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
fun SettingsRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color = MineHostBlue,
    soft: Color = BlueSoft,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PastelIcon(icon, accent, soft, size = 46.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MineHostTextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
        }
        if (trailing != null) trailing() else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MineHostTextSecondary)
    }
}
