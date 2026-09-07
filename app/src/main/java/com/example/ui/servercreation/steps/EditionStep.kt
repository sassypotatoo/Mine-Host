package com.example.ui.servercreation.steps

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ServerEdition
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.WizardTheme
import com.example.ui.servercreation.components.SelectableOptionCard
import com.example.ui.servercreation.components.WizardInfoBanner

@Composable
fun EditionStep(
    draft: CreateServerDraft,
    onDraftUpdate: (CreateServerDraft) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                "Choose Edition",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Select which version of Minecraft this server will run.",
                style = MaterialTheme.typography.bodySmall,
                color = WizardTheme.SecondaryText
            )
        }

        SelectableOptionCard(
            title = "Bedrock Edition",
            description = "For players on Mobile (iOS/Android), Consoles (Xbox/Switch/PlayStation), and Windows 10/11.",
            selected = draft.edition == ServerEdition.BEDROCK,
            onClick = {
                onDraftUpdate(draft.copy(edition = ServerEdition.BEDROCK, engine = null, engineVersionId = null, bedrockVersion = null))
            }
        )

        SelectableOptionCard(
            title = "Java Edition",
            description = "For players on Windows, Mac, and Linux using the original Java Edition client.",
            selected = draft.edition == ServerEdition.JAVA,
            onClick = {
                onDraftUpdate(draft.copy(edition = ServerEdition.JAVA, engine = null, engineVersionId = null, bedrockVersion = null))
            }
        )

        if (draft.edition == ServerEdition.JAVA) {
            WizardInfoBanner(
                text = "Java Edition requires more power and is recommended only for high-end Android devices.",
                icon = Icons.Default.WarningAmber,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                textColor = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}
