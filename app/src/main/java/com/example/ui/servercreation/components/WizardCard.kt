package com.example.ui.servercreation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.servercreation.WizardTheme

@Composable
fun WizardCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(WizardTheme.MainCardRadius),
                spotColor = Color(0xFF000000).copy(alpha = 0.05f)
            ),
        shape = RoundedCornerShape(WizardTheme.MainCardRadius),
        color = WizardTheme.Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, WizardTheme.Border)
    ) {
        Column(
            modifier = Modifier.padding(WizardTheme.CardPadding)
        ) {
            content()
        }
    }
}
