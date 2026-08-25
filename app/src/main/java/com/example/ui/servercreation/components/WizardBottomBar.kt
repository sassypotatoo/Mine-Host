package com.example.ui.servercreation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.servercreation.WizardTheme

@Composable
fun WizardBottomBar(
    backText: String = "Back",
    continueText: String = "Continue",
    onBack: () -> Unit,
    onContinue: () -> Unit,
    backEnabled: Boolean = true,
    continueEnabled: Boolean = true,
    isLastStep: Boolean = false,
    isLoading: Boolean = false
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp),
        color = Color.White,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Back Button
            Button(
                onClick = onBack,
                enabled = backEnabled && !isLoading,
                modifier = Modifier
                    .weight(0.4f)
                    .height(WizardTheme.ButtonHeight),
                shape = RoundedCornerShape(WizardTheme.InputRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE9F1FB),
                    contentColor = WizardTheme.SecondaryText,
                    disabledContainerColor = Color(0xFFF1F4F9),
                    disabledContentColor = WizardTheme.SecondaryText.copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(backText, fontWeight = FontWeight.Bold)
            }

            // Continue/Create Button
            Button(
                onClick = onContinue,
                enabled = continueEnabled && !isLoading,
                modifier = Modifier
                    .weight(0.6f)
                    .height(WizardTheme.ButtonHeight),
                shape = RoundedCornerShape(WizardTheme.InputRadius),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = if (continueEnabled && !isLoading) Color.White else WizardTheme.DisabledText,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = WizardTheme.DisabledText
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = if (continueEnabled && !isLoading) {
                                Brush.linearGradient(
                                    listOf(WizardTheme.GradientLeft, WizardTheme.GradientRight)
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(Color(0xFFF1F4F9), Color(0xFFF1F4F9))
                                )
                            },
                            shape = RoundedCornerShape(WizardTheme.InputRadius)
                        )
                        .then(
                            if (!continueEnabled || isLoading) {
                                Modifier.border(1.dp, WizardTheme.Border, RoundedCornerShape(WizardTheme.InputRadius))
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = WizardTheme.PrimaryBlue,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isLastStep) "Create Server" else continueText,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
