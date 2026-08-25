package com.example.ui.servercreation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.servercreation.WizardTheme

@Composable
fun SelectableOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
    tag: String? = null,
    tagColor: Color = WizardTheme.Success,
    tagBackground: Color = Color(0xFFE8F9F0)
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(WizardTheme.OptionCardRadius),
        color = Color.Transparent,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) Color.Transparent else WizardTheme.Border
        )
    ) {
        Box(
            modifier = Modifier
                .background(
                    brush = if (selected) {
                        Brush.linearGradient(
                            listOf(WizardTheme.SelectedCardGradientLeft, WizardTheme.SelectedCardGradientRight)
                        )
                    } else {
                        Brush.linearGradient(listOf(Color.White, Color.White))
                    }
                )
                .then(
                    if (!enabled) Modifier.background(Color.Black.copy(alpha = 0.03f)) else Modifier
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(WizardTheme.GradientLeft, WizardTheme.GradientRight)
                            ),
                            shape = RoundedCornerShape(WizardTheme.OptionCardRadius)
                        )
                    } else Modifier
                )
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }
                    Spacer(Modifier.width(16.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = if (selected) WizardTheme.PrimaryBlue else WizardTheme.PrimaryText
                            )
                        )
                        if (tag != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = tagBackground,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    tag,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = tagColor,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = WizardTheme.SecondaryText,
                            lineHeight = 16.sp,
                            fontSize = 12.sp
                        )
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Custom Radio effect
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            color = if (selected) WizardTheme.PrimaryBlue else Color.Transparent,
                            shape = CircleShape
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) WizardTheme.PrimaryBlue else WizardTheme.Border,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
