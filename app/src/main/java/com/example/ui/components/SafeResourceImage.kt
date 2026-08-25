package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.ui.theme.MineHostBlue
import com.example.ui.theme.MineHostBackgroundBottom

/**
 * A safe wrapper for loading local resources.
 * Updated to remove Coil dependency for wizard stability.
 */
@Composable
fun SafeResourceImage(
    @DrawableRes resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    fallback: @Composable () -> Unit = { DefaultFallback() }
) {
    Image(
        painter = painterResource(id = resId),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale
    )
}

@Composable
private fun DefaultFallback() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MineHostBackgroundBottom),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.ImageNotSupported,
            contentDescription = "Image load failed",
            tint = MineHostBlue.copy(alpha = 0.5f)
        )
    }
}
