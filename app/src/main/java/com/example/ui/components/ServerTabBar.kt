package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

enum class ServerTab(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Outlined.Dashboard),
    Console("Console", Icons.Outlined.Terminal),
    Players("Players", Icons.Outlined.Group),
    Settings("Settings", Icons.Outlined.Settings),
    More("More", Icons.Outlined.MoreHoriz)
}

@Composable
fun ServerTabBar(selected: ServerTab, onSelected: (ServerTab) -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth(), cornerRadius = 20.dp) {
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            ServerTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelected(tab) }.padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(tab.icon, tab.label, tint = if (active) MineHostBlue else MineHostTextSecondary, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = if (active) MineHostBlue else MineHostTextSecondary, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth(.6f).height(2.dp).background(if (active) MineHostBlue else Color.Transparent))
                }
            }
        }
    }
}
