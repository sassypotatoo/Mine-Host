package com.example.server

import android.content.Context
import android.content.Intent
import android.os.Build

/** Keeps the runtime foreground service alive until the final real server lease ends. */
object ServerServiceLeaseController {
    private val registry = ReferenceCountedLeaseRegistry()

    fun acquire(context: Context, serverId: String, engineName: String): Result<Unit> {
        registry.acquire(serverId, engineName)
        return publish(context).onFailure {
            registry.release(serverId)
            ForegroundServiceFailureBus.publish(serverId, "server", it.message ?: "Unable to start server foreground service")
        }
    }

    fun update(context: Context, serverId: String, engineName: String): Result<Unit> {
        registry.update(serverId, engineName)
            ?: return Result.failure(IllegalStateException("Server lease is not active"))
        return publish(context)
    }

    fun release(context: Context, serverId: String) {
        val state = registry.release(serverId)
        if (state.isEmpty) stopService(context) else publish(context)
    }

    /** Publishes an actionable error for every server whose foreground owner was ended. */
    fun onSystemTimeout() {
        registry.snapshot().entries.forEach { (serverId, engineName) ->
            ForegroundServiceFailureBus.publish(
                serverId,
                "server",
                "Android ended the foreground service for $engineName while the server may still be running",
            )
        }
    }

    fun activeCount(): Int = registry.snapshot().count
    fun activeServerIds(): Set<String> = registry.snapshot().ids

    internal fun resetForTests() {
        registry.clear()
    }

    private fun stopService(context: Context) {
        runCatching {
            context.startService(
                Intent(context, ServerForegroundService::class.java)
                    .setAction(ServerForegroundService.ACTION_STOP),
            )
        }.onFailure {
            context.stopService(Intent(context, ServerForegroundService::class.java))
        }
    }

    private fun publish(context: Context): Result<Unit> = runCatching {
        val state = registry.snapshot()
        check(!state.isEmpty) { "No server lease is active" }
        val intent = Intent(context, ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_UPDATE
            putExtra(ServerForegroundService.EXTRA_ACTIVE_COUNT, state.count)
            putExtra(ServerForegroundService.EXTRA_ENGINE_NAMES, state.labels.joinToString(", "))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }
}
