package com.example.server

/** Converts process truth into a final UI-safe state without inventing activity. */
object RuntimeStateTruth {
    fun reconcile(reported: ServerStatus, processAlive: Boolean): ServerStatus = when {
        processAlive -> reported
        reported == ServerStatus.STOPPING -> ServerStatus.STOPPED
        reported == ServerStatus.WORLD_PROVISIONALLY_LOADED -> ServerStatus.WORLD_LOAD_FAILED
        reported == ServerStatus.WORLD_VERIFIED || reported == ServerStatus.ONLINE -> ServerStatus.CRASHED
        reported == ServerStatus.STARTING ||
            reported == ServerStatus.PROCESS_STARTED ||
            reported == ServerStatus.NETWORK_READY ||
            reported == ServerStatus.ENGINE_READY -> ServerStatus.FAILED
        else -> reported
    }
}
