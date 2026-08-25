package com.example.ui.servercreation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.servercreation.WizardStep
import com.example.ui.servercreation.WizardTheme

@Composable
fun WizardProgressIndicator(
    currentStep: WizardStep,
    modifier: Modifier = Modifier
) {
    val steps = WizardStep.entries
    val currentIndex = steps.indexOf(currentStep)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        steps.forEachIndexed { index, step ->
            val isCompleted = index < currentIndex
            val isCurrent = index == currentIndex
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Left Connector
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.5.dp)
                            .background(
                                if (index == 0) Color.Transparent 
                                else if (index <= currentIndex) WizardTheme.PrimaryBlue 
                                else WizardTheme.InactiveProgress
                            )
                    )
                    
                    // Circle
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                brush = if (isCurrent) {
                                    Brush.linearGradient(
                                        listOf(WizardTheme.GradientLeft, WizardTheme.GradientRight)
                                    )
                                } else if (isCompleted) {
                                    Brush.linearGradient(listOf(WizardTheme.PrimaryBlue, WizardTheme.PrimaryBlue))
                                } else {
                                    Brush.linearGradient(listOf(Color.White, Color.White))
                                },
                                shape = CircleShape
                            )
                            .border(
                                width = if (!isCurrent && !isCompleted) 1.dp else 0.dp,
                                color = if (!isCurrent && !isCompleted) WizardTheme.InactiveProgress else Color.Transparent,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCompleted) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = if (isCurrent) Color.White else WizardTheme.SecondaryText,
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                    
                    // Right Connector
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.5.dp)
                            .background(
                                if (index == steps.size - 1) Color.Transparent 
                                else if (index < currentIndex) WizardTheme.PrimaryBlue 
                                else WizardTheme.InactiveProgress
                            )
                    )
                }
                
                Spacer(Modifier.height(4.dp))
                
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isCurrent) WizardTheme.PrimaryBlue else WizardTheme.SecondaryText,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 8.5.sp
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
