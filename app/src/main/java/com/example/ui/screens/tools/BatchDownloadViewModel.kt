package com.example.ui.screens.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MineHostApplication
import com.example.server.BatchPreparationManager
import kotlinx.coroutines.flow.StateFlow

class BatchDownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MineHostApplication
    private val batchManager = app.batchPreparationManager

    val items = batchManager.items
    val isProcessing = batchManager.isProcessing

    init {
        batchManager.prepareQueue()
    }

    fun startDownloads() {
        batchManager.start()
    }

    fun retryFailed() {
        batchManager.retryFailed()
    }

    fun resumeRemaining() {
        batchManager.resumeRemaining()
    }

    fun stopDownloads() {
        batchManager.stop()
    }

    suspend fun getRequiredSpace() = batchManager.calculateRequiredSpace()
}
