package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.*

@Composable
fun MineHostScreen(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val safeContentPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding(),
        start = contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    )
    val base = modifier
        .fillMaxSize()
        .background(Brush.verticalGradient(listOf(MineHostBackgroundTop, MineHostBackgroundBottom)))
        .padding(safeContentPadding)
    Column(
        modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun MineHostBrandHeader(
    modifier: Modifier = Modifier,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onProfile: () -> Unit = {},
    compact: Boolean = false,
    showProfile: Boolean = true,
    showNotifications: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 32.dp else 56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            SoftIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack, 
                contentDescription = "Back", 
                onClick = onBack,
                size = if (compact) 36.dp else 40.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        
        Spacer(Modifier.weight(1f))
        
        if (showNotifications) {
            SoftIconButton(
                icon = Icons.Outlined.Notifications,
                contentDescription = "Notifications",
                onClick = onNotifications,
                showDot = true,
                size = if (compact) 36.dp else 40.dp
            )
        }
        
        if (showProfile) {
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            
            Box(
                modifier = Modifier
                    .size(if (compact) 36.dp else 40.dp)
                    .clip(CircleShape)
                    .background(MineHostOutline.copy(alpha = 0.1f))
                    .clickable { onProfile() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = "Profile",
                    tint = MineHostBlue,
                    modifier = Modifier.fillMaxSize(0.8f)
                )
            }
        }
    }
}

@Composable
fun SoftIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MineHostTextPrimary,
    showDot: Boolean = false,
    size: Dp = 40.dp
) {
    Box(modifier) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MineHostOutline.copy(alpha = 0.1f)),
            modifier = Modifier.size(size)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
            }
        }
        if (showDot) Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-2).dp, y = 2.dp)
                .size(8.dp)
                .background(MineHostBlue, CircleShape)
                .border(1.5.dp, Color.White, CircleShape)
        )
    }
}

@Composable
fun MineHostPageTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MineHostTextPrimary)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MineHostTextSecondary)
        }
    }
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MineHostTextPrimary)
        Spacer(Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, contentPadding = PaddingValues(4.dp)) {
            Text(action, color = MineHostBlue, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun PastelIcon(
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(size / 3f)).background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * .52f))
    }
}

@Composable
fun MiniLineChart(
    color: Color,
    modifier: Modifier = Modifier,
    values: List<Float> = listOf(.35f, .55f, .42f, .66f, .43f, .58f, .47f)
) {
    androidx.compose.foundation.Canvas(modifier = modifier.height(26.dp).fillMaxWidth()) {
        if (values.size < 2) return@Canvas
        val step = size.width / (values.lastIndex)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height * (1f - value.coerceIn(0f, 1f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun ServerThumbnail(
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MineHostOutline.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Image,
            contentDescription = "Server world",
            tint = MineHostBlue.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxSize(0.5f)
        )
    }
}

@Composable
fun ValueOrDash(value: String?, modifier: Modifier = Modifier) {
    Text(value?.takeIf { it.isNotBlank() } ?: "—", modifier, style = MaterialTheme.typography.headlineSmall, color = MineHostTextPrimary)
}

@Composable
fun RowLabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MineHostTextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MineHostTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
