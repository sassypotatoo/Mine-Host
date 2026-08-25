package com.example.ui.screens.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.MainViewModel

@Composable
fun AiAssistantTabScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val serverId by viewModel.selectedServerId.collectAsState()
    AiAssistantScreen(viewModel, onBack, serverId.orEmpty())
}
