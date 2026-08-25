package com.example.ui.servercreation.steps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.server.template.TemplateRegistry
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.WizardTheme
import com.example.ui.servercreation.components.EngineArtwork
import com.example.ui.servercreation.components.SelectableOptionCard
import com.example.ui.servercreation.components.WizardInfoBanner

@Composable
fun EngineStep(
    draft: CreateServerDraft,
    isEngineAvailable: (String) -> Boolean,
    requiresManualVerification: (String) -> Boolean,
    onEngineSelected: (com.example.server.template.ServerTemplate) -> Unit
) {
    val templates = TemplateRegistry.ALL_TEMPLATES

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                "Choose Server Engine",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Text(
                "Select the engine software that will run your server.",
                style = MaterialTheme.typography.bodySmall,
                color = WizardTheme.SecondaryText
            )
        }

        templates.forEach { template ->
            val available = isEngineAvailable(template.id)
            val manualRequired = available && requiresManualVerification(template.id)
            SelectableOptionCard(
                title = template.name + when {
                    !available -> " (Unavailable)"
                    manualRequired -> " (Manual JAR required)"
                    else -> ""
                },
                description = when {
                    !available -> "No selectable verified versions are currently available for this engine."
                    manualRequired -> "${template.description} This catalog currently has no publisher SHA-256, so automatic download is blocked."
                    else -> template.description
                },
                selected = draft.engine?.id == template.id,
                enabled = available,
                onClick = { if (available) onEngineSelected(template) },
                icon = {
                    EngineArtwork(
                        engineId = template.id,
                        modifier = Modifier.fillMaxSize(),
                        alpha = if (available) 1.0f else 0.4f
                    )
                }
            )
        }

        WizardInfoBanner(
            text = "Engines marked Manual JAR required need the exact publisher JAR and an independently obtained SHA-256 on the Review step. MineHost verifies both before creating the profile."
        )
    }
}
