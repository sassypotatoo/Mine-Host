package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = MineHostBlue,
    secondary = MineHostSurface,
    tertiary = MineHostGreen,
    background = MineHostBackgroundTop,
    surface = MineHostSurface,
    onPrimary = Color.White,
    onSecondary = MineHostTextPrimary,
    onTertiary = Color.White,
    onBackground = MineHostTextPrimary,
    onSurface = MineHostTextPrimary,
    surfaceVariant = MineHostSurfaceVariant,
    onSurfaceVariant = MineHostTextSecondary,
    outline = MineHostOutline
)

private val LightColorScheme = lightColorScheme(
    primary = MineHostBlue,
    secondary = Color(0xFFE2E8F0),
    tertiary = Color(0xFF059669),
    background = Color.White,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color(0xFF1E293B),
    onTertiary = Color.White,
    onBackground = Color(0xFF1E293B),
    onSurface = Color(0xFF1E293B),
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false, // Disable dynamic color for a more branded experience
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

object MineHostTheme {
  val cardCornerRadius = 16.dp
  val bottomBarHeight = 64.dp
}
