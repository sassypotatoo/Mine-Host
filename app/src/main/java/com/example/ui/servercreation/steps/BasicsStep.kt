package com.example.ui.servercreation.steps

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.servercreation.CreateServerDraft
import com.example.ui.servercreation.WizardTheme
import com.example.ui.servercreation.components.BasicsFloatingIslandArtwork
import com.example.ui.servercreation.components.WizardInfoBanner

@Composable
fun BasicsStep(
    draft: CreateServerDraft,
    onDraftUpdate: (CreateServerDraft) -> Unit
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> 
            if (uri != null) {
                onDraftUpdate(draft.copy(artworkUri = uri))
            }
        }
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Server Artwork
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(WizardTheme.OptionCardRadius)),
            contentAlignment = Alignment.Center
        ) {
            // Background Artwork (Gradient + Landscape Vector)
            BasicsFloatingIslandArtwork(
                modifier = Modifier.fillMaxSize()
            )

            // Circular Choose Image Button
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(elevation = 6.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = "Choose Image",
                    modifier = Modifier.size(36.dp),
                    tint = WizardTheme.PrimaryBlue
                )
            }
        }

        // Server Name
        Column {
            Text(
                "Server Name",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = WizardTheme.PrimaryText
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.serverName,
                onValueChange = { 
                    if (it.length <= 32) onDraftUpdate(draft.copy(serverName = it))
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. My Survival Server", color = WizardTheme.SecondaryText.copy(alpha = 0.5f)) },
                shape = RoundedCornerShape(WizardTheme.InputRadius),
                leadingIcon = {
                    Icon(Icons.Outlined.Dns, contentDescription = null, tint = WizardTheme.PrimaryBlue)
                },
                trailingIcon = {
                    Text(
                        "${draft.serverName.length}/32",
                        style = MaterialTheme.typography.bodySmall,
                        color = WizardTheme.SecondaryText.copy(alpha = 0.5f)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WizardTheme.PrimaryBlue,
                    unfocusedBorderColor = WizardTheme.Border,
                    focusedContainerColor = WizardTheme.SoftBlue.copy(alpha = 0.3f),
                    unfocusedContainerColor = Color.Transparent
                ),
                singleLine = true
            )
        }

        // Description
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Description",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = WizardTheme.PrimaryText
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "(optional)",
                    style = MaterialTheme.typography.bodySmall,
                    color = WizardTheme.SecondaryText
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.description,
                onValueChange = {
                    if (it.length <= 120) onDraftUpdate(draft.copy(description = it))
                },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Tell players what makes your server unique...", color = WizardTheme.SecondaryText.copy(alpha = 0.5f)) },
                shape = RoundedCornerShape(WizardTheme.InputRadius),
                trailingIcon = {
                    Box(modifier = Modifier.fillMaxHeight().padding(end = 12.dp, bottom = 12.dp), contentAlignment = Alignment.BottomEnd) {
                        Text(
                            "${draft.description.length}/120",
                            style = MaterialTheme.typography.bodySmall,
                            color = WizardTheme.SecondaryText.copy(alpha = 0.5f)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = WizardTheme.PrimaryBlue,
                    unfocusedBorderColor = WizardTheme.Border,
                    focusedContainerColor = WizardTheme.SoftBlue.copy(alpha = 0.3f),
                    unfocusedContainerColor = Color.Transparent
                )
            )
        }

        WizardInfoBanner(
            text = "You can change these details anytime from your server settings."
        )
    }
}
