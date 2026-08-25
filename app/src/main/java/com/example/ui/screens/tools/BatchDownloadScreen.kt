package com.example.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.server.BatchItemStatus
import com.example.ui.components.GlassCard
import com.example.ui.components.MineHostBrandHeader
import com.example.ui.components.MineHostPageTitle
import com.example.ui.theme.*

@Composable
fun BatchDownloadScreen(
    onBack: () -> Unit
) {
    val viewModel: BatchDownloadViewModel = viewModel()
    val items by viewModel.items.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    
    var showConfirmDialog by remember { mutableStateOf(false) }

    var requiredBytes by remember { mutableLongStateOf(0L) }
    var isLoadingSpace by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        LaunchedEffect(Unit) {
            isLoadingSpace = true
            requiredBytes = viewModel.getRequiredSpace()
            isLoadingSpace = false
        }
        
        if (!isLoadingSpace) {
            BatchConfirmDialog(
                requiredBytes = requiredBytes,
                onConfirm = {
                    showConfirmDialog = false
                    viewModel.startDownloads()
                },
                onDismiss = { showConfirmDialog = false }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(MineHostBackgroundTop, MineHostBackgroundBottom)
                )
            )
            .padding(horizontal = 16.dp)
    ) {
        MineHostBrandHeader(showBack = true, onBack = onBack, compact = true)
        
        MineHostPageTitle(
            title = "Download All Required Files",
            subtitle = "Prepare all Java runtimes and recommended server engines in one operation."
        )

        Spacer(Modifier.height(16.dp))

        val allDone = items.isNotEmpty() && items.all { it.status == BatchItemStatus.READY || it.status == BatchItemStatus.SKIPPED_ALREADY_VALID }

        if (allDone) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = MineHostGreen, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "All required files are ready.",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MineHostGreen
                        )
                    )
                }
            }
        } else if (isProcessing || items.any { it.status != BatchItemStatus.PENDING }) {
            val completedItems = items.count { it.status == BatchItemStatus.READY || it.status == BatchItemStatus.SKIPPED_ALREADY_VALID }
            val totalItems = items.size
            val overallProgress = if (totalItems > 0) completedItems.toFloat() / totalItems else 0f

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Overall Progress",
                            style = MaterialTheme.typography.titleSmall,
                            color = MineHostTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "$completedItems / $totalItems files ready",
                            style = MaterialTheme.typography.labelMedium,
                            color = MineHostBlue,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = MineHostBlue,
                        trackColor = MineHostOutline.copy(alpha = 0.3f),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        } else if (items.all { it.status == BatchItemStatus.PENDING }) {
            InfoCard(
                message = "This will download all supported Java runtimes (17, 21, 25) and the latest recommended versions of all server engines. This ensures MineHost is ready for any server configuration even without an internet connection later."
            )
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(items) { entry ->
                DownloadItemRow(entry)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            color = Color.Transparent
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isProcessing) {
                    Button(
                        onClick = { viewModel.stopDownloads() },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        val isCancelling = items.any { it.isCancelling }
                        Icon(if (isCancelling) Icons.Default.HourglassEmpty else Icons.Default.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isCancelling) "Cancelling..." else "Stop Batch")
                    }
                } else {
                    val hasFailures = items.any { it.status == BatchItemStatus.FAILED }
                    val hasCancelled = items.any { it.status == BatchItemStatus.CANCELLED }
                    
                    if (hasFailures) {
                        Button(
                            onClick = { viewModel.retryFailed() },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MineHostPurple),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry Failed")
                        }
                    }
                    
                    if (hasCancelled || (!hasFailures && !allDone)) {
                        Button(
                            onClick = { 
                                if (hasCancelled) viewModel.resumeRemaining() 
                                else showConfirmDialog = true 
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MineHostBlue),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Icon(if (hasCancelled) Icons.Default.PlayArrow else Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (hasCancelled) "Resume Remaining" else "Start Download All")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchConfirmDialog(
    requiredBytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val usableSpace = android.os.Environment.getDataDirectory().usableSpace
    val isSpaceLow = usableSpace < requiredBytes + (200 * 1024 * 1024)
    val requiredMb = requiredBytes / (1024 * 1024)
    val availableMb = usableSpace / (1024 * 1024)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Batch Download") },
        text = {
            Column {
                Text("This will download approximately ${requiredMb}MB of data.")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isSpaceLow) Icons.Default.Warning else Icons.Default.Storage,
                        null,
                        tint = if (isSpaceLow) MaterialTheme.colorScheme.error else MineHostBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Storage: ${availableMb}MB available",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSpaceLow) MaterialTheme.colorScheme.error else MineHostTextSecondary
                    )
                }
                if (isSpaceLow) {
                    Text(
                        "Warning: Storage is very low. Downloads may fail.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Recommendation: Use Wi-Fi to avoid mobile data charges.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MineHostTextSecondary
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MineHostBlue)) {
                Text("Start Now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MineHostBackgroundTop,
        titleContentColor = MineHostTextPrimary,
        textContentColor = MineHostTextSecondary
    )
}

@Composable
private fun InfoCard(message: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MineHostBlue,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MineHostTextSecondary,
                    lineHeight = 18.sp
                )
            )
        }
    }
}

@Composable
private fun DownloadItemRow(entry: com.example.server.BatchProgressEntry) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                StatusIcon(entry.status)
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.item.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MineHostTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
                if (entry.status == BatchItemStatus.DOWNLOADING && entry.totalBytes > 0) {
                     LinearProgressIndicator(
                         progress = { entry.bytesDownloaded.toFloat() / entry.totalBytes.toFloat() },
                         modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                         color = MineHostBlue,
                         trackColor = MineHostOutline.copy(alpha = 0.2f)
                     )
                }
                if (entry.progressMessage.isNotBlank()) {
                    Text(
                        entry.progressMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (entry.status == BatchItemStatus.FAILED) MaterialTheme.colorScheme.error 
                                    else MineHostTextSecondary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: BatchItemStatus) {
    when (status) {
        BatchItemStatus.PENDING -> Icon(Icons.Default.HourglassEmpty, null, tint = MineHostTextSecondary.copy(alpha = 0.3f))
        BatchItemStatus.CHECKING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MineHostTextSecondary)
        BatchItemStatus.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MineHostBlue)
        BatchItemStatus.VERIFYING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MineHostPurple)
        BatchItemStatus.EXTRACTING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MineHostPurple)
        BatchItemStatus.VALIDATING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MineHostGreen)
        BatchItemStatus.READY, BatchItemStatus.SKIPPED_ALREADY_VALID -> Icon(Icons.Default.CheckCircle, null, tint = MineHostGreen)
        BatchItemStatus.FAILED -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
        BatchItemStatus.CANCELLED -> Icon(Icons.Default.Block, null, tint = MineHostTextSecondary)
    }
}
