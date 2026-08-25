package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*

@Composable
fun MetricCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MineHostBlue,
    softColor: Color = BlueSoft,
    supporting: String? = null,
    progress: Float? = null,
    showChart: Boolean = false
) {
    GlassCard(modifier = modifier, cornerRadius = 20.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(39.dp).background(softColor, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MineHostTextPrimary, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 2)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
            if (showChart) MiniLineChart(accent, Modifier.fillMaxWidth())
            if (progress != null) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(MineHostDivider, RoundedCornerShape(99.dp))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(accent, RoundedCornerShape(99.dp)))
                }
            }
        }
    }
}
