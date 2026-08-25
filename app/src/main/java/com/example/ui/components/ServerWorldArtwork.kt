package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ServerWorldArtwork(
    customIconPath: String?,
    activeWorldPath: String?, // This should be the full path to the world directory
    modifier: Modifier = Modifier,
    corner: Dp = 20.dp
) {
    var finalIconFile by remember(customIconPath, activeWorldPath) { mutableStateOf<File?>(null) }
    
    LaunchedEffect(customIconPath, activeWorldPath) {
        withContext(Dispatchers.IO) {
            // 1. Priority: Custom icon
            if (!customIconPath.isNullOrBlank()) {
                val custom = File(customIconPath)
                if (custom.exists()) {
                    finalIconFile = custom
                    return@withContext
                }
            }
            
            // 2. Priority: Active world icon
            if (!activeWorldPath.isNullOrBlank()) {
                val worldDir = File(activeWorldPath)
                if (worldDir.exists()) {
                    val candidateNames = listOf(
                        "world_icon.png", "world_icon.jpg", "world_icon.jpeg", "world_icon.webp",
                        "icon.png", "icon.jpg", "icon.jpeg", "icon.webp"
                    )
                    val found = candidateNames.firstNotNullOfOrNull { name ->
                        File(worldDir, name).takeIf { it.exists() }
                    }
                    if (found != null) {
                        finalIconFile = found
                        return@withContext
                    }
                }
            }
            
            finalIconFile = null
        }
    }

    if (finalIconFile != null) {
        AsyncImage(
            model = finalIconFile,
            contentDescription = "Server artwork",
            modifier = modifier.clip(RoundedCornerShape(corner)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(corner))
                .background(Color.LightGray.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Image,
                contentDescription = "Server artwork fallback",
                tint = Color.Gray.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
    }
}
