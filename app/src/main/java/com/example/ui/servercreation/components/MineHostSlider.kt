package com.example.ui.servercreation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.ui.servercreation.WizardTheme

@Composable
fun MineHostSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    var sliderWidth by remember { mutableStateOf(0f) }
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val thumbRadius = 14.dp.toPx()
                        val availableWidth = (sliderWidth - 2 * thumbRadius).coerceAtLeast(1f)
                        val newValue = ((offset.x - thumbRadius) / availableWidth).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + newValue * (valueRange.endInclusive - valueRange.start))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val thumbRadius = 14.dp.toPx()
                        val availableWidth = (sliderWidth - 2 * thumbRadius).coerceAtLeast(1f)
                        val newValue = ((change.position.x - thumbRadius) / availableWidth).coerceIn(0f, 1f)
                        onValueChange(valueRange.start + newValue * (valueRange.endInclusive - valueRange.start))
                    }
                }
        ) {
            sliderWidth = size.width
            val thumbRadius = 14.dp.toPx()
            val availableWidth = (size.width - 2 * thumbRadius).coerceAtLeast(0f)
            val fraction = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
            val thumbX = thumbRadius + (availableWidth * fraction)
            val centerY = size.height / 2
            val trackHeight = 6.dp.toPx()
            
            // Inactive track (Soft Lavender)
            drawRoundRect(
                color = Color(0xFFE8EAF6),
                topLeft = Offset(thumbRadius, centerY - trackHeight / 2),
                size = Size(availableWidth, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2, trackHeight / 2)
            )
            
            // Active track (MineHost Gradient)
            drawRoundRect(
                brush = Brush.linearGradient(
                    listOf(WizardTheme.GradientLeft, WizardTheme.GradientRight)
                ),
                topLeft = Offset(thumbRadius, centerY - trackHeight / 2),
                size = Size(thumbX - thumbRadius, trackHeight),
                cornerRadius = CornerRadius(trackHeight / 2, trackHeight / 2)
            )
            
            // Thumb
            val thumbCenter = Offset(thumbX, centerY)
            
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = thumbCenter
            )
            
            drawCircle(
                brush = Brush.linearGradient(
                    listOf(WizardTheme.GradientLeft, WizardTheme.GradientRight)
                ),
                radius = thumbRadius,
                center = thumbCenter,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
