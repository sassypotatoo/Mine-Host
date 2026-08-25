package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.navigation.MineHostDestination
import com.example.ui.theme.*

@Composable
fun MineHostBottomBar(
    currentRoute: String?,
    onNavigate: (MineHostDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MineHostSurface,
        tonalElevation = 0.dp
    ) {
        Column {
            HorizontalDivider(color = MineHostDivider, thickness = 0.5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MineHostTheme.bottomBarHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val destinations = listOf(
                    NavigationItemData("Dashboard", Icons.Outlined.GridView, Icons.Filled.GridView, MineHostDestination.Dashboard),
                    NavigationItemData("Console", Icons.Outlined.Terminal, Icons.Filled.Terminal, MineHostDestination.Console),
                    NavigationItemData("Marketplace", Icons.Outlined.Storefront, Icons.Filled.Storefront, MineHostDestination.Marketplace),
                    NavigationItemData("AI Assistant", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome, MineHostDestination.AiAssistantTab)
                )

                destinations.forEach { item ->
                    val isSelected = currentRoute == item.destination.route
                    MineHostNavigationItem(
                        label = item.label,
                        inactiveIcon = item.inactiveIcon,
                        activeIcon = item.activeIcon,
                        isSelected = isSelected,
                        onClick = { onNavigate(item.destination) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

private data class NavigationItemData(
    val label: String,
    val inactiveIcon: ImageVector,
    val activeIcon: ImageVector,
    val destination: MineHostDestination
)

@Composable
private fun MineHostNavigationItem(
    label: String,
    inactiveIcon: ImageVector,
    activeIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = if (isSelected) MineHostBlue else MineHostTextSecondary
    val icon = if (isSelected) activeIcon else inactiveIcon
    val fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(24.dp),
            tint = color
        )
        Text(
            text = label,
            color = color,
            fontSize = 10.sp,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}
