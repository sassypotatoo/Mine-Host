package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MineHostGlass
import com.example.ui.theme.MineHostOutline
import com.example.ui.theme.MineHostTheme

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = MineHostTheme.cardCornerRadius,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 7.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x1D6387AD),
                spotColor = Color(0x176387AD)
            )
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    listOf(MineHostGlass, Color(0xDDF9FCFF))
                ),
                shape = shape
            )
            .border(
                width = 1.dp,
                color = MineHostOutline.copy(alpha = 0.75f),
                shape = shape
            )
            .then(clickableModifier)
    ) {
        content()
    }
}
