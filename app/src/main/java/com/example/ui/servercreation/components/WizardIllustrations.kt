package com.example.ui.servercreation.components
 
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.servercreation.WizardTheme

@Composable
fun BasicsFloatingIslandArtwork(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF6366F1), Color(0xFFA855F7))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Landscape,
            contentDescription = "Server illustration",
            modifier = Modifier.size(64.dp),
            tint = Color.White
        )
    }
}

@Composable
fun EngineArtwork(
    engineId: String,
    modifier: Modifier = Modifier,
    alpha: Float = 1.0f
) {
    val icon = when (engineId) {
        "bedrock_power_nukkit_x" -> Icons.Default.Bolt
        "bedrock_power_nukkit" -> Icons.Default.ElectricBolt
        "bedrock_cloudburst_nukkit" -> Icons.Default.Cloud
        "nukkit-mot" -> Icons.Default.Memory
        "bedrock_nukkit" -> Icons.Default.Storage
        "java_paper" -> Icons.Default.Coffee
        "java_vanilla" -> Icons.Default.Public
        "java_fabric" -> Icons.Default.Extension
        else -> Icons.Default.Settings
    }
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = WizardTheme.PrimaryBlue.copy(alpha = alpha)
        )
    }
}

@Composable
fun VersionArtwork(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Inventory2,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = WizardTheme.PrimaryBlue
        )
    }
}

@Composable
fun WorldArtwork(
    worldType: String,
    modifier: Modifier = Modifier
) {
    val icon = when (worldType.lowercase()) {
        "survival" -> Icons.Default.Park
        "creative" -> Icons.Default.Diamond
        "adventure" -> Icons.Default.Explore
        "flat" -> Icons.Default.Layers
        else -> Icons.Default.Public
    }
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = WizardTheme.PrimaryBlue
        )
    }
}

@Composable
fun PerformanceArtwork(
    profile: String,
    modifier: Modifier = Modifier
) {
    val icon = when (profile.lowercase()) {
        "low", "low_resource", "low resource" -> Icons.Default.Eco
        "balanced" -> Icons.Default.Balance
        "performance", "high" -> Icons.Default.FlashOn
        else -> Icons.Default.Balance
    }
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = WizardTheme.PrimaryBlue
        )
    }
}

@Composable
fun TunnelArtwork(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.HdrStrong,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = WizardTheme.PrimaryBlue
        )
    }
}

@Composable
fun ReviewSuccessArtwork(
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = "Success",
        modifier = modifier,
        tint = WizardTheme.Success
    )
}
