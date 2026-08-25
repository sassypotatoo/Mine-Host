package com.example.server.updates


data class EngineUpdateCheckState(
    val baselineCreated: Boolean = false,
    val baselineCreatedAt: Long? = null,
    val lastSuccessfulCheckAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val baselineSourceIds: Set<String> = emptySet(),
    val baselineCompletedSources: Set<String> = emptySet()
)
