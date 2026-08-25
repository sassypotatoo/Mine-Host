package com.example.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class ForegroundServiceFailure(
    val ownerId: String?,
    val service: String,
    val message: String,
)

object ForegroundServiceFailureBus {
    private val _events = MutableSharedFlow<ForegroundServiceFailure>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun publish(ownerId: String?, service: String, message: String) {
        _events.tryEmit(ForegroundServiceFailure(ownerId, service, message))
    }
}
