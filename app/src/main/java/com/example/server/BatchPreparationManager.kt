package com.example.server

import android.content.Context
import android.util.Log
import com.example.server.termux.TermuxPackageResolver
import com.example.server.version.EngineVersion
import com.example.server.version.EngineVersionCatalogRepository
import com.example.server.version.VersionSourceType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

sealed class BatchDownloadItem(
    val id: String,
    val displayName: String
) {
    class JavaRuntime(val major: Int) : BatchDownloadItem("java_$major", "Java $major Runtime")
    class Engine(val version: EngineVersion) : BatchDownloadItem("engine_${version.id}", version.displayName)
}

enum class BatchItemStatus {
    PENDING,
    CHECKING,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    VALIDATING,
    READY,
    SKIPPED_ALREADY_VALID,
    FAILED,
    CANCELLED
}

data class BatchProgressEntry(
    val item: BatchDownloadItem,
    val status: BatchItemStatus = BatchItemStatus.PENDING,
    val progressMessage: String = "",
    val error: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val isCancelling: Boolean = false
)

data class BatchPreparationInfo(
    val itemsToDownload: Int,
    val itemsToVerify: Int,
    val totalEstimatedBytes: Long,
    val availableBytes: Long,
    val isMobileData: Boolean
)

class BatchPreparationManager(
    private val context: Context,
    private val catalogRepository: EngineVersionCatalogRepository,
    private val applicationScope: CoroutineScope
) {
    private val _items = MutableStateFlow<List<BatchProgressEntry>>(emptyList())
    val items = _items.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val operationMutex = Mutex()

    private val _isInitialCheckComplete = MutableStateFlow(false)
    val isInitialCheckComplete = _isInitialCheckComplete.asStateFlow()

    private var job: Job? = null
    private var initialCheckJob: Job? = null
    private val operationId = "batch_download_${UUID.randomUUID()}"
    private var lastNotificationTime = 0L
    
    private val persistenceFile = File(context.noBackupFilesDir, ".minehost/batch-operation.json")

    init {
        loadPersistedState()
    }

    private fun loadPersistedState() {
        if (persistenceFile.isFile) {
            try {
                val json = JSONObject(persistenceFile.readText())
                // Basic reconciliation - we mostly care about what was requested
                // We will re-run integrity checks anyway
            } catch (e: Exception) {
                persistenceFile.delete()
            }
        }
    }

    private fun persistState() {
        try {
            persistenceFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val json = JSONObject().apply {
                put("operationId", operationId)
                put("startTime", System.currentTimeMillis())
                put("isProcessing", _isProcessing.value)
                put("items", JSONArray().apply {
                    _items.value.forEach { entry ->
                        put(JSONObject().apply {
                            put("id", entry.item.id)
                            put("status", entry.status.name)
                            put("bytesDownloaded", entry.bytesDownloaded)
                            put("totalBytes", entry.totalBytes)
                        })
                    }
                })
            }
            persistenceFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e("BatchManager", "Failed to persist state", e)
        }
    }

    fun prepareQueue() {
        if (_items.value.isNotEmpty()) return
        
        initialCheckJob = applicationScope.launch(Dispatchers.IO) {
            val queue = mutableListOf<BatchDownloadItem>()
            
            // Add Java runtimes
            JavaRuntimeManager.supportedRuntimeMajors().forEach { major ->
                queue.add(BatchDownloadItem.JavaRuntime(major))
            }

            // Add recommended engines for specific IDs
            val expectedEngineIds = listOf(
                "bedrock_power_nukkit",
                "bedrock_power_nukkit_x",
                "bedrock_nukkit",
                "nukkit-mot"
            )

            expectedEngineIds.forEach { engineId ->
                val recommended = catalogRepository.getAllVersions()
                    .filter { it.engineId == engineId && it.recommended }
                
                if (recommended.isNotEmpty()) {
                    recommended.forEach { version ->
                        queue.add(BatchDownloadItem.Engine(version))
                    }
                }
            }

            _items.value = queue.map { BatchProgressEntry(it) }
            persistState()

            // Perform initial integrity check
            val currentList = _items.value
            currentList.forEachIndexed { index, entry ->
                if (_isProcessing.value) return@forEachIndexed
                updateItemStatus(index, BatchItemStatus.CHECKING, "Verifying local files...")
                val result = checkItemIntegrity(entry.item)
                
                _items.update { list ->
                    if (index < list.size && (list[index].status == BatchItemStatus.CHECKING || list[index].status == BatchItemStatus.PENDING)) {
                        list.toMutableList().also { newList ->
                            newList[index] = newList[index].copy(
                                status = if (result.success) BatchItemStatus.SKIPPED_ALREADY_VALID else BatchItemStatus.PENDING,
                                progressMessage = result.message
                            )
                        }
                    } else list
                }
            }
            _isInitialCheckComplete.value = true
            persistState()
        }
    }

    private suspend fun checkItemIntegrity(item: BatchDownloadItem): ProcessResult {
        return when (item) {
            is BatchDownloadItem.JavaRuntime -> {
                val integrity = JavaRuntimeManager.verifyRuntimeIntegrity(context, item.major)
                if (integrity is RuntimeIntegrityResult.Valid) {
                    val validation = JavaRuntimeManager.runDetailedValidation(
                        launcherFile = JavaRuntimeManager.requireLauncher(context, integrity.runtimeHome),
                        runtimeHome = integrity.runtimeHome,
                        javaMajor = item.major,
                        context = context
                    )
                    if (validation.success) {
                        ProcessResult(true, BatchItemStatus.SKIPPED_ALREADY_VALID, "Verified.")
                    } else {
                        ProcessResult(false, BatchItemStatus.PENDING, "Needs repair.")
                    }
                } else {
                    ProcessResult(false, BatchItemStatus.PENDING, "Not installed.")
                }
            }
            is BatchDownloadItem.Engine -> {
                val verified = Downloader.verifyCachedEngineArtifact(
                    context = context,
                    version = item.version,
                    invalidateInvalid = true,
                )

                if (verified != null) {
                    val label = verified.resolvedIdentity?.let { identity ->
                        "Verified resolved build #${identity.resolvedBuildNumber}."
                    } ?: "Verified."
                    ProcessResult(
                        true,
                        BatchItemStatus.SKIPPED_ALREADY_VALID,
                        label,
                    )
                } else {
                    ProcessResult(
                        false,
                        BatchItemStatus.PENDING,
                        "Missing or invalid.",
                    )
                }
            }
        }
    }

    fun start() {
        applicationScope.launch {
            operationMutex.withLock {
                if (_isProcessing.value) return@withLock
                
                _isProcessing.value = true
                persistState()

                job = applicationScope.launch(Dispatchers.IO) {
            initialCheckJob?.join()

            val monitorJob = launch {
                ForegroundServiceFailureBus.events.collect { failure ->
                    if (failure.ownerId == operationId) {
                        job?.cancel(CancellationException("System stopped setup: ${failure.message}"))
                    }
                }
            }

            try {
                val requiredBytes = calculateRequiredSpace()
                val availableBytes = context.filesDir.usableSpace
                if (availableBytes < requiredBytes + (100 * 1024 * 1024)) {
                    _isProcessing.value = false
                    _items.update { list ->
                        list.map { entry ->
                            if (entry.status == BatchItemStatus.PENDING) {
                                entry.copy(status = BatchItemStatus.FAILED, progressMessage = "Insufficient storage.")
                            } else entry
                        }
                    }
                    persistState()
                    return@launch
                }

                DownloadServiceLeaseController.acquire(context, operationId, "Batch download")
                    .onFailure { error ->
                        _isProcessing.value = false
                        _items.update { list ->
                            list.map { entry ->
                                if (entry.status == BatchItemStatus.PENDING) {
                                    entry.copy(status = BatchItemStatus.FAILED, progressMessage = "Foreground service blocked: ${error.message}")
                                } else entry
                            }
                        }
                        persistState()
                        return@launch
                    }
                
                for (index in _items.value.indices) {
                    val entry = _items.value[index]
                    if (entry.status == BatchItemStatus.SKIPPED_ALREADY_VALID || entry.status == BatchItemStatus.READY) continue

                    // Re-verify integrity (Part 4)
                    val check = checkItemIntegrity(entry.item)
                    if (check.success) {
                        updateItemStatus(index, BatchItemStatus.READY, check.message)
                        continue
                    }

                    updateItemStatus(index, BatchItemStatus.DOWNLOADING, "Starting...")

                    try {
                        val result = processItem(entry.item) { msg, status, bytes, total ->
                            updateItemStatus(index, status ?: BatchItemStatus.DOWNLOADING, msg, bytesDownloaded = bytes, totalBytes = total)
                            updateNotification(index)
                        }

                        if (result.success) {
                            updateItemStatus(index, result.status, result.message)
                        } else {
                            updateItemStatus(index, BatchItemStatus.FAILED, result.message, result.message)
                        }
                    } catch (e: CancellationException) {
                        updateItemStatus(index, BatchItemStatus.CANCELLED, "Cancelled by user.")
                        throw e
                    } catch (e: Exception) {
                        updateItemStatus(index, BatchItemStatus.FAILED, "Error: ${e.message}", e.message)
                    }
                    persistState()
                }
            } catch (e: CancellationException) {
                _items.update { list ->
                    list.map { entry ->
                        if (entry.status == BatchItemStatus.DOWNLOADING || entry.status == BatchItemStatus.PENDING || entry.status == BatchItemStatus.CHECKING) {
                            entry.copy(status = BatchItemStatus.CANCELLED, progressMessage = "Batch cancelled.")
                        } else entry
                    }
                }
                throw e
            } finally {
                DownloadServiceLeaseController.release(context, operationId)
                _isProcessing.value = false
                persistState()
                if (_items.value.all { it.status == BatchItemStatus.READY || it.status == BatchItemStatus.SKIPPED_ALREADY_VALID }) {
                    persistenceFile.delete()
                }
            }
        }
        }
    }
}

    private fun updateNotification(currentIndex: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < 1500L) return // Throttled updates
        lastNotificationTime = now

        val list = _items.value
        val completed = list.count { it.status == BatchItemStatus.READY || it.status == BatchItemStatus.SKIPPED_ALREADY_VALID }
        val current = list.getOrNull(currentIndex) ?: return
        
        val progressText = if (current.totalBytes > 0) {
            "${(current.bytesDownloaded * 100 / current.totalBytes)}% - ${formatBytes(current.bytesDownloaded)} / ${formatBytes(current.totalBytes)}"
        } else {
            current.progressMessage
        }

        val content = "Batch: $completed/${list.size} - ${current.item.displayName}: $progressText"
        DownloadServiceLeaseController.updateProgress(context, operationId, content)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    suspend fun getPreparationInfo(): BatchPreparationInfo {
        initialCheckJob?.join()
        val list = _items.value
        val toDownload = list.count { it.status == BatchItemStatus.PENDING || it.status == BatchItemStatus.FAILED }
        val toVerify = list.count { it.status == BatchItemStatus.SKIPPED_ALREADY_VALID }
        val estimated = calculateRequiredSpace()
        val available = context.filesDir.usableSpace
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val isMobile = cm?.let {
            val network = it.activeNetwork
            val capabilities = it.getNetworkCapabilities(network)
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } ?: false

        return BatchPreparationInfo(
            itemsToDownload = toDownload,
            itemsToVerify = toVerify,
            totalEstimatedBytes = estimated,
            availableBytes = available,
            isMobileData = isMobile
        )
    }

    suspend fun calculateRequiredSpace(): Long {
        var total = 0L
        val currentItems = _items.value
        
        for (entry in currentItems) {
            if (entry.status == BatchItemStatus.SKIPPED_ALREADY_VALID || entry.status == BatchItemStatus.READY) continue
            
            total += when (val item = entry.item) {
                is BatchDownloadItem.JavaRuntime -> {
                    try {
                        val resolver = TermuxPackageResolver(OkHttpClient())
                        val packages = resolver.resolveDependencies("openjdk-${item.major}")
                        packages.sumOf { it.size } + (50 * 1024 * 1024) // size + staging buffer
                    } catch (e: Exception) {
                        450L * 1024 * 1024 // Fallback
                    }
                }
                is BatchDownloadItem.Engine -> {
                    item.version.fileSize?.takeIf { it > 0 } ?: (75L * 1024 * 1024)
                }
            }
        }
        return total
    }

    fun retryFailed() {
        if (_isProcessing.value) return
        _items.update { list ->
            list.map { entry ->
                if (entry.status == BatchItemStatus.FAILED) {
                    entry.copy(status = BatchItemStatus.PENDING, progressMessage = "Ready for retry.", error = null)
                } else {
                    entry
                }
            }
        }
        start()
    }

    fun resumeRemaining() {
        if (_isProcessing.value) return
        _items.update { list ->
            list.map { entry ->
                if (entry.status == BatchItemStatus.CANCELLED || entry.status == BatchItemStatus.PENDING) {
                    entry.copy(status = BatchItemStatus.PENDING, progressMessage = "Resuming...", error = null)
                } else {
                    entry
                }
            }
        }
        start()
    }

    fun stop() {
        _items.update { list ->
            list.map { entry ->
                if (entry.status == BatchItemStatus.DOWNLOADING) {
                    entry.copy(isCancelling = true)
                } else entry
            }
        }
        job?.cancel()
        Downloader.cancel(operationId)
        // isProcessing is set to false in finally block of start()
    }

    private fun updateItemStatus(
        index: Int,
        status: BatchItemStatus,
        message: String,
        error: String? = null,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = 0L
    ) {
        _items.update { list ->
            if (index < 0 || index >= list.size) return@update list
            list.toMutableList().also { newList ->
                newList[index] = newList[index].copy(
                    status = status,
                    progressMessage = message,
                    error = error,
                    bytesDownloaded = if (bytesDownloaded > 0) bytesDownloaded else newList[index].bytesDownloaded,
                    totalBytes = if (totalBytes > 0) totalBytes else newList[index].totalBytes
                )
            }
        }
    }

    private suspend fun processItem(
        item: BatchDownloadItem,
        onProgress: (String, BatchItemStatus?, Long, Long) -> Unit
    ): ProcessResult {
        return when (item) {
            is BatchDownloadItem.JavaRuntime -> {
                val result = JavaRuntimeManager.ensureRuntimeReady(context, item.major) { msg ->
                    val status = when {
                        msg.contains("Downloading", ignoreCase = true) -> BatchItemStatus.DOWNLOADING
                        msg.contains("Extracting", ignoreCase = true) -> BatchItemStatus.EXTRACTING
                        msg.contains("Validating", ignoreCase = true) -> BatchItemStatus.VALIDATING
                        else -> BatchItemStatus.DOWNLOADING
                    }
                    onProgress(msg, status, 0, 0)
                }
                
                if (result is RuntimePreparationResult.Ready) {
                    ProcessResult(true, BatchItemStatus.READY, "Java ${item.major} ready.")
                } else {
                    ProcessResult(false, BatchItemStatus.FAILED, (result as? RuntimePreparationResult.Failure)?.message ?: "Java installation failed.")
                }
            }
            is BatchDownloadItem.Engine -> {
                val version = item.version
                // PART 5: ensureEngineCached logic
                val tempFile = File(context.cacheDir, "batch_download_${version.id}.jar")
                
                try {
                    val downloadRes = Downloader.downloadServerJar(context, version, tempFile, operationId = operationId) { msg ->
                        var bytes = 0L
                        var total = 0L
                        if (msg.contains("%")) {
                            val percent = msg.substringAfter("(").substringBefore("%").toLongOrNull() ?: 0L
                            bytes = percent
                            total = 100L
                        }
                        onProgress(msg, BatchItemStatus.DOWNLOADING, bytes, total)
                    }

                    when (downloadRes) {
                        is ServerJarDownloadResult.Success -> {
                            onProgress(
                                "Verifying shared cache...",
                                BatchItemStatus.VALIDATING,
                                0,
                                0,
                            )

                            val verified = Downloader.verifyCachedEngineArtifact(
                                context = context,
                                version = version,
                                invalidateInvalid = true,
                            )

                            if (verified != null) {
                                val label = verified.resolvedIdentity?.let { identity ->
                                    "Engine build #${identity.resolvedBuildNumber} cached and verified."
                                } ?: "Engine cached and verified."
                                ProcessResult(
                                    true,
                                    BatchItemStatus.READY,
                                    label,
                                )
                            } else {
                                ProcessResult(
                                    false,
                                    BatchItemStatus.FAILED,
                                    "Downloaded engine could not be committed to the verified shared cache.",
                                )
                            }
                        }

                        ServerJarDownloadResult.Cancelled -> ProcessResult(
                            false,
                            BatchItemStatus.FAILED,
                            "Download cancelled.",
                        )

                        is ServerJarDownloadResult.Failure -> ProcessResult(
                            false,
                            BatchItemStatus.FAILED,
                            downloadRes.message,
                        )
                    }
                } finally {
                    tempFile.delete()
                }
            }
        }
    }


    private data class ProcessResult(
        val success: Boolean,
        val status: BatchItemStatus,
        val message: String
    )
}
