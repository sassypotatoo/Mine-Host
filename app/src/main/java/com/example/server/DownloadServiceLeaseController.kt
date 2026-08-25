package com.example.server

import android.content.Context
import android.content.Intent
import android.os.Build

/** Reference-counted owner for concurrent runtime/JAR preparation operations. */
object DownloadServiceLeaseController {
    private val registry = ReferenceCountedLeaseRegistry()

    fun acquire(context: Context, operationId: String, label: String): Result<Unit> {
        registry.acquire(operationId, label)
        return publish(context).onFailure {
            registry.release(operationId)
            ForegroundServiceFailureBus.publish(operationId, "download", it.message ?: "Unable to start setup service")
        }
    }

    fun release(context: Context, operationId: String) {
        val state = registry.release(operationId)
        if (state.isEmpty) stopService(context) else publish(context)
    }

    fun updateProgress(context: Context, operationId: String, content: String) {
        val intent = Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_UPDATE
            putExtra(DownloadForegroundService.EXTRA_PROGRESS_CONTENT, content)
            putExtra(DownloadForegroundService.EXTRA_OWNER_ID, operationId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    /** Called when Android ends the data-sync FGS time window. */
    fun onSystemTimeout() {
        val timedOut = registry.clear()
        timedOut.entries.forEach { (operationId, label) ->
            ForegroundServiceFailureBus.publish(
                operationId,
                "download",
                "Android stopped setup for $label after the allowed foreground-service window",
            )
        }
    }

    fun activeCount(): Int = registry.snapshot().count

    internal fun resetForTests() {
        registry.clear()
    }

    private fun stopService(context: Context) {
        runCatching {
            context.startService(
                Intent(context, DownloadForegroundService::class.java)
                    .setAction(DownloadForegroundService.ACTION_STOP),
            )
        }.onFailure {
            context.stopService(Intent(context, DownloadForegroundService::class.java))
        }
    }

    private fun publish(context: Context): Result<Unit> = runCatching {
        val state = registry.snapshot()
        check(!state.isEmpty) { "No setup lease is active" }
        val intent = Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_UPDATE
            putExtra(DownloadForegroundService.EXTRA_ACTIVE_COUNT, state.count)
            putExtra(DownloadForegroundService.EXTRA_LABELS, state.labels.joinToString(", "))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
}
